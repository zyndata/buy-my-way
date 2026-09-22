package dev.gorny.buymyway.data.sync

import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteLists
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation

/**
 * An in-memory RTDB for the sync tests (PLAN.md Phase 4, task 7). It applies multi-path
 * updates atomically, stamps `changedAt` with its own clock, answers `changedAt` queries, and
 * refuses what `firebase/database.rules.json` refuses for a single user's lists: a stamp that
 * goes back, a changed group without a newer stamp, a write after a tombstone, anything under
 * a deleted list, another user's list. The real rules are tested against the Firebase emulator
 * in `firebase/test`; this mirror only has to be faithful enough for the merge to be tested.
 */
class FakeServer {
    private val root = mutableMapOf<String, Any?>()
    private var serverClock = 10_000_000L

    var refusals = 0
        private set

    /** One phone's view of the server, as the signed-in [uid]. */
    fun client(uid: String) = Client(uid)

    @Synchronized
    fun node(path: String): Any? = copy(get(root, split(path)))

    inner class Client(private val uid: String) : RemoteLists {
        /** Offline: writes go nowhere and nothing answers. */
        var online = true

        /** Writes are applied, but their acknowledgement never arrives (a delayed writer). */
        var dropAcks = false

        override fun update(paths: Map<String, Any?>): Deferred<Unit> {
            if (!online) return CompletableDeferred()
            val result = CompletableDeferred<Unit>()
            val accepted = apply(uid, paths)
            when {
                dropAcks -> Unit
                accepted -> result.complete(Unit)
                else -> result.completeExceptionally(RemoteDenied("refused"))
            }
            return result
        }

        override suspend fun read(path: String): Any? {
            if (!online) awaitCancellation()
            return synchronized(this@FakeServer) {
                checkReadable(uid, path)
                copy(get(root, split(path)))
            }
        }

        override suspend fun readChangedSince(path: String, since: Long): Map<String, Any?> {
            if (!online) awaitCancellation()
            return synchronized(this@FakeServer) {
                checkReadable(uid, path)
                @Suppress("UNCHECKED_CAST")
                val children = get(root, split(path)) as? Map<String, Any?> ?: emptyMap()
                children.filterValues { node -> ((node as? Map<*, *>)?.get(RemoteWrites.CHANGED_AT) as? Long ?: -1) >= since }
                    .mapValues { copy(it.value) }
            }
        }
    }

    // --- Writing --------------------------------------------------------------------------

    @Synchronized
    private fun apply(uid: String, paths: Map<String, Any?>): Boolean {
        val now = ++serverClock
        @Suppress("UNCHECKED_CAST")
        val proposed = copy(root) as MutableMap<String, Any?>
        for ((path, value) in paths) set(proposed, split(path), resolve(value, now))
        if (!allowed(uid, paths.keys, proposed, now)) {
            refusals++
            return false
        }
        root.clear()
        root.putAll(proposed)
        return true
    }

    private fun allowed(uid: String, written: Set<String>, proposed: Map<String, Any?>, now: Long): Boolean {
        val listIds = written.mapNotNull { split(it).takeIf { p -> p.size >= 2 && p[0] == "lists" }?.get(1) }.toSet()
        for (listId in listIds) {
            val oldMeta = map(get(root, listOf("lists", listId, "meta")))
            val newMeta = map(get(proposed, listOf("lists", listId, "meta")))
            val owner = (oldMeta ?: newMeta)?.get("ownerUid")
            if (owner != uid) return false
            if (!metaOk(oldMeta, newMeta)) return false
            val oldItems = map(get(root, listOf("lists", listId, "items"))).orEmpty()
            val newItems = map(get(proposed, listOf("lists", listId, "items"))).orEmpty()
            val deleted = oldMeta?.get("deletedAt") != null
            for (itemId in (oldItems.keys + newItems.keys)) {
                val before = map(oldItems[itemId])
                val after = map(newItems[itemId])
                if (before == after) continue
                if (after != null && deleted) return false
                if (!itemOk(before, after, now)) return false
            }
            val oldCats = map(get(root, listOf("lists", listId, "categories"))).orEmpty()
            val newCats = map(get(proposed, listOf("lists", listId, "categories"))).orEmpty()
            for (categoryId in (oldCats.keys + newCats.keys)) {
                val before = map(oldCats[categoryId])
                val after = map(newCats[categoryId])
                if (before == after) continue
                if (after != null && deleted) return false
                if (!groupOk(before, after, "updatedAt", listOf("name", "builtin", "updatedBy"))) return false
                if (!groupOk(before, after, "deletedAt", listOf("moveItemsTo"))) return false
            }
        }
        return true
    }

