package dev.gorny.buymyway.core.model

import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three „Sortowanie" views (PLAN.md Phase 5, task 9; STATE.md decisions 62 and 67). */
class SortViewsTest {

    private var n = 0
    private fun id() = "op-${n++}"

    private val base = listOf(
        Op.ListPut(id(), "l", null, 1, "Sobota", listOf("warzywa", "nabial", "pieczywo")),
    ) + BuiltinCategories.ALL.map { (cid, label) -> Op.CategoryPut(id(), "l", null, 1, cid, label, true) }

    private fun put(at: Long, itemId: String, name: String, category: String, sortKey: Double = 0.0, manualKey: Double? = null) =
        Op.ItemPut(id(), "l", null, at, itemId, ItemContent(name, categoryId = category, sortKey = sortKey, manualKey = manualKey))

    private fun state(vararg ops: Op): ListState = Merge.apply((base + ops).toList(), ListState())

    private fun detail(view: SortView, vararg ops: Op): ListDetail {
        val s = state(*ops)
        return ListViews.detail(s.list!!, s.categories.values, s.items.values, view = view)
    }

    private val shop = arrayOf(
        put(2, "a", "Żurek", "inne"),
        put(3, "b", "lody", "mrozonki"),
        put(4, "c", "łosoś", "mieso"),
        put(5, "d", "Ziemniaki", "warzywa"),
        put(6, "e", "ananas", "warzywa", sortKey = 2.0),
        put(7, "f", "Mleko", "nabial"),
        put(8, "g", "sól", "przyprawy"),
        put(9, "h", "śmietana", "nabial", sortKey = 2.0),
        put(10, "i", "ser", "nabial", sortKey = 3.0),
    )

    @Test
    fun byDepartmentsIsTheDefaultWithHeadings() {
        val d = detail(SortView.DEPARTMENTS, *shop)
        assertEquals(SortView.DEPARTMENTS, d.view)
        assertTrue(d.sections.all { it.category != null })
        assertEquals("warzywa", d.sections.first().category?.id)
        assertEquals(listOf("Ziemniaki", "ananas"), d.sections.first().items.map { it.name })
    }

    @Test
    fun alphabeticalIsOneFlatListInPolishOrder() {
        val d = detail(SortView.ALPHABETICAL, *shop)
        assertEquals(1, d.sections.size)
        assertNull(d.sections.single().category)
        // „ł" after „l", „ś" after „s", „ż" last; case does not matter.
        assertEquals(
            listOf("ananas", "lody", "łosoś", "Mleko", "ser", "sól", "śmietana", "Ziemniaki", "Żurek"),
            d.sections.single().items.map { it.name },
        )
    }

    @Test
    fun manualShowsPlacedItemsByKeyThenTheRestInDepartmentOrder() {
        val d = detail(
            SortView.MANUAL,
            *shop,
            put(20, "f", "Mleko", "nabial", manualKey = 1.0),
            put(21, "b", "lody", "mrozonki", manualKey = 2.0),
        )
        assertNull(d.sections.single().category)
        val names = d.sections.single().items.map { it.name }
        // Placed first, then walk order: warzywa, nabial, pieczywo, then the rest by default order.
        assertEquals(listOf("Mleko", "lody", "Ziemniaki", "ananas", "śmietana", "ser", "łosoś", "sól", "Żurek"), names)
    }

    @Test
    fun placingAppendsTheUnplacedOneApartAfterThePlaced() {
        val shown = detail(SortView.MANUAL, *shop, put(20, "f", "Mleko", "nabial", manualKey = 5.0)).sections.single().items
        val keys = ListViews.placements(shown)
        assertEquals(8, keys.size)
        assertEquals(6.0, keys.getValue("d"), 0.0) // Ziemniaki, first in the walk order
        assertEquals(7.0, keys.getValue("e"), 0.0)
        assertEquals(13.0, keys.getValue("a"), 0.0) // Żurek, „Inne", last
        // Once placed, the order is the same as before placing: choosing „Ręcznie" moves nothing.
        val placed = shown.map { it.copy(manualKey = it.manualKey ?: keys.getValue(it.id)) }
        assertEquals(shown.map { it.id }, ListViews.manualOrder(placed) { 0 }.map { it.id })
        assertTrue(ListViews.placements(placed).isEmpty())
    }

    @Test
    fun aNewItemInManualGoesToTheEnd() {
        val items = state(*shop, put(20, "f", "Mleko", "nabial", manualKey = 5.0)).items.values
        assertEquals(6.0, ListViews.nextManualKey(items), 0.0)
        assertEquals(1.0, ListViews.nextManualKey(emptyList()), 0.0)
    }

    @Test
    fun boughtStaysAtTheBottomInEveryView() {
        for (view in SortView.entries) {
            val d = detail(view, *shop, Op.ItemCheck(id(), "l", null, 30, "b", true))
            assertEquals(listOf("lody"), d.bought.map { it.name })
            assertTrue(d.sections.flatMap { it.items }.none { it.name == "lody" })
        }
    }

    @Test
    fun anUnknownStoredViewIsTheDefault() {
        assertEquals(SortView.DEPARTMENTS, SortView.of(null))
        assertEquals(SortView.DEPARTMENTS, SortView.of("random"))
        assertEquals(SortView.MANUAL, SortView.of("manual"))
    }
}
