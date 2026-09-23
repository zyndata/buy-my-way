package dev.gorny.buymyway.core.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Eat My Way import parser (PLAN.md Phase 8, task 1: „30+ tests including a real export").
 *
 * The format under test is what `formatShoppingList` writes in Eat My Way's
 * `src/lib/shopping.ts`; `src/test/resources/eatmyway-export.txt` is a week's list in exactly
 * that shape (STATE.md decision 83).
 */
class EatMyWayImportTest {

    /** Unit tests run with the module directory as the working directory. */
    private val export = File("src/test/resources/eatmyway-export.txt").readText()

    private fun parse(text: String) = EatMyWayImport.parse(text)

    private fun items(text: String) = parse(text).items

    private fun one(text: String): ImportedItem = items(text).single()

    // --- A real export --------------------------------------------------------------------

    @Test
    fun theRealExportGivesEveryLineAndNothingElse() {
        val parsed = parse(export)
        assertTrue(parsed.fromEatMyWay)
        // Every „• " line of the fixture, and none of the eight headings or the title.
        assertEquals(export.lines().count { it.startsWith("•") }, parsed.items.size)
        assertEquals(46, parsed.items.size)
    }

    @Test
    fun theRealExportsTitleNamesTheNewList() {
        val parsed = parse(export)
        assertEquals("Lista zakupów — tydzień 15.09 – 21.09", parsed.title)
        assertEquals("tydzień 15.09 – 21.09", parsed.suggestedName)
    }

    @Test
    fun everyLineOfTheRealExportKeepsTheDepartmentItWasPrintedUnder() {
        val byName = parse(export).items.associate { it.name to it.categoryId }
        assertEquals("warzywa", byName["Ziemniaki"])
        assertEquals("nabial", byName["Mleko 2%"])
        assertEquals("mieso", byName["Pierś z kurczaka"])
        assertEquals("pieczywo", byName["Chleb żytni"])
        assertEquals("sypkie", byName["Makaron pszenny"])
        assertEquals("przyprawy", byName["Oliwa z oliwek"])
        // A department heading is never a category of the list's own.
        assertTrue(parse(export).items.all { it.categoryName == null })
    }

    @Test
    fun theRealExportsAmountsAreRead() {
        val byName = parse(export).items.associateBy { it.name }
        assertEquals(ImportedItem("Ziemniaki", 1.5, "kg", "warzywa"), byName.getValue("Ziemniaki"))
        assertEquals(ImportedItem("Pomidory", 500.0, "g", "warzywa"), byName.getValue("Pomidory"))
        assertEquals(ImportedItem("Mleko 2%", 2.0, "l", "nabial"), byName.getValue("Mleko 2%"))
        assertEquals(ImportedItem("Jajko kurze", 10.0, "szt.", "nabial"), byName.getValue("Jajko kurze"))
        assertEquals(ImportedItem("Czosnek", 5.0, "ząbków", "warzywa"), byName.getValue("Czosnek"))
        assertEquals(ImportedItem("Śmietana 18%", 400.0, "ml", "nabial"), byName.getValue("Śmietana 18%"))
    }

    @Test
    fun noLineOfTheRealExportKeepsItsGramsSuffix() {
        assertTrue(parse(export).items.none { it.name.contains("(") || it.unit.orEmpty().contains("(") })
    }

    // --- The shape of one line ------------------------------------------------------------

    @Test
    fun aBulletLineIsANameAndAnAmount() {
        assertEquals(ImportedItem("Cebula", 2.0, "szt."), one("• Cebula — 2 szt."))
    }

    @Test
    fun theGramsSuffixIsDropped() {
        assertEquals(ImportedItem("Cebula", 2.0, "szt."), one("• Cebula — 2 szt. (160 g)"))
    }

    @Test
    fun aHouseholdMeasureIsKeptAsItWasWritten() {
        assertEquals(ImportedItem("Czosnek", 2.0, "ząbki"), one("• Czosnek — 2 ząbki (10 g)"))
        assertEquals(ImportedItem("Oliwa z oliwek", 1.0, "łyżka"), one("• Oliwa z oliwek — 1 łyżka (12 g)"))
    }

    @Test
    fun aDecimalCommaIsANumber() {
        assertEquals(ImportedItem("Ziemniaki", 1.5, "kg"), one("• Ziemniaki — 1,5 kg"))
        assertEquals(ImportedItem("Mleko", 0.5, "l"), one("• Mleko — 0,5 l"))
    }

    @Test
    fun aThousandsGroupIsOneNumber() {
        // Polish formatting puts a non-breaking space (U+00A0) inside „1 200"; it is not a unit,
        // and it is invisible in source, so the test builds it by code point.
        val nbsp = 160.toChar()
        assertEquals(ImportedItem("Mąka", 1200.0, "g"), one("• Mąka — 1${nbsp}200 g"))
        assertEquals(ImportedItem("Mąka", 1200.0, "g"), one("• Mąka — 1 200 g"))
    }

    @Test
    fun theShortFormOfAUnitIsWhatIsStored() {
        assertEquals("szt.", one("• Jajka — 10 sztuk").unit)
        assertEquals("kg", one("• Ziemniaki — 2 kilogramy").unit)
        assertEquals("op.", one("• Chusteczki — 2 opakowania").unit)
    }

    @Test
    fun aLineWithNoAmountIsStillAnItem() {
        assertEquals(ImportedItem("Chleb"), one("• Chleb"))
    }

    @Test
    fun anAmountThatIsNotANumberStaysPartOfTheName() {
        assertEquals(ImportedItem("Sól — do smaku"), one("• Sól — do smaku"))
    }

    @Test
    fun aNameMayHoldADashOfItsOwn() {
        assertEquals(ImportedItem("Ser feta - grecki", 200.0, "g"), one("• Ser feta - grecki — 200 g"))
    }

    @Test
    fun otherBulletsAndOtherDashesAreReadToo() {
        assertEquals(ImportedItem("Chleb", 1.0, "szt."), one("- Chleb - 1 szt."))
        assertEquals(ImportedItem("Chleb", 1.0, "szt."), one("* Chleb – 1 szt."))
        assertEquals(ImportedItem("Chleb", 1.0, "szt."), one("1. Chleb — 1 szt."))
    }

    @Test
    fun aQuantityOutOfRangeIsNoQuantity() {
        assertEquals(ImportedItem("Ryż"), one("• Ryż — 99999 g"))
        assertEquals(ImportedItem("Ryż"), one("• Ryż — 0 g"))
    }

    @Test
    fun whitespaceInANameIsCollapsed() {
        assertEquals("Ser żółty", one("•   Ser    żółty   —  200 g").name)
    }

    // --- Headings -------------------------------------------------------------------------

    @Test
    fun eachOfTheNineDepartmentsMapsOntoItsId() {
        val text = buildString {
            appendLine("Lista zakupów — środa")
            appendLine()
            listOf(
                "Warzywa i owoce" to "warzywa", "Nabiał i jaja" to "nabial",
                "Mięso, ryby i wędliny" to "mieso", "Pieczywo" to "pieczywo",
                "Sypkie i makarony" to "sypkie", "Przyprawy i dodatki" to "przyprawy",
                "Mrożonki" to "mrozonki", "Napoje" to "napoje", "Inne" to "inne",
            ).forEach { (label, id) ->
                appendLine(label)
                appendLine("• $id — 1 szt.")
                appendLine()
            }
        }
        assertEquals(items(text).map { it.name }, items(text).map { it.categoryId })
    }

    @Test
    fun anUnknownHeadingBecomesACategoryOfItsOwn() {
        val parsed = items(
            """
            Lista zakupów — środa

            Chemia i higiena
            • Mydło — 1 szt.
            """.trimIndent(),
        )
        assertEquals(ImportedItem("Mydło", 1.0, "szt.", null, "Chemia i higiena"), parsed.single())
    }

    @Test
    fun aHeadingOnlyHoldsTheLinesUnderIt() {
        val parsed = items(
            """
            Warzywa i owoce
            • Cebula — 1 szt.

            Pieczywo
            • Chleb — 1 szt.
            """.trimIndent(),
        )
        assertEquals(listOf("warzywa", "pieczywo"), parsed.map { it.categoryId })
    }

    @Test
    fun textWithNoHeadingLeavesTheCategoryToTheDictionary() {
        assertNull(one("mleko").categoryId)
        assertNull(one("mleko").categoryName)
    }

    // --- Anything else (the third acceptance criterion) ------------------------------------

    @Test
    fun plainTextIsOneItemPerLine() {
        val parsed = items("mleko\nchleb\nmasło")
        assertEquals(listOf("mleko", "chleb", "masło"), parsed.map { it.name })
        assertTrue(parsed.all { it.categoryId == null && it.categoryName == null })
    }

    @Test
    fun plainTextIsNotMistakenForAnExport() {
        assertFalse(parse("mleko\nchleb").fromEatMyWay)
        assertEquals(EatMyWayImport.DEFAULT_NAME, parse("mleko\nchleb").suggestedName)
    }

    @Test
    fun aHandTypedListUnderATitleKeepsEveryLine() {
        // No bullets anywhere, so nothing here heads anything: three names, not one and a heading.
        val parsed = items("Lista zakupów — sobota\n\nmleko\nchleb\nmasło")
        assertEquals(listOf("mleko", "chleb", "masło"), parsed.map { it.name })
    }

    @Test
    fun anEmptyExportIsAnEmptyList() {
        val parsed = parse("Lista zakupów — środa\n\nBrak składników do kupienia.\n")
        assertTrue(parsed.fromEatMyWay)
        assertEquals(emptyList<ImportedItem>(), parsed.items)
        assertEquals("środa", parsed.suggestedName)
    }

    @Test
    fun blankTextNamesNothing() {
        assertEquals(emptyList<ImportedItem>(), items(""))
        assertEquals(emptyList<ImportedItem>(), items("   \n\n  \t "))
        assertEquals(EatMyWayImport.DEFAULT_NAME, parse("").suggestedName)
    }

    @Test
    fun windowsLineEndingsAreReadTheSameWay() {
        assertEquals(items("mleko\nchleb"), items("mleko\r\nchleb\r\n"))
    }

    @Test
    fun junkIsReadAsNamesAndNeverThrows() {
        val junk = listOf(
            "•", "— — —", "\u0000\u0001", "1234567890", "((((", "— 2 kg",
            "a".repeat(5000), "😀 🍞", "<html><body>x</body></html>", "{\"a\":1}",
        )
        junk.forEach { text ->
            // The only promise is that it answers at all, with names that are never blank.
            assertTrue(text, EatMyWayImport.parse(text).items.all { it.name.isNotBlank() })
        }
    }

    @Test
    fun aTitleOnItsOwnNamesTheListAndNothingElse() {
        val parsed = parse("Lista zakupów — tydzień 01.12 – 07.12\n")
        assertEquals(emptyList<ImportedItem>(), parsed.items)
        assertEquals("tydzień 01.12 – 07.12", parsed.suggestedName)
    }

    @Test
    fun aTitleWithNoRangeStillNamesTheList() {
        assertEquals("Lista zakupów", parse("Lista zakupów\n\n• Chleb — 1 szt.").suggestedName)
    }

    // --- Units --------------------------------------------------------------------------

    @Test
    fun oneMeasureIsOneUnitWhateverFormItWasPrintedIn() {
        assertEquals(EatMyWayImport.unitKey("ząbek"), EatMyWayImport.unitKey("ząbki"))
        assertEquals(EatMyWayImport.unitKey("ząbek"), EatMyWayImport.unitKey("ząbków"))
        assertEquals(EatMyWayImport.unitKey("łyżka"), EatMyWayImport.unitKey("łyżek"))
        assertEquals(EatMyWayImport.unitKey("szt."), EatMyWayImport.unitKey("sztuki"))
    }

    @Test
    fun differentUnitsStayDifferent() {
        val keys = listOf("g", "kg", "ml", "l", "szt.", "ząbek", "łyżka", null).map { EatMyWayImport.unitKey(it) }
        assertEquals(keys.size, keys.distinct().size)
        assertEquals("", EatMyWayImport.unitKey(null))
        assertEquals("", EatMyWayImport.unitKey("  "))
    }
}
