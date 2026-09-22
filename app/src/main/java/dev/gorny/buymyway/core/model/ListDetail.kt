package dev.gorny.buymyway.core.model

import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.text.TextKey
import java.text.Collator
import java.util.Locale

/** A category as a screen shows it: its id and the name to print. */
data class CategoryInfo(val id: String, val name: String, val builtin: Boolean)

/** A run of items to buy under one heading; [category] is null in the flat views, which have none. */
data class CategorySection(val category: CategoryInfo?, val items: List<Item>)

/**
 * The three ways to show the items still to buy (PLAN.md *Screens*, STATE.md decision 62). The
 * [key] is what `/users/{uid}/prefs/listSort` stores.
 */
enum class SortView(val key: String) {
    /** Under their department headings, in the list's walk order (the default). */
    DEPARTMENTS("departments"),

    /** One flat list, A–Z by the Polish collation. */
    ALPHABETICAL("alphabetical"),

    /** One flat list in the order the members dragged it (`manualKey`). */
    MANUAL("manual"),
    ;

    companion object {
        fun of(key: String?): SortView = entries.firstOrNull { it.key == key } ?: DEPARTMENTS
    }
}

/**
 * What the list screen shows (PLAN.md *Screens*): the items still to buy in [view] (under their
 * category headings in the list's walk order, empty headings left out, or one flat section),
 * and „Kupione" below, newest tick first, in every view. [categories] is every live category
 * in walk order, for pickers.
 */
data class ListDetail(
    val list: ShoppingList,
    val categories: List<CategoryInfo>,
    val sections: List<CategorySection>,
    val bought: List<Item>,
    val view: SortView = SortView.DEPARTMENTS,
) {
    val total: Int get() = sections.sumOf { it.items.size } + bought.size
}

/** Short summary for the home screen's card: „3 / 12". */
data class ListSummary(val list: ShoppingList, val checked: Int, val total: Int)

object ListViews {
    /**
     * [lingering] are items just ticked on this device that stay where they were, struck
     * through, for a moment before they move to „Kupione" (PLAN.md *Screens*): they are shown
     * in their category, still `checked`.
     */
    fun detail(
        list: ShoppingList,
        categories: Collection<Category>,
        items: Collection<Item>,
        lingering: Set<String> = emptySet(),
        view: SortView = SortView.DEPARTMENTS,
    ): ListDetail {
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
        val inPlace = { item: Item -> !item.checked || item.id in lingering }
        val toBuy = visible.filter(inPlace)
        val sections = when (view) {
            SortView.DEPARTMENTS -> {
                val grouped = toBuy.groupBy { Merge.resolveCategory(it.categoryId, byId) }
                infos.mapNotNull { info -> grouped[info.id]?.sortedWith(withinCategory)?.let { CategorySection(info, it) } }
            }
            SortView.ALPHABETICAL -> flat(toBuy.sortedWith(alphabetical()))
            SortView.MANUAL -> flat(manualOrder(toBuy, departmentRank(infos, byId)))
        }
        val bought = visible.filterNot(inPlace)
            .sortedWith(compareByDescending<Item> { it.checkedAt ?: 0 }.thenBy { it.id })
        return ListDetail(list, infos, sections, bought, view)
    }

    /**
     * The „Ręcznie" order (decision 67): the placed items by `manualKey`, then the ones not
     * placed yet in department order.
     */
    fun manualOrder(items: Collection<Item>, departmentRank: (Item) -> Int): List<Item> {
        val (placed, unplaced) = items.partition { it.manualKey != null }
        return placed.sortedWith(compareBy<Item>({ it.manualKey }, { it.createdAt }, { it.id })) +
            unplaced.sortedWith(compareBy<Item>(departmentRank).then(withinCategory))
    }

    /**
     * Keys that place every unplaced item of [shown] (a „Ręcznie" section, in its order) after
     * the placed ones, one apart: what choosing „Ręcznie", or dragging in it, writes. Empty
     * when everything is placed already.
     */
    fun placements(shown: List<Item>): Map<String, Double> {
        var next = nextManualKey(shown)
        return shown.filter { it.manualKey == null }.associate { it.id to next.also { next += 1.0 } }
    }

    /** The key that puts a new item last in the „Ręcznie" view. */
    fun nextManualKey(items: Collection<Item>): Double = (items.mapNotNull { it.manualKey }.maxOrNull() ?: 0.0) + 1.0

    /** A–Z by the Polish collation („ł" after „l", „ś" after „s"), case aside. */
    fun alphabetical(): Comparator<Item> {
        val collator = Collator.getInstance(Locale.forLanguageTag("pl-PL")).apply { strength = Collator.SECONDARY }
        return Comparator<Item> { a, b -> collator.compare(a.name, b.name) }.then(compareBy({ it.createdAt }, { it.id }))
    }

    private fun flat(items: List<Item>): List<CategorySection> = if (items.isEmpty()) emptyList() else listOf(CategorySection(null, items))

    private fun departmentRank(infos: List<CategoryInfo>, byId: Map<String, Category>): (Item) -> Int {
        val position = infos.withIndex().associate { (i, info) -> info.id to i }
        return { item -> position[Merge.resolveCategory(item.categoryId, byId)] ?: Int.MAX_VALUE }
    }

    private val withinCategory = compareBy<Item>({ it.sortKey }, { it.createdAt }, { it.id })

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
