package dev.gorny.buymyway.data.sync

import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.remote.ChildEvent
import dev.gorny.buymyway.data.remote.LiveSource
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteLists
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * An in-memory RTDB for the sync tests (PLAN.md Phases 4 and 5). It applies multi-path
 * updates atomically, stamps `changedAt` with its own clock, answers `changedAt` queries and
 * listeners, and refuses what `firebase/database.rules.json` refuses: a stamp that goes back, a
 * changed group without a newer stamp, a write after a tombstone, anything under a deleted
 * list, a list the writer is neither the owner nor an editor of, members written by anyone
 * but the owner (or by oneself with a valid invite), reads by non-members. The real rules are
 * tested against the Firebase emulator in `firebase/test`; this mirror only has to be faithful
 * enough for the merge and the sharing flows to be tested.
 */
class FakeServer {
    private val root = mutableMapOf<String, Any?>()
    private var serverClock = 10_000_000L

    /** Bumped after every accepted write: what the listeners watch. */
    private val version = MutableStateFlow(0L)

    /** The rules' `now` for an invite's expiry: the tests' wall clock. */
    @Volatile
    var wallClock = 0L

    var refusals = 0
        private set

    /** One phone's view of the server, as the signed-in [uid]. */
    fun client(uid: String) = Client(uid)

    @Synchronized
    fun node(path: String): Any? = copy(get(root, split(path)))

