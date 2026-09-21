package dev.gorny.buymyway.core.model

/**
 * Manual orders: the lists on the home screen (per device, STATE.md decision 44) and the items
 * within a category (`sortKey`).
 */
object Ordering {
    /**
     * [lists] in the user's [order]: the ones it names first, in its order, then the rest as
     * they come (the DAO gives them oldest first). Ids of lists that are gone are ignored.
     */
    fun <T> byIds(lists: List<T>, order: List<String>, id: (T) -> String): List<T> {
        val position = order.withIndex().associate { (i, listId) -> listId to i }
        val (named, rest) = lists.partition { id(it) in position }
        return named.sortedBy { position.getValue(id(it)) } + rest
    }

    /** [items] with the one at [from] moved to [to]. */
    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in items.indices || to !in items.indices) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The sort key for an item placed between [before] and [after] (either may be null at an
     * end of the category). Only the moved item gets a new key, so a reorder is one op.
     */
    fun sortKeyBetween(before: Double?, after: Double?): Double = when {
        before == null && after == null -> 1.0
        before == null -> after!! - 1.0
        after == null -> before + 1.0
        else -> (before + after) / 2
    }

    /** The key for the item now at [index] of [keys], the category's keys in their new order. */
    fun sortKeyAt(keys: List<Double>, index: Int): Double =
        sortKeyBetween(keys.getOrNull(index - 1), keys.getOrNull(index + 1))
}
