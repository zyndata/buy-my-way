package dev.gorny.buymyway.core.model

import kotlinx.serialization.Serializable

/**
 * The domain types (PLAN.md *Data model*), plain Kotlin so every rule about them is testable on
 * the JVM. Room mirrors them in `data/local`; `core/sync/NodeCodec` maps them to RTDB nodes.
 *
 * Each type is the *state of one node*: the fields the merge needs travel with it
 * (`updatedAt`/`updatedBy` for the content, `checkedAt`/`checkedBy` for the tick, `deletedAt`
 * for the tombstone). A timestamp of 0 means „not known yet": a node whose tick or deletion
 * arrived before its content (STATE.md decision 39) exists, but is never shown.
 */

/** A shopping list's own node: RTDB `/lists/{id}/meta`, Room `lists`. */
data class ShoppingList(
    val id: String, // UUID v4, generated on the device that creates the list
    val name: String,
    val ownerUid: String?, // null for a private list of a signed-out user
    val shared: Boolean, // true once it has a members node
    val categoryOrder: List<String>, // category ids, the walk order of this list
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String?,
    val clearedAt: Long?, // „Wyczyść kupione": items checked at or before this are gone
    val deletedAt: Long?,
)

/** What an `item.put` writes: everything about an item except its tick and its lifetime. */
@Serializable
data class ItemContent(
    val name: String,
    val quantity: Double? = null, // „2"
    val unit: String? = null, // „kg"
    val categoryId: String = BuiltinCategories.FALLBACK,
    val note: String? = null,
    val photoAt: Long? = null, // set when /photos/{listId}/{id} exists; null = none
    val sortKey: Double = 0.0, // manual order within a category
)

data class Item(
    val id: String,
    val listId: String,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val categoryId: String,
    val note: String?,
    val photoAt: Long?,
    val sortKey: Double,
    val checked: Boolean,
    val checkedAt: Long?,
    val checkedBy: String?,
    val createdAt: Long,
    val createdBy: String?,
    val updatedAt: Long,
    val updatedBy: String?,
    val deletedAt: Long?, // tombstone, final, kept 30 days
) {
    val content: ItemContent
        get() = ItemContent(name, quantity, unit, categoryId, note, photoAt, sortKey)

    fun withContent(c: ItemContent): Item = copy(
        name = c.name,
        quantity = c.quantity,
        unit = c.unit,
        categoryId = c.categoryId,
        note = c.note,
        photoAt = c.photoAt,
        sortKey = c.sortKey,
    )
}

data class Category(
    val id: String,
    val listId: String,
    val name: String,
    val builtin: Boolean,
    val updatedAt: Long,
    val updatedBy: String?,
    val deletedAt: Long?,
    val moveItemsTo: String?, // where a deleted category's items are shown
)

enum class Role { OWNER, EDITOR, VIEWER }

data class Member(
    val listId: String,
    val uid: String,
    val role: Role,
    val since: Long,
    val name: String?,
    val email: String?,
    val photoUrl: String?,
)

/**
 * The nine departments of Eat My Way, with its ids (STATE.md decision 9), in the order a shop
 * is walked. A list starts with all nine; „Inne" is where anything unknown goes.
 */
object BuiltinCategories {
    const val FALLBACK = "inne"

    val ALL: List<Pair<String, String>> = listOf(
        "warzywa" to "Warzywa i owoce",
        "nabial" to "Nabiał i jaja",
        "mieso" to "Mięso, ryby i wędliny",
        "pieczywo" to "Pieczywo",
        "sypkie" to "Sypkie i makarony",
        "przyprawy" to "Przyprawy i dodatki",
        "mrozonki" to "Mrożonki",
        "napoje" to "Napoje",
        "inne" to "Inne",
    )

    val IDS: List<String> = ALL.map { it.first }

    fun isBuiltin(id: String): Boolean = id in IDS

    /**
     * A stored or preferred order made whole: unknown ids dropped, duplicates removed, and any
     * built-in id it lacks appended in the default order. So an order saved by an older build,
     * or a preference edited before a category existed, still names all nine.
     */
    fun completeOrder(order: List<String>, extra: Collection<String> = emptyList()): List<String> {
        val known = IDS.toSet() + extra
        val kept = order.filter { it in known }.distinct()
        return kept + (IDS + extra).filter { it !in kept }.distinct()
    }
}