    inner class Client(private val uid: String) : RemoteLists, LiveSource {
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

        // --- Listeners: re-read after every write, as the SDK reports changes ------------

        override fun value(path: String): Flow<Any?> = version.map {
            synchronized(this@FakeServer) {
                checkReadable(uid, path)
                copy(get(root, split(path)))
            }
        }.distinctUntilChanged()

        override fun children(path: String, changedSince: Long?): Flow<ChildEvent> = flow {
            var previous = emptyMap<String, Any?>()
            version.collect {
                val current = synchronized(this@FakeServer) {
                    checkReadable(uid, path)
                    map(get(root, split(path))).orEmpty()
                        .filterValues { node -> changedSince == null || ((map(node)?.get(RemoteWrites.CHANGED_AT) as? Long) ?: -1) >= changedSince }
                        .mapValues { copy(it.value) }
                }
                for ((key, node) in current) if (previous[key] != node) emit(ChildEvent(key, node))
                for (key in previous.keys - current.keys) emit(ChildEvent(key, null))
                previous = current
            }
        }

        override fun present(path: String): Flow<Unit> = flow {
            apply(uid, mapOf(path to RemoteWrites.SERVER_TIME))
            emit(Unit)
            try {
                awaitCancellation()
            } finally {
                apply(uid, mapOf(path to null))
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
        version.value++
        return true
    }

    private fun allowed(uid: String, written: Set<String>, proposed: Map<String, Any?>, now: Long): Boolean {
        for (path in written) if (!writable(uid, split(path), proposed)) return false
        val listIds = written.mapNotNull { split(it).takeIf { p -> p.size >= 3 && p[0] == "lists" && p[2] in CONTENT }?.get(1) }.toSet()
        for (listId in listIds) {
            val oldMeta = map(get(root, listOf("lists", listId, "meta")))
            val newMeta = map(get(proposed, listOf("lists", listId, "meta")))
            val owner = (oldMeta ?: newMeta)?.get("ownerUid")
            val editor = owner != uid && role(uid, listId) == "editor"
            if (owner != uid && !editor) return false
            // An editor neither deletes the list nor removes a node; the owner does.
            if (editor && (newMeta == null || newMeta["deletedAt"] != oldMeta?.get("deletedAt"))) return false
            if (editor && removesANode(listId, proposed)) return false
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
        val content = listOf("name", "quantity", "unit", "categoryId", "note", "photoAt", "sortKey", "manualKey", "updatedBy")
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

    /** Who may write each path, apart from the content checks above (decision 64). */
    private fun writable(uid: String, parts: List<String>, proposed: Map<String, Any?>): Boolean {
        val listId = parts.getOrNull(1)
        return when (parts.firstOrNull()) {
            "lists" -> when (parts.getOrNull(2)) {
                null -> proposed.let { get(it, parts) == null } && ownerOf(listId) == uid
                in CONTENT -> true
                "members" -> membersWritable(uid, listId!!, parts.getOrNull(3), proposed)
                "presence" -> {
                    val who = parts.getOrNull(3)
                    (who == uid && (ownerOf(listId) == uid || role(uid, listId!!) != null)) ||
                        (get(proposed, parts) == null && ownerOf(listId) == uid)
                }
                else -> false
            }
            "userLists" -> {
                val who = parts.getOrNull(1)
                val list = parts.getOrNull(2) ?: return false
                val value = get(proposed, parts) as? String
                val newOwner = map(get(proposed, listOf("lists", list, "meta")))?.get("ownerUid")
                val mayWrite = who == uid || ownerOf(list) == uid || newOwner == uid
                val valid = value == null ||
                    (value == "owner" && newOwner == who) ||
                    (value != "owner" && map(get(proposed, listOf("lists", list, "members", who.orEmpty())))?.get("role") == value)
                mayWrite && valid
            }
            "invites" -> {
                val token = parts.getOrNull(1) ?: return false
                val list = (map(get(root, listOf("invites", token))) ?: map(get(proposed, listOf("invites", token))))?.get("listId") as? String
                list != null && ownerOf(list) == uid
            }
            "users" -> listId == uid
            "emailIndex" -> true
            "photos" -> photoWritable(uid, parts, proposed)
            else -> false
        }
    }

    /** `/photos` (decision 72): owner or editor, a live item, a newer `at`, the writer's own name. */
    private fun photoWritable(uid: String, parts: List<String>, proposed: Map<String, Any?>): Boolean {
        val listId = parts.getOrNull(1) ?: return false
        val itemId = parts.getOrNull(2)
        val node = map(get(proposed, listOf("photos", listId) + listOfNotNull(itemId)))
        if (itemId == null) return node == null && ownerOf(listId) == uid
        if (ownerOf(listId) != uid && role(uid, listId) != "editor") return false
        if (node == null) return true
        if (map(get(root, listOf("lists", listId, "meta")))?.get("deletedAt") != null) return false
        val item = map(get(proposed, listOf("lists", listId, "items", itemId))) ?: return false
        if (item["updatedAt"] == null || item["deletedAt"] != null) return false
        if (node.keys != setOf("webp", "w", "h", "by", "at") || node["by"] != uid) return false
        if (((node["webp"] as? String)?.length ?: 0) !in 1..110_000) return false
        val before = map(get(root, listOf("photos", listId, itemId)))
        return before == null || (stampOf(node["at"]) ?: 0.0) >= (stampOf(before["at"]) ?: 0.0)
    }

    private fun membersWritable(uid: String, listId: String, member: String?, proposed: Map<String, Any?>): Boolean {
        if (ownerOf(listId) == uid) return true
        if (member != uid) return false
        val after = map(get(proposed, listOf("lists", listId, "members", uid)))
        if (after == null) return true // leaving
        if (get(root, listOf("lists", listId, "members", uid)) != null) return false // accepted once already
        val token = after["invite"] as? String ?: return false
        val invite = map(get(root, listOf("invites", token))) ?: return false
        return invite["listId"] == listId && invite["role"] == after["role"] && ((invite["expiresAt"] as? Number)?.toLong() ?: 0) > wallClock
    }

    private fun removesANode(listId: String, proposed: Map<String, Any?>): Boolean = listOf("items", "categories").any { group ->
        val before = map(get(root, listOf("lists", listId, group))).orEmpty().keys
        val after = map(get(proposed, listOf("lists", listId, group))).orEmpty().keys
        !after.containsAll(before)
    }

    private fun ownerOf(listId: String?): String? = listId?.let { map(get(root, listOf("lists", it, "meta")))?.get("ownerUid") as? String }

    private fun role(uid: String, listId: String): String? = map(get(root, listOf("lists", listId, "members", uid)))?.get("role") as? String

    private fun checkReadable(uid: String, path: String) {
        val parts = split(path)
        val readable = when (parts.firstOrNull()) {
            "lists" -> parts.size < 2 || ownerOf(parts[1]) == uid || role(uid, parts[1]) != null
            "userLists" -> parts.getOrNull(1) == uid
            "users" -> parts.getOrNull(1) == uid || parts.getOrNull(2) in setOf("name", "email", "photoUrl")
            "invites" -> parts.size >= 2
            "photos" -> parts.size >= 2 && (ownerOf(parts[1]) == uid || role(uid, parts[1]) != null)
            else -> true
        }
        if (!readable) throw RemoteDenied("not readable")
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

    private companion object {
        /** The parts of a list whose writes go through the content checks. */
        val CONTENT = setOf("meta", "items", "categories")
    }
}
