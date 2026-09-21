package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Category
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.model.ShoppingList

/** Everything one list is made of, as the merge sees it. */
data class ListState(
    val list: ShoppingList? = null,
    val categories: Map<String, Category> = emptyMap(),
    val items: Map<String, Item> = emptyMap(),
)

/**
 * The merge (PLAN.md *Architecture*, STATE.md decision 39), pure.
 *
 * Every node is a set of independent field groups, and merging two versions of a node keeps,
 * for each group, the version with the later stamp:
 * - an item's **content** (name, quantity, unit, category, note, photo, order) by `updatedAt`,
 * - its **tick** (`checked`) by `checkedAt`, separately, so a tick racing a note edit keeps both,
 * - its **creation** by the earliest `createdAt`,
 * - its **tombstone**: once `deletedAt` is set it stays set, whatever arrives later.
 *
 * Equal stamps are broken by the actor and then by the value itself, so the order is total and
 * two devices always pick the same winner. That makes [merge] a join: commutative, associative
 * and idempotent. An op is turned into the partial node it would write ([nodesOf]), and [apply]
 * merges that node in, so ops too apply in any order, any number of times, to the same state.
 */
object Merge {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** A bought item is deleted this long after it was ticked (decision 36). */
    const val CHECKED_EXPIRY_MS = 90 * DAY_MS

    /** A tombstone is kept this long so an offline device learns of the deletion. */
    const val TOMBSTONE_KEEP_MS = 30 * DAY_MS

    fun apply(op: Op, state: ListState): ListState = merge(state, nodesOf(op))

    fun apply(ops: Iterable<Op>, state: ListState): ListState = ops.fold(state) { s, op -> apply(op, s) }

    /** Merges a whole remote state (or any partial one) into a local one. */
    fun merge(local: ListState, remote: ListState): ListState = ListState(
        list = when {
            local.list == null -> remote.list
            remote.list == null -> local.list
            else -> mergeList(local.list, remote.list)
        },
        categories = mergeMaps(local.categories, remote.categories, ::mergeCategory),
        items = mergeMaps(local.items, remote.items, ::mergeItem),
    )

    fun mergeRemote(local: Item?, remote: Item): Item = if (local == null) remote else mergeItem(local, remote)

    fun mergeRemote(local: Category?, remote: Category): Category =
        if (local == null) remote else mergeCategory(local, remote)

    fun mergeRemote(local: ShoppingList?, remote: ShoppingList): ShoppingList =
        if (local == null) remote else mergeList(local, remote)

    fun mergeItem(a: Item, b: Item): Item {
        require(a.id == b.id) { "merging two different items" }
        val content = maxOf(a, b, itemContentOrder)
        val tick = maxOf(a, b, itemCheckOrder)
        val created = minOf(a, b, creationOrder { it.createdAt to it.createdBy })
        return content.copy(
            checked = tick.checked,
            checkedAt = tick.checkedAt,
            checkedBy = tick.checkedBy,
            createdAt = created.createdAt,
            createdBy = created.createdBy,
            deletedAt = maxOfNullable(a.deletedAt, b.deletedAt),
        )
    }

    fun mergeCategory(a: Category, b: Category): Category {
        require(a.id == b.id && a.listId == b.listId) { "merging two different categories" }
        val content = maxOf(a, b, categoryContentOrder)
        val tombstone = maxOf(a, b, categoryTombstoneOrder)
        return content.copy(deletedAt = tombstone.deletedAt, moveItemsTo = tombstone.moveItemsTo)
    }

    fun mergeList(a: ShoppingList, b: ShoppingList): ShoppingList {
        require(a.id == b.id) { "merging two different lists" }
        val content = maxOf(a, b, listContentOrder)
        val created = minOf(a, b, creationOrder { it.createdAt to it.ownerUid })
        return content.copy(
            ownerUid = listOfNotNull(a.ownerUid, b.ownerUid).minOrNull(),
            // Phase 5 derives this from the members node; until then it only ever turns on.
            shared = a.shared || b.shared,
            createdAt = created.createdAt,
            clearedAt = maxOfNullable(a.clearedAt, b.clearedAt),
            deletedAt = maxOfNullable(a.deletedAt, b.deletedAt),
        )
    }

    /** The partial node an op writes, as a one-node state. */
    fun nodesOf(op: Op): ListState = when (op) {
        is Op.ItemPut -> itemNode(
            blankItem(op.itemId, op.listId).withContent(op.content).copy(
                createdAt = op.at,
                createdBy = op.actor,
                updatedAt = op.at,
                updatedBy = op.actor,
            ),
        )
        is Op.ItemCheck -> itemNode(
            blankItem(op.itemId, op.listId).copy(checked = op.checked, checkedAt = op.at, checkedBy = op.actor),
        )
        is Op.ItemDelete -> itemNode(blankItem(op.itemId, op.listId).copy(deletedAt = op.at))
        is Op.ListPut -> ListState(
            list = blankList(op.listId).copy(
                name = op.name,
                ownerUid = op.ownerUid,
                categoryOrder = op.categoryOrder,
                createdAt = op.at,
                updatedAt = op.at,
                updatedBy = op.actor,
            ),
        )
        is Op.ListDelete -> ListState(list = blankList(op.listId).copy(deletedAt = op.at))
        is Op.ClearChecked -> ListState(list = blankList(op.listId).copy(clearedAt = op.at))
        is Op.CategoryPut -> categoryNode(
            Category(op.categoryId, op.listId, op.name, op.builtin, op.at, op.actor, null, null),
        )
        is Op.CategoryDelete -> categoryNode(
            Category(op.categoryId, op.listId, "", false, 0, null, op.at, op.moveItemsTo),
        )
    }

