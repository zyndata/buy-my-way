package dev.gorny.buymyway.data.sync

import dev.gorny.buymyway.core.sync.NodeCodec
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.core.text.TextLimits
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.local.NameHistoryEntity
import dev.gorny.buymyway.data.local.OwnProductEntity
import dev.gorny.buymyway.data.prefs.ListSortPreferences
import dev.gorny.buymyway.data.prefs.StampedPreference
import dev.gorny.buymyway.data.prefs.SyncMarks
import dev.gorny.buymyway.data.prefs.SyncMarks.Mark
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.RemoteLists
import dev.gorny.buymyway.data.remote.asNode
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `/users/{uid}/prefs` ↔ this phone (PLAN.md Phase 4, task 6; STATE.md decision 59): the
 * default category order and the home screen's list order, each last-writer-wins by its
 * `updatedAt`, the category memory (`name_history`), last-writer-wins per name by `at`, and
 * from Phase 5 the sort view of each list (decision 67), last-writer-wins per list, and from
 * Phase 8b „Moje produkty", last-writer-wins per name by `at`, a deleted one as a tombstone
 * (decision 88). Called by [SyncEngine] under its lock.
 */
class PrefsSync(
    private val db: AppDatabase,
    private val remote: RemoteLists,
    private val defaultOrder: StampedPreference,
    private val listOrder: StampedPreference,
    private val marks: SyncMarks,
    private val listSort: ListSortPreferences? = null,
    private val timeoutMs: Long = SyncEngine.DEFAULT_TIMEOUT_MS,
) {
    private val stamped = listOf(
        Triple("defaultOrder", defaultOrder, Mark.DEFAULT_ORDER_SENT),
        Triple("listOrder", listOrder, Mark.LIST_ORDER_SENT),
    )

    suspend fun push(uid: String) {
        for ((node, pref, mark) in stamped) {
            val local = pref.stamped() ?: continue
            if (local.updatedAt <= marks.get(mark)) continue
            val path = "${RemoteWrites.prefs(uid)}/$node"
            try {
                ack(mapOf(path to NodeCodec.stampedToNode(local)))
            } catch (_: RemoteDenied) {
                pullStamped(path, pref, mark) // another phone set it later
            }
            marks.advance(mark, local.updatedAt)
        }
        pushListSort(uid)
        pushOwnProducts(uid)
        while (true) {
            val rows = db.nameHistory().usedAfter(marks.get(Mark.MEMORY_SENT), MEMORY_BATCH)
            if (rows.isEmpty()) return
            val paths = rows.associate { memoryPath(uid, it.key) to NodeCodec.memoryToNode(it.toMemory()) }
            try {
                ack(paths)
            } catch (_: RemoteDenied) {
                // One of them was used later on another phone: send the rest one by one.
                for ((path, node) in paths) {
                    try {
                        ack(mapOf(path to node))
                    } catch (_: RemoteDenied) {
                        // RTDB's is newer; the next pull brings it.
                    }
                }
            }
            marks.advance(Mark.MEMORY_SENT, rows.last().lastUsedAt)
            if (rows.size < MEMORY_BATCH) return
        }
    }

    /** „Moje produkty" (Phase 8b), last-writer-wins per name by `at`, tombstones included. */
    private suspend fun pushOwnProducts(uid: String) {
        while (true) {
            val rows = db.ownProducts().setAfter(marks.get(Mark.PRODUCTS_SENT), MEMORY_BATCH)
            if (rows.isEmpty()) return
            val paths = rows.associate { productPath(uid, it.key) to NodeCodec.ownProductToNode(it.toProduct()) }
            try {
                ack(paths)
            } catch (_: RemoteDenied) {
                // One of them was set later on another phone: send the rest one by one.
                for ((path, node) in paths) {
                    try {
                        ack(mapOf(path to node))
                    } catch (_: RemoteDenied) {
                        // RTDB's is newer; the next pull brings it.
                    }
                }
            }
            marks.advance(Mark.PRODUCTS_SENT, rows.last().at)
            if (rows.size < MEMORY_BATCH) return
        }
    }

    private suspend fun pullOwnProducts(uid: String) {
        val since = marks.get(Mark.PRODUCTS_SEEN)
        val nodes = io { remote.readChangedSince(RemoteWrites.ownProducts(uid), since) }
        var seen = since
        for ((key, value) in nodes) {
            val node = value.asNode()
            NodeCodec.changedAt(node)?.let { seen = maxOf(seen, it) }
            val product = NodeCodec.ownProductFromNode(key, node) ?: continue
            val local = db.ownProducts().get(key)
            if (local != null && local.at >= product.at) continue
            db.ownProducts().upsert(product.toEntity())
        }
        marks.advance(Mark.PRODUCTS_SEEN, seen)
    }

    suspend fun pull(uid: String) {
        for ((node, pref, mark) in stamped) pullStamped("${RemoteWrites.prefs(uid)}/$node", pref, mark)
        if (listSort != null) {
            for ((listId, node) in io { remote.read(RemoteWrites.listSort(uid)) }.asNode()) {
                val remoteValue = NodeCodec.stampedFromNode(node.asNode()) ?: continue
                if (listSort.applyRemote(listId, remoteValue)) marks.advance(Mark.LIST_SORT_SENT, remoteValue.updatedAt)
            }
        }

        val since = marks.get(Mark.MEMORY_SEEN)
        val nodes = io { remote.readChangedSince(RemoteWrites.categoryMemory(uid), since) }
        var seen = since
        for ((key, value) in nodes) {
            val node = value.asNode()
            NodeCodec.changedAt(node)?.let { seen = maxOf(seen, it) }
            val memory = NodeCodec.memoryFromNode(key, node) ?: continue
            val local = db.nameHistory().get(key)
            if (local != null && local.lastUsedAt >= memory.at) continue
            db.nameHistory().upsert(
                NameHistoryEntity(key, memory.name, memory.categoryId, memory.at, maxOf(local?.useCount ?: 0, 1)),
            )
        }
        marks.advance(Mark.MEMORY_SEEN, seen)
        pullOwnProducts(uid)
    }

    private suspend fun pushListSort(uid: String) {
        val prefs = listSort ?: return
        val sent = marks.get(Mark.LIST_SORT_SENT)
        val changed = prefs.all().filterValues { it.updatedAt > sent }
        if (changed.isEmpty()) return
        val paths = changed.entries.associate { (listId, value) -> "${RemoteWrites.listSort(uid)}/$listId" to NodeCodec.stampedToNode(value) }
        try {
            ack(paths)
        } catch (_: RemoteDenied) {
            // One was set later on another phone (the next pull brings it): send the rest alone.
            for ((path, node) in paths) {
                try {
                    ack(mapOf(path to node))
                } catch (_: RemoteDenied) {
                    // RTDB's is newer.
                }
            }
        }
        marks.advance(Mark.LIST_SORT_SENT, changed.values.maxOf { it.updatedAt })
    }

    private suspend fun pullStamped(path: String, pref: StampedPreference, mark: Mark) {
        val remoteValue = io { remote.read(path) }?.let { NodeCodec.stampedFromNode(it.asNode()) } ?: return
        // What arrived from RTDB is not sent back to it.
        if (pref.applyRemote(remoteValue)) marks.advance(mark, remoteValue.updatedAt)
    }

    private fun memoryPath(uid: String, key: String) = "${RemoteWrites.categoryMemory(uid)}/$key"

    private fun productPath(uid: String, key: String) = "${RemoteWrites.ownProducts(uid)}/$key"

    private fun OwnProductEntity.toProduct() =
        NodeCodec.OwnProduct(key, name.take(TextLimits.ITEM_NAME), categoryId, at, deleted = deletedAt != null)

    private fun NodeCodec.OwnProduct.toEntity() =
        OwnProductEntity(key, name, categoryId, at, deletedAt = at.takeIf { deleted })

    private fun NameHistoryEntity.toMemory() =
        NodeCodec.Memory(key, name.take(TextLimits.ITEM_NAME), categoryId, lastUsedAt)

    private suspend fun ack(paths: Map<String, Any?>) = io { remote.update(paths).await() }

    private suspend fun <T> io(block: suspend () -> T): T {
        val answer = withTimeoutOrNull(timeoutMs) { listOf(block()) } ?: throw RemoteFailure("no answer in $timeoutMs ms")
        return answer.single()
    }

    private companion object {
        const val MEMORY_BATCH = 200
    }
}
