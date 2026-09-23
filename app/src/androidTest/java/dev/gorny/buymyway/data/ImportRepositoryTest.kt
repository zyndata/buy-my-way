package dev.gorny.buymyway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.imports.EatMyWayImport
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An Eat My Way list poured into a real Room database (PLAN.md Phase 8, task 3). The parser and
 * the merge rules are tested on the JVM; what this adds is that the whole thing reaches the
 * database as one batch, with the categories, the ordering and the outbox it should leave.
 */
@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private var now = 1_000_000L
    private var ids = 0

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ListRepository(
            db = db,
            categoryOrder = { emptyList() },
            categorize = { name -> if (name.contains("mleko", ignoreCase = true)) "nabial" else "inne" },
            clock = { now },
            newId = { "id-${ids++}" },
        )
    }

    @After
    fun close() = db.close()

    private val export = """
        Lista zakupów — tydzień 15.09 – 21.09

        Warzywa i owoce
        • Cebula — 2 szt. (160 g)
        • Ziemniaki — 1,5 kg

        Nabiał i jaja
        • Mleko 2% — 500 ml (500 g)
    """.trimIndent()

    private fun parse(text: String) = EatMyWayImport.parse(text).items

    private suspend fun items(listId: String): List<Item> =
        repo.observeItems(listId).first().filter { Merge.isVisible(it, repo.loadState(listId).list) }

    private suspend fun byName(listId: String): Map<String, Item> = items(listId).associateBy { it.name }

    private suspend fun outboxOps(): List<Op> = db.outbox().oldest(Int.MAX_VALUE).map { Op.decode(it.payload) }

    // --- The first acceptance criterion ----------------------------------------------------

    @Test
    fun aWeeksListLandsInItsDepartmentsWithItsQuantities() = runTest {
        val listId = repo.createList("Zakupy")
        val summary = repo.importItems(listId, parse(export))

        assertEquals(ImportSummary(added = 3, summed = 0, revived = 0), summary)
        val items = byName(listId)
        assertEquals(setOf("Cebula", "Ziemniaki", "Mleko 2%"), items.keys)
        assertEquals("warzywa", items.getValue("Cebula").categoryId)
        assertEquals("warzywa", items.getValue("Ziemniaki").categoryId)
        assertEquals("nabial", items.getValue("Mleko 2%").categoryId)
        assertEquals(2.0, items.getValue("Cebula").quantity!!, 0.0)
        assertEquals("szt.", items.getValue("Cebula").unit)
        assertEquals(1.5, items.getValue("Ziemniaki").quantity!!, 0.0)
        assertEquals("kg", items.getValue("Ziemniaki").unit)
        assertEquals(500.0, items.getValue("Mleko 2%").quantity!!, 0.0)
        assertEquals("ml", items.getValue("Mleko 2%").unit)
    }

    @Test
    fun theWholeImportIsOneBatchAtOneMoment() = runTest {
        val listId = repo.createList("Zakupy")
        val before = outboxOps().size
        repo.importItems(listId, parse(export))
        val added = outboxOps().drop(before)
        assertEquals(3, added.size)
        assertEquals(1, added.map { it.at }.distinct().size)
        // Room holds exactly what the pure merge makes of the outbox.
        assertEquals(Merge.apply(outboxOps().filter { it.listId == listId }, ListState()), repo.loadState(listId))
    }

    @Test
    fun itemsOfOneDepartmentKeepTheOrderTheyWereRead() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse(export))
        val warzywa = items(listId).filter { it.categoryId == "warzywa" }.sortedBy { it.sortKey }
        assertEquals(listOf("Cebula", "Ziemniaki"), warzywa.map { it.name })
    }

    // --- The second acceptance criterion ----------------------------------------------------

    @Test
    fun theSameTextTwiceSumsQuantitiesAndAddsNoDuplicate() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse(export))
        now += 1000
        val second = repo.importItems(listId, parse(export))

        assertEquals(ImportSummary(added = 0, summed = 3, revived = 0), second)
        val items = byName(listId)
        assertEquals(3, items.size)
        assertEquals(4.0, items.getValue("Cebula").quantity!!, 0.0)
        assertEquals(3.0, items.getValue("Ziemniaki").quantity!!, 0.0)
        assertEquals(1000.0, items.getValue("Mleko 2%").quantity!!, 0.0)
    }

    @Test
    fun aBoughtItemComesBackInsteadOfBeingAddedTwice() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse(export))
        val onion = byName(listId).getValue("Cebula")
        repo.setChecked(onion.id, true)
        now += 1000

        val summary = repo.importItems(listId, parse(export))
        assertEquals(1, summary.revived)
        val back = byName(listId).getValue("Cebula")
        assertEquals(onion.id, back.id)
        assertFalse(back.checked)
        assertEquals(4.0, back.quantity!!, 0.0)
    }

    // --- Headings ---------------------------------------------------------------------------

    @Test
    fun anUnknownHeadingBecomesACategoryOfTheListAndItsWalkOrderGainsIt() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(
            listId,
            parse("Lista zakupów — środa\n\nChemia i higiena\n• Mydło — 1 szt.\n• Pasta do zębów — 1 szt."),
        )
        val detail = repo.observeList(listId).first()!!
        val made = detail.categories.firstOrNull { it.name == "Chemia i higiena" }
        assertNotNull("the heading became a category", made)
        assertEquals(made!!.id, detail.categories.last().id)
        assertEquals(listOf(made.id, made.id), items(listId).map { it.categoryId })
    }

    @Test
    fun oneHeadingMakesOneCategoryHoweverManyLinesStandUnderIt() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse("Chemia\n• Mydło — 1 szt.\n• Szampon — 1 szt."))
        val made = repo.observeList(listId).first()!!.categories.filter { it.name == "Chemia" }
        assertEquals(1, made.size)
    }

    @Test
    fun aHeadingTheListAlreadyHasIsNotMadeAgain() = runTest {
        val listId = repo.createList("Zakupy")
        val existing = repo.addCategory(listId, "Chemia")
        repo.importItems(listId, parse("Chemia\n• Mydło — 1 szt."))
        assertEquals(existing, items(listId).single().categoryId)
        assertEquals(1, repo.observeList(listId).first()!!.categories.count { it.name == "Chemia" })
    }

    @Test
    fun aLineWithNoHeadingIsCategorisedAsATypedOneWouldBe() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse("mleko\nchleb"))
        val items = byName(listId)
        assertEquals("nabial", items.getValue("mleko").categoryId)
        assertEquals("inne", items.getValue("chleb").categoryId)
    }

    @Test
    fun anImportedNameIsRememberedForTheAddBar() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse("Pieczywo\n• Bułka tarta — 100 g"))
        assertEquals("pieczywo", db.nameHistory().get("bulka tarta")?.categoryId)
    }

    // --- Anything else ------------------------------------------------------------------------

    @Test
    fun plainTextBecomesOneItemPerLine() = runTest {
        val listId = repo.createList("Zakupy")
        val summary = repo.importItems(listId, parse("mleko\nchleb\nmasło"))
        assertEquals(3, summary.added)
        assertEquals(listOf("mleko", "chleb", "masło"), items(listId).sortedBy { it.sortKey }.map { it.name })
    }

    @Test
    fun anEmptyImportWritesNothing() = runTest {
        val listId = repo.createList("Zakupy")
        val before = outboxOps().size
        assertEquals(ImportSummary(0, 0, 0), repo.importItems(listId, parse("Lista zakupów — środa\n\nBrak składników do kupienia.")))
        assertEquals(before, outboxOps().size)
    }

    @Test
    fun importingIntoAListThatIsNotThereIsRefused() = runTest {
        try {
            repo.importItems("no-such-list", parse(export))
            fail("expected the list to be missing")
        } catch (_: NoSuchElementException) {
            // The list has to exist before anything is poured into it.
        }
    }

    // --- What an import does not touch ----------------------------------------------------

    @Test
    fun anImportLeavesTheItemsItDidNotNameAlone() = runTest {
        val listId = repo.createList("Zakupy")
        val other = repo.addItem(listId, "Papier toaletowy", quantity = 4.0, unit = "szt.").itemId
        now += 1000
        repo.importItems(listId, parse(export))
        val kept = items(listId).first { it.id == other }
        assertEquals(4.0, kept.quantity!!, 0.0)
        assertEquals("szt.", kept.unit)
    }

    @Test
    fun anImportedItemIsNotPlacedInTheManualOrder() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse(export))
        assertTrue(items(listId).all { it.manualKey == null })
    }

    @Test
    fun anImportedItemCarriesNoPhotoAndNoNote() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse(export))
        assertTrue(items(listId).all { it.photoAt == null && it.note == null })
    }

    @Test
    fun theTargetListKeepsItsName() = runTest {
        val listId = repo.createList("Sobota")
        repo.importItems(listId, parse(export))
        assertEquals("Sobota", repo.observeList(listId).first()!!.list.name)
    }

    @Test
    fun aQuantityIsSummedOntoAnItemTypedByHand() = runTest {
        val listId = repo.createList("Zakupy")
        repo.addItem(listId, "Cebula", quantity = 1.0, unit = "szt.")
        now += 1000
        val summary = repo.importItems(listId, parse(export))
        assertEquals(2, summary.added)
        assertEquals(1, summary.summed)
        assertEquals(3.0, byName(listId).getValue("Cebula").quantity!!, 0.0)
    }

    @Test
    fun onlyEditableListsAreOffered() = runTest {
        val mine = repo.createList("Moja")
        val offered = repo.observeEditableLists().first().map { it.list.id }
        assertEquals(listOf(mine), offered)
        assertNull(repo.observeList(mine).first()!!.list.ownerUid)
    }

    @Test
    fun anItemContentIsWhatTheOutboxSays() = runTest {
        val listId = repo.createList("Zakupy")
        repo.importItems(listId, parse("• Cebula — 2 szt."))
        val put = outboxOps().filterIsInstance<Op.ItemPut>().last()
        assertEquals(ItemContent(name = "Cebula", quantity = 2.0, unit = "szt.", categoryId = "inne", sortKey = 1.0), put.content)
    }
}
