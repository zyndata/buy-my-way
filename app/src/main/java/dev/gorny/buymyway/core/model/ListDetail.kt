package dev.gorny.buymyway.core.model

import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.text.TextKey

/** A category as a screen shows it: its id and the name to print. */
data class CategoryInfo(val id: String, val name: String, val builtin: Boolean)

data class CategorySection(val category: CategoryInfo, val items: List<Item>)

/**
 * What the list screen shows (PLAN.md *Screens*): the items still to buy under their category
 * headings in the list's walk order, empty headings left out, and „Kupione" below, newest tick
 * first. [categories] is every live category in walk order, for pickers.
 */
data class ListDetail(
    val list: ShoppingList,
    val categories: List<CategoryInfo>,
    val sections: List<CategorySection>,
    val bought: List<Item>,
) {
    val total: Int get() = sections.sumOf { it.items.size } + bought.size
}

/** Short summary for the home screen's card: „3 / 12". */
data class ListSummary(val list: ShoppingList, val checked: Int, val total: Int)

object ListViews {
    fun detail(list: ShoppingList, categories: Collection<Category>, items: Collection<Item>): ListDetail {
        val byId = categories.associateBy { it.id }
        val live = categories.filter { Merge.isVisible(it) }.associateBy { it.id }
        val order = BuiltinCategories.completeOrder(list.categoryOrder, live.keys.filterNot(BuiltinCategories::isBuiltin))
        val infos = order.mapNotNull { id ->
            val category = live[id]
            when {
                category != null -> CategoryInfo(id, category.name, category.builtin)
                BuiltinCategories.isBuiltin(id) && byId[id]?.deletedAt == null ->
                    CategoryInfo(id, BuiltinCategories.ALL.first { it.first == id }.second, true)
                else -> null
            }
        }
        val visible = items.filter { Merge.isVisible(it, list) }
        val toBuy = visible.filterNot { it.checked }.groupBy { Merge.resolveCategory(it.categoryId, byId) }
        val sections = infos.mapNotNull { info ->
            toBuy[info.id]
                ?.sortedWith(compareBy<Item>({ it.sortKey }, { it.createdAt }, { it.id }))
                ?.let { CategorySection(info, it) }
        }
        val bought = visible.filter { it.checked }
            .sortedWith(compareByDescending<Item> { it.checkedAt ?: 0 }.thenBy { it.id })
        return ListDetail(list, infos, sections, bought)
    }

    /**
     * The item in „Kupione" that adding [name] should bring back instead of creating a new one
     * (decision 36), or null.
     */
    fun revivable(list: ShoppingList?, items: Collection<Item>, name: String): Item? {
        val key = TextKey.fold(name)
        if (key.isEmpty()) return null
        return items
            .filter { Merge.isVisible(it, list) && it.checked && TextKey.fold(it.name) == key }
            .maxByOrNull { it.checkedAt ?: 0 }
    }

    /** The sort key that puts a new item last in its category. */
    fun nextSortKey(list: ShoppingList?, items: Collection<Item>, categoryId: String): Double =
        (items.filter { Merge.isVisible(it, list) && it.categoryId == categoryId }.maxOfOrNull { it.sortKey } ?: 0.0) + 1.0
}
