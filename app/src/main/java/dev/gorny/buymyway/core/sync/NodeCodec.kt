package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Category
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.ShoppingList

/**
 * Domain types ↔ the RTDB node shapes of PLAN.md *Storage layout*: maps of primitives, which is
 * what the Firebase SDK writes and what `DataSnapshot.getValue()` returns.
 *
 * Writing leaves a null field out (RTDB has no nulls). Reading is lenient, because the other end
 * may be an older or a newer build: unknown keys are ignored, a missing field takes its default,
 * and numbers are accepted as any `Number` (the SDK gives `Long` for 2 and `Double` for 2.5). A
 * node without the fields that identify it is rejected with null rather than invented.
 */
object NodeCodec {

    // --- /lists/{listId}/items/{itemId} ---------------------------------------------------

    fun itemToNode(item: Item): Map<String, Any> = buildMap {
        put("name", item.name)
        item.quantity?.let { put("quantity", it) }
        item.unit?.let { put("unit", it) }
        put("categoryId", item.categoryId)
        item.note?.let { put("note", it) }
        item.photoAt?.let { put("photoAt", it) }
        put("sortKey", item.sortKey)
        put("checked", item.checked)
        item.checkedAt?.let { put("checkedAt", it) }
        item.checkedBy?.let { put("checkedBy", it) }
        put("createdAt", item.createdAt)
        item.createdBy?.let { put("createdBy", it) }
        put("updatedAt", item.updatedAt)
        item.updatedBy?.let { put("updatedBy", it) }
        item.deletedAt?.let { put("deletedAt", it) }
    }

    fun itemFromNode(listId: String, itemId: String, node: Map<String, Any?>): Item = Item(
        id = itemId,
        listId = listId,
        name = node.string("name").orEmpty(),
        quantity = node.double("quantity"),
        unit = node.string("unit"),
        categoryId = node.string("categoryId") ?: BuiltinCategories.FALLBACK,
        note = node.string("note"),
        photoAt = node.long("photoAt"),
        sortKey = node.double("sortKey") ?: 0.0,
        checked = node.boolean("checked") ?: false,
        checkedAt = node.long("checkedAt"),
        checkedBy = node.string("checkedBy"),
        createdAt = node.long("createdAt") ?: 0,
        createdBy = node.string("createdBy"),
        updatedAt = node.long("updatedAt") ?: 0,
        updatedBy = node.string("updatedBy"),
        deletedAt = node.long("deletedAt"),
    )

    // --- /lists/{listId}/meta -------------------------------------------------------------

    fun listToNode(list: ShoppingList): Map<String, Any> = buildMap {
        put("name", list.name)
        list.ownerUid?.let { put("ownerUid", it) }
        put("categoryOrder", list.categoryOrder)
        put("createdAt", list.createdAt)
        put("updatedAt", list.updatedAt)
        list.updatedBy?.let { put("updatedBy", it) }
        list.clearedAt?.let { put("clearedAt", it) }
        list.deletedAt?.let { put("deletedAt", it) }
    }

    /** [shared] is not in the meta node: it follows from the list having a members node. */
    fun listFromNode(listId: String, node: Map<String, Any?>, shared: Boolean): ShoppingList = ShoppingList(
        id = listId,
        name = node.string("name").orEmpty(),
        ownerUid = node.string("ownerUid"),
        shared = shared,
        categoryOrder = node.stringList("categoryOrder"),
        createdAt = node.long("createdAt") ?: 0,
        updatedAt = node.long("updatedAt") ?: 0,
        updatedBy = node.string("updatedBy"),
        clearedAt = node.long("clearedAt"),
        deletedAt = node.long("deletedAt"),
    )

    // --- /lists/{listId}/categories/{categoryId} ------------------------------------------

    fun categoryToNode(category: Category): Map<String, Any> = buildMap {
        put("name", category.name)
        put("builtin", category.builtin)
        put("updatedAt", category.updatedAt)
        category.updatedBy?.let { put("updatedBy", it) }
        category.deletedAt?.let { put("deletedAt", it) }
        category.moveItemsTo?.let { put("moveItemsTo", it) }
    }

    fun categoryFromNode(listId: String, categoryId: String, node: Map<String, Any?>): Category = Category(
        id = categoryId,
        listId = listId,
        name = node.string("name").orEmpty(),
        builtin = node.boolean("builtin") ?: false,
        updatedAt = node.long("updatedAt") ?: 0,
        updatedBy = node.string("updatedBy"),
        deletedAt = node.long("deletedAt"),
        moveItemsTo = node.string("moveItemsTo"),
    )

    // --- /lists/{listId}/members/{uid} + /users/{uid} -------------------------------------

    fun memberToNode(member: Member): Map<String, Any> = mapOf(
        "role" to member.role.name.lowercase(),
        "since" to member.since,
    )

    /**
     * A member from its members node and, when already read, the `/users/{uid}` profile.
     * An unknown role is read as viewer: the rules decide what a member may do, and the
     * screen should never offer more than that.
     */
    fun memberFromNode(listId: String, uid: String, node: Map<String, Any?>, profile: Map<String, Any?>? = null): Member =
        Member(
            listId = listId,
            uid = uid,
            role = Role.entries.firstOrNull { it.name.equals(node.string("role"), ignoreCase = true) } ?: Role.VIEWER,
            since = node.long("since") ?: 0,
            name = profile?.string("name"),
            email = profile?.string("email"),
            photoUrl = profile?.string("photoUrl"),
        )

    // --- Lenient readers ------------------------------------------------------------------

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean

    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toLong()
        else -> null
    }

    private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** RTDB returns an array as a `List`, or as a map keyed "0", "1"… when it has gaps. */
    private fun Map<String, Any?>.stringList(key: String): List<String> = when (val v = this[key]) {
        is List<*> -> v.filterIsInstance<String>()
        is Map<*, *> -> v.entries
            .mapNotNull { (k, value) -> (k as? String)?.toIntOrNull()?.let { it to value } }
            .sortedBy { it.first }
            .mapNotNull { it.second as? String }
        else -> emptyList()
    }
}
