package dev.gorny.buymyway.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.ui.list.ListLive
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.ui.categories.CategoryOrderScreen
import dev.gorny.buymyway.ui.categories.CategoryOrderViewModel
import dev.gorny.buymyway.ui.list.ListScreen
import dev.gorny.buymyway.ui.list.ListViewModel
import dev.gorny.buymyway.ui.lists.ListsScreen
import dev.gorny.buymyway.ui.lists.ListsViewModel
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.gorny.buymyway.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The screen flows (PLAN.md Phase 3, task 6, and Phase 5), on the real screens and view models
 * over an in-memory Room: add → check → „Kupione" → back; category reorder persists; delete +
 * „Cofnij"; someone else's tick with their initial; the sort views; the item dates.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ListRepository(
            db = db,
            categoryOrder = { BuiltinCategories.IDS },
            categorize = { name -> if (name.contains("mleko", ignoreCase = true)) "nabial" else "warzywa" },
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The list screen's link to sync, played by the test: who is looking, and the sort view. */
    private class FakeLive : ListLive {
        val present = MutableStateFlow<Set<String>>(emptySet())
        val view = MutableStateFlow(SortView.DEPARTMENTS)

        override suspend fun myUid(): String? = null

        override fun watch(listId: String): Flow<Set<String>> = present

        override fun sortView(listId: String): Flow<SortView> = view

        override suspend fun setSortView(listId: String, view: SortView) {
            this.view.value = view
        }
    }

    private fun showList(listId: String, live: ListLive? = null): ListViewModel {
        val vm = ListViewModel(repo, listId, { _, _ -> emptyList() }, scope, live)
        compose.setContent { BuyMyWayTheme { ListScreen(vm, onBack = {}, onOpenCategoryOrder = {}, onOpenShare = {}) } }
        // The screen (and its add bar) appears once Room has answered.
        waitFor { exists(hasTestTag("addField")) }
        return vm
    }

    @Test
    fun addCheckItGoesToKupioneAndATapThereBringsItBack() {
        val listId = runBlocking { repo.createList("Sobota") }
        showList(listId)

        compose.onNodeWithTag("addField").performTextInput("2 l mleko, ziemniaki")
        compose.onNodeWithTag("addField").performImeAction()
        waitFor { exists(hasTestTag("item:mleko")) && exists(hasTestTag("item:ziemniaki")) }
        assertEquals(true, exists(hasText("Nabiał i jaja")))
        // On a small phone the keyboard leaves room for a few rows only.
        compose.onNodeWithTag("items").performScrollToNode(hasTestTag("item:mleko"))

        // A tap strikes it through at once, in place…
        compose.onNodeWithTag("item:mleko").performClick()
        waitFor(1_000) { exists(hasTestTag("item:mleko") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)) }
        waitFor(2_000) { exists(hasText(text(R.string.bought_header, 1))) }
        // …and within a second it has moved to „Kupione", which starts collapsed.
        waitFor(2_000) { !exists(hasTestTag("item:mleko")) }
        assertEquals(true, runBlocking { db.items().getAllForList(listId).single { it.name == "mleko" }.checked })

        compose.onNodeWithTag("items").performScrollToNode(hasText(text(R.string.bought_header, 1)))
        compose.onNodeWithText(text(R.string.bought_header, 1)).performClick()
        compose.onNodeWithTag("items").performScrollToNode(hasTestTag("item:mleko"))
        waitFor { exists(hasTestTag("item:mleko") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)) }
        compose.onNodeWithTag("item:mleko").performClick()

        waitFor { exists(hasTestTag("item:mleko") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)) }
        assertEquals(false, exists(hasText(text(R.string.bought_header, 1))))
        assertEquals(false, runBlocking { db.items().getAllForList(listId).single { it.name == "mleko" }.checked })
    }

    @Test
    fun aCategoryDraggedInTheEditorKeepsItsNewPlace() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = CategoryOrderViewModel(
            categories = repo.observeList(listId).map { it?.categories.orEmpty() },
            save = { repo.setCategoryOrder(listId, it) },
            edits = null,
        )
        compose.setContent { BuyMyWayTheme { CategoryOrderScreen(vm, R.string.title_category_order, onBack = {}) } }
        waitFor { exists(hasTestTag("drag:warzywa")) }

        // Drag „Warzywa i owoce" down by two and a half rows, a few pixels at a time.
        val top = { name: String -> compose.onNodeWithTag("category:$name").fetchSemanticsNode().boundsInRoot.top }
        val row = top("Nabiał i jaja") - top("Warzywa i owoce")
        // One step per frame, as a finger would: the list lays out between the moves.
        val handle = compose.onNodeWithTag("drag:warzywa")
        handle.performTouchInput { down(center) }
        repeat(50) {
            handle.performTouchInput { moveBy(Offset(0f, row * 2.5f / 50)) }
            compose.waitForIdle()
        }
        handle.performTouchInput { up() }
        val order = { runBlocking { repo.observeList(listId).first()!!.list.categoryOrder } }
        runCatching { waitFor { order().take(3) == listOf("nabial", "mieso", "warzywa") } }
        assertEquals(listOf("nabial", "mieso", "warzywa"), order().take(3))

        // TalkBack's way: „Przesuń wyżej" on „Pieczywo" puts it above „Warzywa i owoce".
        val actions = compose.onNodeWithTag("category:Pieczywo").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == text(R.string.action_move_up) }.action() }
        waitFor { order().take(4) == listOf("nabial", "mieso", "pieczywo", "warzywa") }

        // Persisted: a fresh list screen walks the shop in the new order.
        runBlocking {
            repo.addItem(listId, "ziemniaki", categoryId = "warzywa")
            repo.addItem(listId, "chleb", categoryId = "pieczywo")
        }
        assertEquals(listOf("pieczywo", "warzywa"), runBlocking { repo.observeList(listId).first()!!.sections.map { it.category?.id } })
    }

    @Test
    fun aDeletedListComesBackWithCofnijAndIsGoneWithoutIt() {
        val keep = runBlocking { repo.createList("Sobota") }
        val drop = runBlocking { repo.createList("Niedziela") }
        val prefs = ListOrderPreferences(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb") },
        )
        val vm = ListsViewModel(repo, prefs, scope)
        compose.setContent { BuyMyWayTheme { ListsScreen(vm, onOpenList = {}, onOpenSettings = {}, onOpenShare = {}) } }
        waitFor { exists(hasText("Niedziela")) }

        deleteFromMenu("Sobota")
        waitFor { !exists(hasText("Sobota")) }
        compose.onNodeWithText(text(R.string.action_undo)).performClick()
        waitFor { exists(hasText("Sobota")) }
        assertEquals(null, runBlocking { db.lists().get(keep)!!.deletedAt })

        deleteFromMenu("Niedziela")
        waitFor { !exists(hasText("Niedziela")) }
        // Held back until the snackbar closes without „Cofnij" (STATE.md decision 43)…
        assertNull(runBlocking { db.lists().get(drop)!!.deletedAt })
        compose.runOnIdle { vm.held.snackbar.currentSnackbarData?.dismiss() }
        // …and committed when it does.
        waitFor { runBlocking { db.lists().get(drop)!!.deletedAt } != null }
        compose.onNodeWithText("Sobota").assertIsDisplayed()
    }

    @Test
    fun anItemDeletedInTheEditSheetComesBackWithCofnij() {
        val listId = runBlocking { repo.createList("Sobota") }
        val itemId = runBlocking { repo.addItem(listId, "ziemniaki").itemId }
        showList(listId)
        waitFor { exists(hasTestTag("item:ziemniaki")) }

        compose.onNodeWithTag("item:ziemniaki").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        waitFor { !exists(hasTestTag("item:ziemniaki")) }
        compose.onNodeWithText(text(R.string.action_undo)).performClick()
        waitFor { exists(hasTestTag("item:ziemniaki")) }
        assertNull(runBlocking { db.items().get(itemId)!!.deletedAt })
    }

    private fun deleteFromMenu(listName: String) {
        compose.onNode(hasText(listName)).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
    }

    // --- Phase 5 -----------------------------------------------------------------------------

    @Test
    fun someoneElsesTickShowsTheirInitialHoldsThenSlidesIntoKupione() {
        val listId = runBlocking { repo.createList("Sobota") }
        val itemId = runBlocking { repo.addItem(listId, "mleko").itemId }
        runBlocking { repo.applyMembers(listId, listOf(Member(listId, ANIA, Role.EDITOR, 1, "Ania", null, null))) }
        val live = FakeLive()
        showList(listId, live)
        waitFor { exists(hasTestTag("item:mleko")) }

        // Ania opens the list on her phone…
        live.present.value = setOf(ANIA)
        waitFor { exists(hasText(text(R.string.watching_one, "Ania"))) }

        // …and ticks „mleko": it arrives as a remote node, as the listener would bring it.
        val item = runBlocking { repo.loadState(listId).items.getValue(itemId) }
        val remote = item.copy(checked = true, checkedAt = item.updatedAt + 1_000, checkedBy = ANIA)
        runBlocking { repo.applyRemote(listId, ListState(items = mapOf(itemId to remote)), serverTime = 1) }
        // The initial sits inside the row, whose click merges it: look in the unmerged tree.
        val initial = hasTestTag("tickedBy:mleko") and hasText("A")
        waitFor { compose.onAllNodes(initial, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Still in place, struck through, for 1.5 s; then in „Kupione".
        waitFor(timeoutMs = 4_000) {
            exists(hasText(text(R.string.bought_header, 1))) &&
                compose.onAllNodes(initial, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun theSortViewsAlphabeticalAndManualAreFlatAndManualKeepsItsOrder() {
        val listId = runBlocking { repo.createList("Sobota") }
        runBlocking {
            repo.addItem(listId, "ziemniaki", categoryId = "warzywa")
            repo.addItem(listId, "mleko", categoryId = "nabial")
            repo.addItem(listId, "banany", categoryId = "warzywa")
        }
        val live = FakeLive()
        showList(listId, live)
        waitFor { exists(hasText("Warzywa i owoce")) }
        assertEquals(listOf("ziemniaki", "banany", "mleko"), shownItems())

        chooseView(SortView.ALPHABETICAL)
        waitFor { !exists(hasText("Warzywa i owoce")) }
        assertEquals(listOf("banany", "mleko", "ziemniaki"), shownItems())

        chooseView(SortView.MANUAL)
        // Choosing „Ręcznie" places everything in the order it was shown by department.
        waitFor { runBlocking { repo.loadState(listId).items.values.all { it.manualKey != null } } }
        assertEquals(listOf("ziemniaki", "banany", "mleko"), shownItems())
        val actions = compose.onNodeWithTag("item:mleko").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == text(R.string.action_move_up) }.action() }
        waitFor { shownItems() == listOf("ziemniaki", "mleko", "banany") }
        assertEquals(SortView.MANUAL, live.view.value)
        assertEquals(false, exists(hasText("Warzywa i owoce")))
    }

    @Test
    fun theEditSheetShowsWhenAnItemWasEditedAndBought() {
        val listId = runBlocking { repo.createList("Sobota") }
        val itemId = runBlocking { repo.addItem(listId, "mleko").itemId }
        runBlocking { repo.setChecked(itemId, true) }
        showList(listId)
        compose.onNodeWithText(text(R.string.bought_header, 1)).performClick()
        waitFor { exists(hasTestTag("item:mleko")) }
        compose.onNodeWithTag("item:mleko").performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor { exists(hasTestTag("itemDates")) }
        compose.onNodeWithText(text(R.string.item_edited, ""), substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.item_bought, ""), substring = true).assertIsDisplayed()
    }

    @Test
    fun aViewerSeesTheListButNoAddBar() {
        val listId = runBlocking { repo.createList("Wspólna") }
        runBlocking {
            repo.addItem(listId, "mleko")
            // The list belongs to Ania; this phone's user is signed out, so only a viewer's copy.
            db.lists().upsert(db.lists().get(listId)!!.copy(ownerUid = ANIA))
        }
        val vm = ListViewModel(repo, listId, { _, _ -> emptyList() }, scope)
        compose.setContent { BuyMyWayTheme { ListScreen(vm, onBack = {}, onOpenCategoryOrder = {}, onOpenShare = {}) } }
        waitFor { exists(hasTestTag("readOnly")) }
        assertEquals(false, exists(hasTestTag("addField")))
        compose.onNodeWithTag("item:mleko").performClick()
        compose.waitForIdle()
        assertEquals(false, runBlocking { repo.loadState(listId).items.values.single().checked })
    }

    private fun shownItems(): List<String> = compose.onAllNodes(
        SemanticsMatcher("an item row") { node ->
            node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("item:") == true
        },
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("item:") }

    private fun chooseView(view: SortView) {
        compose.onNodeWithContentDescription(text(R.string.action_more)).performClick()
        compose.onNodeWithText(text(R.string.action_sort)).performClick()
        compose.onNodeWithTag("sort:${view.key}").performClick()
    }

    private companion object {
        /** A made-up uid: the repository is public (CLAUDE.md). */
        const val ANIA = "test-ania"
    }
}
