package dev.gorny.buymyway.core.model

import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListViewsTest {

    private var n = 0
    private fun id() = "op-${n++}"

    private fun state(vararg ops: Op): ListState = Merge.apply(ops.toList(), ListState())

    private val base = listOf(
        Op.ListPut(id(), "l", null, 1, "Sobota", listOf("nabial", "warzywa")),
    ) + BuiltinCategories.ALL.map { (cid, label) -> Op.CategoryPut(id(), "l", null, 1, cid, label, true) }

    private fun detail(vararg ops: Op): ListDetail {
        val s = state(*(base + ops).toTypedArray())
        return ListViews.detail(s.list!!, s.categories.values, s.items.values)
    }

    @Test
    fun itemsAreGroupedInTheListsWalkOrderAndEmptyHeadingsAreLeftOut() {
        val d = detail(
            Op.ItemPut(id(), "l", null, 2, "a", ItemContent("Ziemniaki", categoryId = "warzywa", sortKey = 2.0)),
            Op.ItemPut(id(), "l", null, 3, "b", ItemContent("Mleko", categoryId = "nabial")),
            Op.ItemPut(id(), "l", null, 4, "c", ItemContent("Cebula", categoryId = "warzywa", sortKey = 1.0)),
        )
        assertEquals(listOf("nabial", "warzywa"), d.sections.map { it.category.id })
        assertEquals(listOf("Cebula", "Ziemniaki"), d.sections[1].items.map { it.name })
        // The stored order names two; the other seven follow in the default order.
        assertEquals(listOf("nabial", "warzywa", "mieso", "pieczywo"), d.categories.take(4).map { it.id })
        assertEquals("Nabiał i jaja", d.categories.first().name)
    }

    @Test
    fun boughtItemsGoBelowNewestFirstAndClearedOnesAreGone() {
        val d = detail(
            Op.ItemPut(id(), "l", null, 2, "a", ItemContent("Masło", categoryId = "nabial")),
            Op.ItemPut(id(), "l", null, 2, "b", ItemContent("Jajka", categoryId = "nabial")),
            Op.ItemPut(id(), "l", null, 2, "c", ItemContent("Sól", categoryId = "przyprawy")),
            Op.ItemCheck(id(), "l", null, 3, "c", true),
            Op.ClearChecked(id(), "l", null, 4),
            Op.ItemCheck(id(), "l", null, 5, "a", true),
            Op.ItemCheck(id(), "l", null, 6, "b", true),
        )
        assertEquals(listOf("Jajka", "Masło"), d.bought.map { it.name })
        assertEquals(emptyList<CategorySection>(), d.sections)
        assertEquals(2, d.total)
    }

    @Test
    fun aCustomCategoryHasItsPlaceAndItsDeletionMovesItsItems() {
        val withCustom = arrayOf(
            Op.CategoryPut(id(), "l", null, 2, "apteka", "Apteka", false),
            Op.ListPut(id(), "l", null, 2, "Sobota", listOf("apteka", "nabial")),
            Op.ItemPut(id(), "l", null, 3, "a", ItemContent("Plastry", categoryId = "apteka")),
        )
        assertEquals(listOf("apteka"), detail(*withCustom).sections.map { it.category.id })

        val deleted = detail(*withCustom, Op.CategoryDelete(id(), "l", null, 4, "apteka", "nabial"))
        assertEquals(listOf("nabial"), deleted.sections.map { it.category.id })
        assertEquals("nabial", deleted.categories.first().id)
    }

    @Test
    fun onlyABoughtItemWithTheSameNameIsRevived() {
        val s = state(
            *(
                base + listOf(
                    Op.ItemPut(id(), "l", null, 2, "a", ItemContent("Śmietana 18%")),
                    Op.ItemCheck(id(), "l", null, 3, "a", true),
                    Op.ItemPut(id(), "l", null, 2, "b", ItemContent("Chleb")),
                )
                ).toTypedArray(),
        )
        assertEquals("a", ListViews.revivable(s.list, s.items.values, "  smietana 18 ")?.id)
        assertNull(ListViews.revivable(s.list, s.items.values, "chleb")) // not bought: no revive
        assertNull(ListViews.revivable(s.list, s.items.values, "Śmietana 12%"))
    }

    @Test
    fun completeOrderDropsUnknownIdsAndAppendsMissingOnes() {
        assertEquals(
            listOf("napoje", "warzywa", "nabial", "mieso", "pieczywo", "sypkie", "przyprawy", "mrozonki", "inne", "x"),
            BuiltinCategories.completeOrder(listOf("napoje", "bogus", "warzywa", "napoje"), listOf("x")),
        )
    }

    @Test
    fun aJustTickedItemStaysInItsCategoryStruckThroughUntilItLetsGo() {
        val ops = base + listOf(
            Op.ItemPut(id(), "l", null, 2, "a", ItemContent("Mleko", categoryId = "nabial", sortKey = 1.0)),
            Op.ItemPut(id(), "l", null, 3, "b", ItemContent("Ser", categoryId = "nabial", sortKey = 2.0)),
            Op.ItemCheck(id(), "l", null, 4, "a", true),
        )
        val s = state(*ops.toTypedArray())

        val lingering = ListViews.detail(s.list!!, s.categories.values, s.items.values, lingering = setOf("a"))
        assertEquals(listOf("Mleko", "Ser"), lingering.sections.single().items.map { it.name })
        assertEquals(true, lingering.sections.single().items.first().checked)
        assertEquals(emptyList<Item>(), lingering.bought)
        assertEquals(2, lingering.total)

        val settled = ListViews.detail(s.list, s.categories.values, s.items.values)
        assertEquals(listOf("Ser"), settled.sections.single().items.map { it.name })
        assertEquals(listOf("Mleko"), settled.bought.map { it.name })
    }
}
