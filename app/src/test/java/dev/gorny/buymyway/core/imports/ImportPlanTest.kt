package dev.gorny.buymyway.core.imports

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an import does to a list it is poured into (PLAN.md Phase 8, task 3, and its second
 * acceptance criterion: „importing the same text twice sums quantities and adds no duplicate").
 */
class ImportPlanTest {

    private var n = 0
    private fun id() = "op-${n++}"

    private val base = listOf(Op.ListPut(id(), "l", null, 1, "Sobota", BuiltinCategories.IDS)) +
        BuiltinCategories.ALL.map { (cid, label) -> Op.CategoryPut(id(), "l", null, 1, cid, label, true) }

    private fun state(vararg ops: Op): ListState = Merge.apply(base + ops.toList(), ListState())

    private fun item(itemId: String, name: String, quantity: Double?, unit: String?, at: Long = 2) =
        Op.ItemPut(id(), "l", null, at, itemId, ItemContent(name, quantity, unit, categoryId = "inne"))

    private fun line(name: String, quantity: Double? = null, unit: String? = null) =
        ImportedItem(name, quantity, unit)

    private fun plan(state: ListState, vararg lines: ImportedItem) =
        ImportPlan.of(lines.toList(), state.list, state.items.values)

    // --- The acceptance criterion ---------------------------------------------------------

    @Test
    fun theSameTextTwiceSumsAndAddsNoDuplicate() {
        val text = EatMyWayImport.parse(
            """
            Lista zakupów — środa

            Warzywa i owoce
            • Cebula — 2 szt. (160 g)
            • Ziemniaki — 1,5 kg

            Nabiał i jaja
            • Mleko 2% — 500 ml
            """.trimIndent(),
        ).items

        // The first import adds all three.
        val first = ImportPlan.of(text, null, emptyList())
        assertEquals(3, first.added.size)
        assertTrue(first.summed.isEmpty())

        // Now the list holds them, and the same text arrives again.
        val list = state(
            item("a", "Cebula", 2.0, "szt."),
            item("b", "Ziemniaki", 1.5, "kg"),
            item("c", "Mleko 2%", 500.0, "ml"),
        )
        val second = plan(list, *text.toTypedArray())
        assertTrue("nothing is added twice", second.added.isEmpty())
        assertEquals(
            listOf("a" to 4.0, "b" to 3.0, "c" to 1000.0),
            second.summed.map { it.itemId to it.quantity },
        )
        assertTrue("nothing was bought, so nothing is revived", second.summed.none { it.revive })
    }

    // --- Matching -------------------------------------------------------------------------

    @Test
    fun aNameTheListDoesNotHoldIsAdded() {
        val p = plan(state(item("a", "Cebula", 2.0, "szt.")), line("Chleb", 1.0, "szt."))
        assertEquals(listOf("Chleb"), p.added.map { it.name })
        assertTrue(p.summed.isEmpty())
    }

    @Test
    fun theNameIsMatchedFoldedSoTheSpellingDoesNotMatter() {
        val p = plan(state(item("a", "Ziemniaki", 1.0, "kg")), line("ziemniaki", 2.0, "kg"))
        assertEquals(listOf("a" to 3.0), p.summed.map { it.itemId to it.quantity })
    }

    @Test
    fun aDifferentUnitIsADifferentLine() {
        val p = plan(state(item("a", "Cebula", 2.0, "szt.")), line("Cebula", 200.0, "g"))
        assertEquals(listOf("Cebula"), p.added.map { it.name })
        assertTrue(p.summed.isEmpty())
    }

    @Test
    fun oneMeasureInTwoFormsIsOneLine() {
        val p = plan(state(item("a", "Czosnek", 1.0, "ząbek")), line("Czosnek", 2.0, "ząbki"))
        assertEquals(listOf("a" to 3.0), p.summed.map { it.itemId to it.quantity })
    }

    @Test
    fun aLineWithNoUnitMeetsOnlyAnotherWithNoUnit() {
        val withUnit = plan(state(item("a", "Chleb", 1.0, "szt.")), line("Chleb"))
        assertEquals(listOf("Chleb"), withUnit.added.map { it.name })

        val without = plan(state(item("a", "Chleb", 1.0, null)), line("Chleb", 2.0))
        assertEquals(listOf("a" to 3.0), without.summed.map { it.itemId to it.quantity })
    }

    // --- Quantities -----------------------------------------------------------------------

    @Test
    fun anUncountedLineLeavesACountedItemAlone() {
        val p = plan(state(item("a", "Chleb", 2.0, null)), line("Chleb"))
        assertEquals(listOf("a" to 2.0), p.summed.map { it.itemId to it.quantity })
    }

    @Test
    fun aCountedLineCountsAnUncountedItem() {
        val p = plan(state(item("a", "Chleb", null, null)), line("Chleb", 3.0))
        assertEquals(listOf("a" to 3.0), p.summed.map { it.itemId to it.quantity })
    }

    @Test
    fun twoUncountedLinesStayUncounted() {
        val p = plan(state(item("a", "Chleb", null, null)), line("Chleb"))
        assertEquals(listOf<Double?>(null), p.summed.map { it.quantity })
    }

    // --- Within one text ------------------------------------------------------------------

    @Test
    fun oneThingNamedTwiceInOneTextIsAddedOnce() {
        val p = ImportPlan.of(listOf(line("Mleko", 1.0, "l"), line("Mleko", 2.0, "l")), null, emptyList())
        assertEquals(1, p.added.size)
        assertEquals(3.0, p.added.single().quantity!!, 0.0)
    }

    @Test
    fun oneThingNamedTwiceInOneTextGrowsAnItemOnce() {
        val p = plan(state(item("a", "Mleko", 1.0, "l")), line("Mleko", 1.0, "l"), line("Mleko", 2.0, "l"))
        assertEquals(listOf("a" to 4.0), p.summed.map { it.itemId to it.quantity })
    }

    @Test
    fun collapsingKeepsTheFirstHeadingAndTheOrder() {
        val collapsed = ImportPlan.collapse(
            listOf(
                ImportedItem("Mleko", 1.0, "l", "nabial"),
                ImportedItem("Chleb", 1.0, "szt.", "pieczywo"),
                ImportedItem("Mleko", 1.0, "l", "inne"),
            ),
        )
        assertEquals(listOf("Mleko", "Chleb"), collapsed.map { it.name })
        assertEquals("nabial", collapsed.first().categoryId)
    }

    // --- „Kupione" and tombstones ---------------------------------------------------------

    @Test
    fun aBoughtItemComesBackInsteadOfBeingAddedAgain() {
        val list = state(item("a", "Mleko", 1.0, "l"), Op.ItemCheck(id(), "l", null, 3, "a", true))
        val p = plan(list, line("Mleko", 1.0, "l"))
        assertTrue(p.added.isEmpty())
        assertEquals(listOf(ImportPlan.Summed("a", 2.0, revive = true)), p.summed)
    }

    @Test
    fun anItemStillToBuyIsPreferredToOneAlreadyBought() {
        val list = state(
            item("bought", "Mleko", 1.0, "l", at = 2),
            Op.ItemCheck(id(), "l", null, 3, "bought", true),
            item("open", "Mleko", 1.0, "l", at = 4),
        )
        val p = plan(list, line("Mleko", 1.0, "l"))
        assertEquals(listOf("open"), p.summed.map { it.itemId })
        assertTrue(p.summed.none { it.revive })
    }

    @Test
    fun aDeletedItemIsNotThereToGrow() {
        val list = state(item("a", "Mleko", 1.0, "l"), Op.ItemDelete(id(), "l", null, 3, "a"))
        val p = plan(list, line("Mleko", 1.0, "l"))
        assertEquals(listOf("Mleko"), p.added.map { it.name })
        assertTrue(p.summed.isEmpty())
    }

    @Test
    fun itemsClearedFromTheListAreNotThereToGrow() {
        val list = state(
            item("a", "Mleko", 1.0, "l"),
            Op.ItemCheck(id(), "l", null, 3, "a", true),
            Op.ClearChecked(id(), "l", null, 4),
        )
        val p = plan(list, line("Mleko", 1.0, "l"))
        assertEquals(listOf("Mleko"), p.added.map { it.name })
    }

    // --- Shape ----------------------------------------------------------------------------

    @Test
    fun addedLinesKeepTheOrderTheyWereRead() {
        val p = ImportPlan.of(
            listOf(line("Cebula"), line("Chleb"), line("Mleko")),
            null,
            emptyList(),
        )
        assertEquals(listOf("Cebula", "Chleb", "Mleko"), p.added.map { it.name })
    }

    @Test
    fun anEmptyImportDoesNothing() {
        val p = plan(state(item("a", "Mleko", 1.0, "l")))
        assertTrue(p.isEmpty)
    }
}