    // --- What the screens see -------------------------------------------------------------

    /** Deleted, or ticked before „Wyczyść kupione" (decision 39). */
    fun isGone(item: Item, list: ShoppingList?): Boolean {
        if (item.deletedAt != null) return true
        val cleared = list?.clearedAt ?: return false
        val checkedAt = item.checkedAt ?: return false
        return item.checked && checkedAt <= cleared
    }

    /** Known (its content has arrived) and not gone. */
    fun isVisible(item: Item, list: ShoppingList?): Boolean = item.updatedAt > 0 && !isGone(item, list)

    fun isVisible(category: Category): Boolean = category.updatedAt > 0 && category.deletedAt == null

    /**
     * The category an item is shown under: its own while it lives; for a deleted one, where its
     * items were moved (followed through further deletions); „Inne" for anything unknown.
     */
    fun resolveCategory(categoryId: String, categories: Map<String, Category>): String {
        var current = categoryId
        repeat(categories.size + 1) {
            if (current == BuiltinCategories.FALLBACK) return current
            val category = categories[current]
                ?: return if (BuiltinCategories.isBuiltin(current)) current else BuiltinCategories.FALLBACK
            if (category.deletedAt == null) {
                return if (category.updatedAt > 0) current else BuiltinCategories.FALLBACK
            }
            current = category.moveItemsTo ?: BuiltinCategories.FALLBACK
        }
        return BuiltinCategories.FALLBACK // a cycle of deletions
    }

    // --- Lifetime (decision 36) -----------------------------------------------------------

    /** Visible items ticked 90 days ago or earlier: each gets an ordinary `item.delete`. */
    fun expiredItems(state: ListState, now: Long): List<String> = state.items.values
        .filter { isVisible(it, state.list) && it.checked && (it.checkedAt ?: now) <= now - CHECKED_EXPIRY_MS }
        .map { it.id }
        .sorted()

    /** What may be removed for good: gone for longer than [TOMBSTONE_KEEP_MS]. */
    data class Purge(val wholeList: Boolean, val itemIds: List<String>, val categoryIds: List<String>)

    fun purgeable(state: ListState, now: Long): Purge {
        val limit = now - TOMBSTONE_KEEP_MS
        val list = state.list
        if (list?.deletedAt != null && list.deletedAt <= limit) {
            return Purge(true, state.items.keys.sorted(), state.categories.keys.sorted())
        }
        val itemIds = state.items.values.filter { item ->
            val deletedAt = item.deletedAt
            when {
                deletedAt != null -> deletedAt <= limit
                isGone(item, list) -> (list?.clearedAt ?: Long.MAX_VALUE) <= limit
                else -> false
            }
        }.map { it.id }.sorted()
        val categoryIds = state.categories.values
            .filter { (it.deletedAt ?: Long.MAX_VALUE) <= limit }
            .map { it.id }
            .sorted()
        return Purge(false, itemIds, categoryIds)
    }

    // --- Internals ------------------------------------------------------------------------

    fun blankItem(id: String, listId: String) = Item(
        id = id, listId = listId,
        name = "", quantity = null, unit = null, categoryId = BuiltinCategories.FALLBACK,
        note = null, photoAt = null, sortKey = 0.0,
        checked = false, checkedAt = null, checkedBy = null,
        createdAt = 0, createdBy = null, updatedAt = 0, updatedBy = null, deletedAt = null,
    )

    fun blankList(id: String) = ShoppingList(
        id = id, name = "", ownerUid = null, shared = false, categoryOrder = emptyList(),
        createdAt = 0, updatedAt = 0, updatedBy = null, clearedAt = null, deletedAt = null,
    )

    private fun itemNode(item: Item) = ListState(items = mapOf(item.id to item))

    private fun categoryNode(category: Category) = ListState(categories = mapOf(category.id to category))

    private fun <V> mergeMaps(a: Map<String, V>, b: Map<String, V>, join: (V, V) -> V): Map<String, V> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val out = a.toMutableMap()
        for ((key, value) in b) out[key] = out[key]?.let { join(it, value) } ?: value
        return out
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun contentKey(c: ItemContent): String = c.toString()

    private val itemContentOrder: Comparator<Item> =
        compareBy<Item>({ it.updatedAt }, { it.updatedBy.orEmpty() }, { contentKey(it.content) })

    private val itemCheckOrder: Comparator<Item> =
        compareBy<Item>({ it.checkedAt ?: -1L }, { it.checkedBy.orEmpty() }, { it.checked })

    private val categoryContentOrder: Comparator<Category> =
        compareBy<Category>({ it.updatedAt }, { it.updatedBy.orEmpty() }, { it.name }, { it.builtin })

    private val categoryTombstoneOrder: Comparator<Category> =
        compareBy<Category>({ it.deletedAt ?: -1L }, { it.moveItemsTo.orEmpty() })

    private val listContentOrder: Comparator<ShoppingList> = compareBy<ShoppingList>(
        { it.updatedAt },
        { it.updatedBy.orEmpty() },
        { it.name },
        { it.categoryOrder.joinToString(",") },
    )

    /** Earliest known creation first; 0 means unknown and sorts last. */
    private fun <T> creationOrder(key: (T) -> Pair<Long, String?>): Comparator<T> = compareBy<T>(
        { key(it).first.let { at -> if (at > 0) at else Long.MAX_VALUE } },
        { key(it).second.orEmpty() },
    )
}
