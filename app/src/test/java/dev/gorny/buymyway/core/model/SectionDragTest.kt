package dev.gorny.buymyway.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionDragTest {

    private fun item(id: String, category: String, sortKey: Double, checked: Boolean = false) = Item(
        id = id, listId = "l", name = id, quantity = null, unit = null, categoryId = category, note = null,
        photoAt = null, sortKey = sortKey, manualKey = null, checked = checked, checkedAt = null, checkedBy = null,
        createdAt = 0, createdBy = null, updatedAt = 0, updatedBy = null, deletedAt = null,
    )

    private fun section(category: String?, vararg items: Item) =
        CategorySection(category?.let { CategoryInfo(it, it, true) }, items.toList())

    // Rows on screen: h:nabial, mleko(1), ser(2), h:warzywa, marchew(1), cebula(2), h:pieczywo, chleb(1)
    private val sections = listOf(
        section("nabial", item("mleko", "nabial", 1.0), item("ser", "nabial", 2.0)),
        section("warzywa", item("marchew", "warzywa", 1.0), item("cebula", "warzywa", 2.0)),
        section("pieczywo", item("chleb", "pieczywo", 1.0)),
    )

    private val bySortKey: (Item) -> Double = { it.sortKey }

    @Test
    fun aDragWithinADepartmentKeepsTheCategory() {
        val landing = SectionDrag.land(sections, "mleko", "ser", bySortKey)!!
        assertEquals("nabial", landing.categoryId)
        assertTrue(landing.key > 2.0)
    }

    @Test
    fun draggingDownOntoTheNextHeadingPutsTheItemFirstInThatDepartment() {
        val landing = SectionDrag.land(sections, "ser", SectionDrag.headerKey("warzywa"), bySortKey)!!
        assertEquals("warzywa", landing.categoryId)
        assertTrue(landing.key < 1.0)
    }

    @Test
    fun draggingUpOntoItsOwnHeadingPutsTheItemLastInTheDepartmentAbove() {
        val landing = SectionDrag.land(sections, "marchew", SectionDrag.headerKey("warzywa"), bySortKey)!!
        assertEquals("nabial", landing.categoryId)
        assertTrue(landing.key > 2.0)
    }

    @Test
    fun droppingOnAnItemOfAnotherDepartmentTakesItsPlaceThere() {
        // Moving down onto „marchew" puts „ser" right after it.
        val down = SectionDrag.land(sections, "ser", "marchew", bySortKey)!!
        assertEquals("warzywa", down.categoryId)
        assertTrue(down.key > 1.0 && down.key < 2.0)
        // Moving up onto „cebula" from below puts „chleb" right before it.
        val up = SectionDrag.land(sections, "chleb", "cebula", bySortKey)!!
        assertEquals("warzywa", up.categoryId)
        assertTrue(up.key > 1.0 && up.key < 2.0)
    }

    @Test
    fun nothingGoesAboveTheFirstHeadingOrOntoABoughtItem() {
        assertFalse(SectionDrag.canMove(sections, "mleko", SectionDrag.headerKey("nabial")))
        assertNull(SectionDrag.land(sections, "ser", SectionDrag.headerKey("nabial"), bySortKey))
        val lingering = listOf(section("nabial", item("mleko", "nabial", 1.0), item("ser", "nabial", 2.0, checked = true)))
        assertFalse(SectionDrag.canMove(lingering, "mleko", "ser"))
        assertFalse(SectionDrag.canMove(sections, "mleko", "bought"))
        assertFalse(SectionDrag.canMove(sections, SectionDrag.headerKey("warzywa"), "mleko"))
    }

    @Test
    fun talkBackStepsOneRowSoPastAHeading() {
        assertEquals(SectionDrag.headerKey("warzywa"), SectionDrag.neighbour(sections, "ser", 1))
        assertEquals(SectionDrag.headerKey("warzywa"), SectionDrag.neighbour(sections, "marchew", -1))
        assertNull(SectionDrag.neighbour(sections, "mleko", -1))
        assertNull(SectionDrag.neighbour(sections, "chleb", 1))
    }

    @Test
    fun aFlatViewHasNoCategoryToLandIn() {
        val flat = listOf(section(null, item("a", "nabial", 1.0), item("b", "warzywa", 2.0)))
        val landing = SectionDrag.land(flat, "a", "b", bySortKey)!!
        assertNull(landing.categoryId)
        assertTrue(landing.key > 2.0)
    }

    @Test
    fun regroupShowsTheItemInItsNewSectionAndKeepsTheEmptiedOne() {
        val regrouped = SectionDrag.regroup(sections, mapOf("chleb" to "nabial", "mleko" to "nabial"))
        assertEquals(listOf("mleko", "ser", "chleb"), regrouped[0].items.map { it.id })
        assertEquals(listOf("marchew", "cebula"), regrouped[1].items.map { it.id })
        assertEquals("pieczywo", regrouped[2].category?.id)
        assertTrue(regrouped[2].items.isEmpty())
        // A category not shown any more leaves the item where it was.
        assertEquals(sections, SectionDrag.regroup(sections, mapOf("chleb" to "gone")))
    }
}