    private fun metaOk(before: Map<String, Any?>?, after: Map<String, Any?>?): Boolean {
        if (after == null || before == null) return true
        if (after["ownerUid"] != before["ownerUid"] || after["createdAt"] != before["createdAt"]) return false
        if (!groupOk(before, after, "updatedAt", listOf("name", "updatedBy"))) return false
        if (!monotonic(before, after, "clearedAt")) return false
        if (before["deletedAt"] != null && (!monotonic(before, after, "deletedAt") || after["updatedAt"] != before["updatedAt"])) return false
        return true
    }

    private fun itemOk(before: Map<String, Any?>?, after: Map<String, Any?>?, now: Long): Boolean {
        if (after == null) return true
        if (after[RemoteWrites.CHANGED_AT] != now) return false
        if (before == null) return true
        val content = listOf("name", "quantity", "unit", "categoryId", "note", "photoAt", "sortKey", "updatedBy")
        val tick = listOf("checked", "checkedBy")
        if (!groupOk(before, after, "updatedAt", content)) return false
        if (!groupOk(before, after, "checkedAt", tick)) return false
        if (before["deletedAt"] != null) {
            if (!monotonic(before, after, "deletedAt")) return false
            if ((content + tick + listOf("updatedAt", "checkedAt")).any { norm(before[it]) != norm(after[it]) }) return false
        }
        if (before["createdAt"] != null && (after["createdAt"] != before["createdAt"] || after["createdBy"] != before["createdBy"])) return false
        return true
    }

    /** A group's stamp only moves forward, and its fields change only with a newer stamp. */
    private fun groupOk(before: Map<String, Any?>?, after: Map<String, Any?>?, stamp: String, fields: List<String>): Boolean {
        if (before == null || after == null) return true
        val old = stampOf(before[stamp])
        val new = stampOf(after[stamp])
        val same = fields.all { norm(before[it]) == norm(after[it]) }
        return when {
            old == null -> new != null || same
            new == null -> false
            new > old -> true
            new == old -> same
            else -> false
        }
    }

    private fun monotonic(before: Map<String, Any?>, after: Map<String, Any?>, key: String): Boolean {
        val old = stampOf(before[key]) ?: return true
        val new = stampOf(after[key]) ?: return false
        return new >= old
    }

    private fun checkReadable(uid: String, path: String) {
        val parts = split(path)
        when {
            parts.firstOrNull() == "lists" && parts.size >= 2 -> {
                val owner = map(get(root, listOf("lists", parts[1], "meta")))?.get("ownerUid")
                if (owner != uid) throw RemoteDenied("not readable")
            }
            parts.firstOrNull() in setOf("userLists", "users") && parts.getOrNull(1) != uid -> throw RemoteDenied("not readable")
        }
    }

    // --- The tree -------------------------------------------------------------------------

    private fun split(path: String) = path.split('/').filter { it.isNotEmpty() }

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    private fun get(tree: Map<String, Any?>, parts: List<String>): Any? {
        var current: Any? = tree
        for (part in parts) current = map(current)?.get(part) ?: return null
        return current
    }

    @Suppress("UNCHECKED_CAST")
    private fun set(tree: MutableMap<String, Any?>, parts: List<String>, value: Any?) {
        var current = tree
        for (part in parts.dropLast(1)) {
            current = current.getOrPut(part) { mutableMapOf<String, Any?>() } as? MutableMap<String, Any?>
                ?: mutableMapOf<String, Any?>().also { current[part] = it }
        }
        if (value == null) current.remove(parts.last()) else current[parts.last()] = value
        prune(tree)
    }

    /** RTDB has no empty nodes. */
    @Suppress("UNCHECKED_CAST")
    private fun prune(tree: MutableMap<String, Any?>) {
        val iterator = tree.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val child = entry.value as? MutableMap<String, Any?> ?: continue
            prune(child)
            if (child.isEmpty()) iterator.remove()
        }
    }

    /** What the SDK stores: `ServerValue.TIMESTAMP` resolved, lists as they are, maps copied. */
    private fun resolve(value: Any?, now: Long): Any? = when {
        value == RemoteWrites.SERVER_TIME -> now
        value is Map<*, *> -> value.entries.associateTo(mutableMapOf<String, Any?>()) { (k, v) -> k as String to resolve(v, now) }
        value is Int -> value.toLong()
        else -> value
    }

    private fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(mutableMapOf<String, Any?>()) { (k, v) -> k as String to copy(v) }
        is List<*> -> value.toList()
        else -> value
    }

    /** RTDB keeps numbers as numbers: 2 and 2.0 are the same value. */
    private fun norm(value: Any?): Any? = if (value is Number) value.toDouble() else value

    private fun stampOf(value: Any?): Double? = (value as? Number)?.toDouble()
}
