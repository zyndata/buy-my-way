package dev.gorny.buymyway.data.photo

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import dev.gorny.buymyway.core.sync.NodeCodec
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.RemoteLists
import dev.gorny.buymyway.data.remote.asNode
import dev.gorny.buymyway.data.sync.SyncEngine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/** What the list screen needs of photos; the tests put a fake behind it. */
interface ItemPhotos {
    /** Photos set here and not sent yet: item id → the photo's `at`. What rows show first. */
    val pending: Flow<Map<String, Long>>

    suspend fun set(listId: String, itemId: String, open: () -> InputStream)

    suspend fun remove(listId: String, itemId: String)

    suspend fun load(ref: PhotoRef, maxPx: Int): ImageBitmap?

    /** The photo's own bytes, to set it on another item (STATE.md decision 124); null if unreadable. */
    suspend fun bytes(ref: PhotoRef): ByteArray? = null
}

/**
 * Items' photos (PLAN.md Phase 6; STATE.md decisions 70–72): setting and removing them on the
 * phone, and sending those changes to `/photos` ([send], run by `PhotoWorker`).
 *
 * A photo is shown from the moment it is set, from the [PhotoOutbox]. The item's `photoAt`
 * changes only after `/photos` has acknowledged the photo, so another phone never looks for a
 * photo that is not there yet. A removal clears `photoAt` at once; the node goes afterwards,
 * unless someone put a newer photo there meanwhile.
 */
