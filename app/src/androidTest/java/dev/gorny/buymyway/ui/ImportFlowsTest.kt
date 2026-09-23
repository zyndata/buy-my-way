package dev.gorny.buymyway.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.imports.EatMyWayImport
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.data.ImportSummary
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.ui.imports.ImportScreen
import dev.gorny.buymyway.ui.imports.ImportViewModel
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import preview on the real screen (PLAN.md Phase 8, tasks 2–3). The text is handed over
 * the way a share sheet would hand it over; what is checked is that nothing reaches a list
 * before „Dodaj" and that what does reach it is what the preview showed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ImportFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private val store = ViewModelStore()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ListRepository(
            db = db,
            categoryOrder = { BuiltinCategories.IDS },
            categorize = { "inne" },
        )
    }

    @After
    fun close() {
        store.clear()
        db.close()
    }

    private fun <T : ViewModel> kept(vm: T): T = vm.also { store.put(it.hashCode().toString(), it) }

    private fun text(id: Int) = context.getString(id)

    private val export = """
        Lista zakupów — tydzień 15.09 – 21.09

        Warzywa i owoce
        • Cebula — 2 szt. (160 g)
        • Ziemniaki — 1,5 kg

        Nabiał i jaja
        • Mleko 2% — 500 ml (500 g)
    """.trimIndent()

    /** The imported list id and what the import did, once the button has been tapped. */
    private var landed: Pair<String, ImportSummary>? = null

    private fun show(shared: String) {
        // Built here rather than inside setContent: a view model is never constructed in a
        // composable, and the screen is handed the one the navigation graph would hand it.
        val vm = kept(ImportViewModel(shared, repo))
        compose.setContent {
            BuyMyWayTheme {
                ImportScreen(
                    vm = vm,
                    onBack = {},
                    onImported = { listId, summary -> landed = listId to summary },
                )
            }
        }
    }

    private fun itemsOfLanded(): List<Item> = runBlocking {
        val listId = landed!!.first
        repo.observeItems(listId).first().filter { Merge.isVisible(it, repo.loadState(listId).list) }
    }

    @Test
    fun theSharedListIsShownGroupedUnderItsDepartments() {
        show(export)
        compose.onNodeWithTag("importSource").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_source_eatmyway)).assertIsDisplayed()
        compose.onNodeWithText("Warzywa i owoce").assertIsDisplayed()
        compose.onNodeWithText("Nabiał i jaja").assertIsDisplayed()
        compose.onNodeWithText("Cebula").assertIsDisplayed()
        compose.onNodeWithText("Ziemniaki").assertIsDisplayed()
        compose.onNodeWithText("Mleko 2%").assertIsDisplayed()
        // The amounts are shown as the list will hold them.
        compose.onNodeWithText("2 szt.").assertIsDisplayed()
        compose.onNodeWithText("1,5 kg").assertIsDisplayed()
    }

    @Test
    fun nothingReachesAnyListBeforeTheButtonIsTapped() {
        show(export)
        compose.onNodeWithText("Cebula").assertIsDisplayed()
        assertNull(landed)
        assertEquals(emptyList<Any>(), runBlocking { repo.observeLists().first() })
    }

    @Test
    fun oneTapMakesTheListAndFillsIt() {
        show(export)
        compose.onNodeWithTag("doImport").performClick()
        compose.waitUntil(WAIT_MS) { landed != null }

        val (listId, summary) = landed!!
        assertEquals(ImportSummary(added = 3, summed = 0, revived = 0), summary)
        val byName = itemsOfLanded().associateBy { it.name }
        assertEquals(setOf("Cebula", "Ziemniaki", "Mleko 2%"), byName.keys)
        assertEquals("warzywa", byName.getValue("Cebula").categoryId)
        assertEquals("nabial", byName.getValue("Mleko 2%").categoryId)
        // The new list is named after the title's date range.
        assertEquals("tydzień 15.09 – 21.09", runBlocking { repo.observeList(listId).first()!!.list.name })
    }

    @Test
    fun theNewListsNameCanBeChangedBeforeImporting() {
        show(export)
        compose.onNodeWithTag("importListName").performTextReplacement("Sobota")
        compose.onNodeWithTag("doImport").performClick()
        compose.waitUntil(WAIT_MS) { landed != null }
        assertEquals("Sobota", runBlocking { repo.observeList(landed!!.first).first()!!.list.name })
    }

    @Test
    fun aLineRemovedInThePreviewIsNotImported() {
        show(export)
        compose.onNodeWithContentDescription(context.getString(R.string.action_remove_imported, "Ziemniaki")).performClick()
        compose.onNodeWithTag("doImport").performClick()
        compose.waitUntil(WAIT_MS) { landed != null }

        assertEquals(2, landed!!.second.added)
        assertEquals(setOf("Cebula", "Mleko 2%"), itemsOfLanded().map { it.name }.toSet())
    }

    @Test
    fun anExistingListCanBeChosenInsteadOfANewOne() {
        val existing = runBlocking { repo.createList("Sobota") }
        show(export)
        compose.onNodeWithTag("importTarget").performClick()
        compose.onNodeWithTag("target-$existing").performClick()
        compose.onNodeWithTag("doImport").performClick()
        compose.waitUntil(WAIT_MS) { landed != null }

        assertEquals(existing, landed!!.first)
        assertEquals(3, itemsOfLanded().size)
        // No second list was made.
        assertEquals(1, runBlocking { repo.observeLists().first() }.size)
    }

    @Test
    fun theSameTextTwiceSumsInsteadOfDuplicating() {
        val existing = runBlocking { repo.createList("Sobota") }
        runBlocking { repo.importItems(existing, EatMyWayImport.parse(export).items) }

        show(export)
        compose.onNodeWithTag("importTarget").performClick()
        compose.onNodeWithTag("target-$existing").performClick()
        compose.onNodeWithTag("doImport").performClick()
        compose.waitUntil(WAIT_MS) { landed != null }

        assertEquals(ImportSummary(added = 0, summed = 3, revived = 0), landed!!.second)
        val byName = itemsOfLanded().associateBy { it.name }
        assertEquals(3, byName.size)
        assertEquals(4.0, byName.getValue("Cebula").quantity!!, 0.0)
    }

    @Test
    fun plainTextIsPreviewedAsOneLinePerName() {
        show("mleko\nchleb\nmasło")
        compose.onNodeWithText(text(R.string.import_source_text)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_no_category)).assertIsDisplayed()
        compose.onNodeWithTag("doImport").performClick()
        compose.waitUntil(WAIT_MS) { landed != null }
        assertEquals(setOf("mleko", "chleb", "masło"), itemsOfLanded().map { it.name }.toSet())
    }

    @Test
    fun textThatNamesNothingSaysSoAndOffersNoButton() {
        show("Lista zakupów — środa\n\nBrak składników do kupienia.")
        compose.onNodeWithTag("importEmpty").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("doImport")).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        /**
         * The import itself is a few database writes, so this is not how long it takes — it is
         * how long a loaded CI emulator may take to get round to running it. Waiting longer
         * costs nothing when the work is already done.
         */
        const val WAIT_MS = 30_000L
    }
}
