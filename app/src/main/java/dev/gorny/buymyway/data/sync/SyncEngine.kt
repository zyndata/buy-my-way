package dev.gorny.buymyway.data.sync

import androidx.room.withTransaction
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.sync.NodeCodec
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.local.toDomain
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.RemoteLists
import dev.gorny.buymyway.data.remote.asNode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Locale

/**
 * Room ↔ RTDB for one signed-in user's own lists (PLAN.md Phase 4, tasks 2 and 5; STATE.md
 * decisions 54–56). Nothing here decides *when* to run: [SyncController] does that in the
 * foreground and [OutboxWorker] in the background. Everything here is safe to run twice.
 *
 * - [flush] sends: first every owned list RTDB does not have yet, whole; then the outbox, op
 *   by op, in the order the ops were made. An acknowledged op leaves the outbox. An op the
 *   rules refuse means RTDB holds something newer: the list is read, merged into Room, and the
 *   op is dropped, so the phone adopts the newer state.
 * - [catchUp] reads: `/userLists/{uid}`, then per list the meta, the categories, and the items
 *   whose server-stamped `changedAt` is at least `seenUpTo`, all merged through [Merge].
 */
class SyncEngine(
    private val db: AppDatabase,
    private val repo: ListRepository,
    private val remote: RemoteLists,
    private val prefs: PrefsSync? = null,
    /** False when the Firebase session is gone: then a refusal says nothing about the data. */
    private val sessionValid: suspend () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val mutex = Mutex()

    /** The session ended while sending: nothing was dropped, the caller asks to sign in again. */
    class SessionLost : Exception("the Firebase session is no longer valid")

    /** `/users/{uid}` and `/emailIndex`, once per process after sign-in (decision 57). */
    suspend fun writeProfile(uid: String, name: String?, email: String?, photoUrl: String?) {
        val normalised = email?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }
        acknowledged(remote.update(RemoteWrites.profile(uid, name, normalised, photoUrl, normalised?.let(::sha256), clock())))
    }

    /**
     * Sends what this phone has and RTDB does not, starting with adopting any list made while
     * signed out (decision 57). Returns the ops still waiting.
     */
    suspend fun flush(uid: String): Int = mutex.withLock {
        repo.adoptOwnerless(uid)
        uploadNewLists(uid)
        sendOutbox(uid)
        prefs?.push(uid)
        db.outbox().count()
    }

    /** Runs [block] with no sync in progress: sign-out clears Room under it. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Reads what RTDB has and this phone does not, for every list of [uid]. */
    suspend fun catchUp(uid: String) = mutex.withLock {
        val remoteIds = read(RemoteWrites.userLists(uid)).asNode().keys
        val localIds = db.lists().getAll().filter { it.ownerUid == uid && db.listSync().get(it.id)?.synced == true }.map { it.id }
        for (listId in (remoteIds + localIds).distinct().sorted()) {
            try {
                pullList(uid, listId, fromStart = false)
            } catch (_: RemoteDenied) {
                // Not readable: gone from RTDB, or (from Phase 5) no longer shared with us.
            }
        }
        prefs?.pull(uid)
    }

    // --- Sending --------------------------------------------------------------------------

    private suspend fun uploadNewLists(uid: String) {
        for (entity in db.lists().getAll()) {
            if (entity.ownerUid != uid || db.listSync().get(entity.id)?.synced == true) continue
            val (state, upTo) = db.withTransaction {
                repo.loadState(entity.id) to (db.outbox().maxSeqForList(entity.id) ?: 0)
            }
            val list = state.list ?: continue
            if (list.deletedAt != null || list.updatedAt == 0L) {
                // Deleted before it ever left the phone: there is nothing to tell RTDB.
                markUploaded(entity.id, upTo)
                continue
            }
            try {
                acknowledged(remote.update(RemoteWrites.wholeList(state, uid)))
                markUploaded(entity.id, upTo)
            } catch (e: RemoteDenied) {
                refused(e)
                // RTDB already holds a copy with something newer in it. Take it, and send the
                // merge next time: it is newer than RTDB in every part.
                try {
                    pullList(uid, entity.id, fromStart = true)
                } catch (_: RemoteDenied) {
                    continue // not ours to read either; it stays on the phone, unsent
                }
                db.listSync().get(entity.id)?.let { db.listSync().upsert(it.copy(synced = false)) }
            }
        }
    }

    private suspend fun sendOutbox(uid: String) {
        while (true) {
            val batch = db.outbox().oldest(BATCH)
            if (batch.isEmpty()) return
            val sendable = mutableListOf<Pair<String, Op>>()
            val orphans = mutableListOf<String>()
            val ready = HashMap<String, Boolean>()
            for (row in batch) {
                val list = db.lists().get(row.listId)
                if (list == null) {
                    orphans += row.opId // the list was purged from the phone; nothing to send it to
                    continue
                }
                val ok = ready.getOrPut(row.listId) { list.ownerUid == uid && db.listSync().get(row.listId)?.synced == true }
                if (ok) sendable += row.opId to Op.decode(row.payload)
            }
            if (orphans.isNotEmpty()) db.outbox().delete(orphans)
            if (sendable.isEmpty()) return // waiting for a list's first upload

            // Queue them all at once, in order; the SDK keeps that order on the wire.
            val sent = sendable.map { (opId, op) -> Triple(opId, op, remote.update(RemoteWrites.forOp(op, uid, known(op)))) }
            val done = mutableListOf<String>()
            val adopt = linkedSetOf<String>()
            var stalled = false
            for ((opId, op, pending) in sent) {
                try {
                    acknowledged(pending)
                    done += opId
                } catch (e: RemoteDenied) {
                    refused(e)
                    adopt += op.listId
                    done += opId
                } catch (_: RemoteFailure) {
                    stalled = true
                    break
                }
            }
            db.outbox().delete(done)
            for (listId in adopt) {
                try {
                    pullList(uid, listId, fromStart = true)
                } catch (_: RemoteDenied) {
                    // The list is not readable any more; its refused ops are dropped with it.
                }
            }
            if (stalled) throw RemoteFailure("not acknowledged in time")
            // Some ops wait for their list's first upload, or this was the last batch.
            if (orphans.size + sendable.size < batch.size || batch.size < BATCH) return
        }
    }

    /** What a write must repeat as it is: the list's owner and creation, the item's creation. */
    private suspend fun known(op: Op): ListState {
        val list = db.lists().get(op.listId)?.toDomain()
        val item = (op as? Op.ItemPut)?.let { db.items().get(it.itemId)?.toDomain() }
        return ListState(list = list, items = item?.let { mapOf(it.id to it) }.orEmpty())
    }

    private suspend fun markUploaded(listId: String, upToSeq: Long) = db.withTransaction {
        db.outbox().deleteForListUpTo(listId, upToSeq)
        repo.markSynced(listId, dirty = db.outbox().countForList(listId) > 0)
    }

    private suspend fun refused(e: RemoteDenied) {
        if (!sessionValid()) throw SessionLost().apply { initCause(e) }
    }

    // --- Reading --------------------------------------------------------------------------

    /**
     * One list from RTDB into Room. A list deleted more than 30 days ago is removed for good,
     * from RTDB by its owner and from the phone (decision 56).
     */
    private suspend fun pullList(uid: String, listId: String, fromStart: Boolean) {
        val metaNode = read(RemoteWrites.meta(listId)) ?: return
        val list = NodeCodec.listFromNode(listId, metaNode.asNode(), shared = false)
        val deletedAt = list.deletedAt
        if (deletedAt != null && deletedAt <= clock() - Merge.TOMBSTONE_KEEP_MS && list.ownerUid == uid) {
            acknowledged(remote.update(RemoteWrites.removeList(listId, uid)))
            repo.forget(listId)
            return
        }
        val categories = read(RemoteWrites.categories(listId)).asNode()
            .mapValues { (id, node) -> NodeCodec.categoryFromNode(listId, id, node.asNode()) }
        val since = if (fromStart) 0 else db.listSync().get(listId)?.seenUpTo ?: 0
        val itemNodes = io { remote.readChangedSince(RemoteWrites.items(listId), since) }
        val items = itemNodes.mapValues { (id, node) -> NodeCodec.itemFromNode(listId, id, node.asNode()) }
        val serverTime = itemNodes.values.mapNotNull { NodeCodec.changedAt(it.asNode()) }.maxOrNull()
        repo.applyRemote(listId, ListState(list, categories, items), serverTime)
    }

    private suspend fun read(path: String): Any? = io { remote.read(path) }

    private suspend fun acknowledged(pending: Deferred<Unit>) = io { pending.await() }

    /** Every network wait has an end: offline, the SDK would wait for ever. */
    private suspend fun <T> io(block: suspend () -> T): T {
        val answer = withTimeoutOrNull(timeoutMs) { Answer(block()) } ?: throw RemoteFailure("no answer in $timeoutMs ms")
        return answer.value
    }

    /** Tells a null answer from no answer at all. */
    private class Answer<T>(val value: T)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val BATCH = 100

        /** `/emailIndex` keys: the sha256 of the lower-case email, in hex (PLAN.md *Storage layout*). */
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