class Photos(
    private val repo: ListRepository,
    private val remote: RemoteLists,
    private val outbox: PhotoOutbox,
    private val cache: PhotoCache,
    val loader: PhotoLoader,
    /** Starts `PhotoWorker`: something waits to be sent. */
    private val schedule: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = SyncEngine.DEFAULT_TIMEOUT_MS,
    /** False when the Firebase session is gone: then a refusal says nothing about the photo. */
    private val sessionValid: suspend () -> Boolean = { true },
) : ItemPhotos {
    private val mutex = Mutex()

    /**
     * Held while the outbox and the item change together: a removal and the end of an upload
     * never interleave, so a removed photo cannot come back through a late `photoAt`.
     */
    private val commitLock = Mutex()

    override val pending: Flow<Map<String, Long>> = outbox.pending
        .map { all -> all.filterValues { !it.remove }.mapValues { it.value.at } }
        .distinctUntilChanged()

    /** „Zrób zdjęcie" / „Wybierz z galerii": [open] reads the chosen image, once per pass. */
    override suspend fun set(listId: String, itemId: String, open: () -> InputStream) {
        if (!repo.canEdit(listId) || !repo.isLive(itemId)) return
        val photo = withContext(Dispatchers.Default) { PhotoProcessor.process(open) }
        val stored = repo.observeItem(itemId).first()?.photoAt ?: 0
        val waiting = outbox.get(itemId)?.at ?: 0
        // `at` only moves forward, here and in the rules.
        val at = maxOf(clock(), stored + 1, waiting + 1)
        withContext(Dispatchers.IO) { outbox.put(listId, itemId, at, photo) }
        if (repo.isSynced(listId)) schedule()
    }

    /** „Usuń zdjęcie". */
    override suspend fun remove(listId: String, itemId: String) {
        if (!repo.canEdit(listId)) return
        val waiting = outbox.get(itemId)?.at ?: 0
        commitLock.withLock {
            withContext(Dispatchers.IO) { outbox.discard(itemId) }
            repo.clearPhoto(itemId)
        }
        // A list only on this phone has no node to remove. Otherwise the node may exist even
        // with no `photoAt` yet: the upload may be under way right now.
        if (repo.isSynced(listId)) {
            withContext(Dispatchers.IO) { outbox.markRemoved(listId, itemId, maxOf(clock(), waiting + 1)) }
            schedule()
        }
    }

    override suspend fun load(ref: PhotoRef, maxPx: Int): ImageBitmap? = loader.load(ref, maxPx)

    override suspend fun bytes(ref: PhotoRef): ByteArray? = loader.bytes(ref)

    /** Whether something waits that could be sent now: a change on a list RTDB has. */
    suspend fun ready(): Boolean = outbox.all().any { repo.isSynced(it.listId) }

    /**
     * Sends every waiting change whose list is in RTDB, oldest first. Changes on a list that is
     * not there yet stay; so does everything, if the network does not answer ([RemoteFailure]).
     */
    suspend fun send(uid: String) = mutex.withLock {
        val tried = mutableSetOf<PendingPhoto>()
        while (true) {
            val next = outbox.all().firstOrNull { it !in tried && repo.isSynced(it.listId) } ?: return@withLock
            tried += next
            if (!repo.isLive(next.itemId)) {
                // Deleted meanwhile: its node, if any, goes with the item after 30 days.
                outbox.complete(next)
                continue
            }
            if (!repo.canEdit(next.listId)) {
                outbox.complete(next) // a viewer now: nothing of theirs can be sent
                continue
            }
            try {
                if (next.remove) sendRemoval(next) else sendPhoto(uid, next)
            } catch (e: RemoteDenied) {
                if (!sessionValid()) throw SyncEngine.SessionLost().apply { initCause(e) }
                // Refused: someone put a newer photo, the item is gone, or the rules live in the
                // project are older than this build (docs/DEPLOYMENT.md). The photo is dropped,
                // so say so: without this line it would leave the row with no word anywhere.
                Log.w(TAG, "a photo was refused and dropped")
                outbox.complete(next)
            }
        }
    }

    /** Sign-out: nothing of the account's photos stays on the phone. */
    fun clear() {
        outbox.clear()
        cache.clear()
        loader.clear()
    }

    private suspend fun sendPhoto(uid: String, change: PendingPhoto) {
        val bytes = withContext(Dispatchers.IO) { outbox.file(change.itemId)?.readBytes() }
        if (bytes == null) {
            outbox.complete(change)
            return
        }
        val photo = NodeCodec.Photo(bytes, change.width, change.height, uid, change.at)
        acknowledged(remote.update(RemoteWrites.putPhoto(change.listId, change.itemId, photo)))
        // The phone that took the photo never downloads it back.
        withContext(Dispatchers.IO) { cache.put(change.itemId, change.at, bytes) }
        // Replaced or removed while it was sent: the newer change goes next and wins.
        commitLock.withLock {
            if (outbox.get(change.itemId) == change) {
                try {
                    repo.setPhotoAt(change.itemId, change.at)
                } catch (_: NoSuchElementException) {
                    // Deleted while it was sent; the node goes with the item after 30 days.
                } catch (_: ListRepository.ReadOnlyList) {
                    // Made a viewer while it was sent: the photo is there, unnamed.
                }
                outbox.complete(change)
            }
        }
    }

    private suspend fun sendRemoval(change: PendingPhoto) {
        val path = RemoteWrites.photo(change.listId, change.itemId)
        val node = io { remote.read("$path/at") }
        val at = (node as? Number)?.toLong()
        if (at != null && at <= change.at) acknowledged(remote.update(RemoteWrites.removePhoto(change.listId, change.itemId)))
        outbox.complete(change)
    }

    private suspend fun acknowledged(pending: Deferred<Unit>) = io { pending.await() }

    private suspend fun <T> io(block: suspend () -> T): T {
        val answer = withTimeoutOrNull(timeoutMs) { Answer(block()) } ?: throw RemoteFailure("no answer in $timeoutMs ms")
        return answer.value
    }

    private class Answer<T>(val value: T)

    companion object {
        private const val TAG = "BuyMyWayPhotos"

        /** The photo node of an item, read by a row that shows it; null if there is none. */
        fun decode(node: Any?): NodeCodec.Photo? = node?.asNode()?.let(NodeCodec::photoFromNode)
    }
}
