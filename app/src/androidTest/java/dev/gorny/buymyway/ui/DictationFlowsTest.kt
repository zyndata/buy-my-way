package dev.gorny.buymyway.ui

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
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.categorize.NameIndex
import dev.gorny.buymyway.core.voice.Dictation
import dev.gorny.buymyway.core.voice.VoiceError
import dev.gorny.buymyway.core.voice.VoiceEvent
import dev.gorny.buymyway.core.voice.VoiceSource
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.ui.list.AddBar
import dev.gorny.buymyway.ui.list.AddBarState
import dev.gorny.buymyway.ui.list.ListScreen
import dev.gorny.buymyway.ui.list.ListViewModel
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dictation on the real screens (PLAN.md Phase 7, task 3). No microphone is opened: the screen
 * is given a [VoiceSource] that says nothing on its own, and the utterances a phone would have
 * heard are handed to the view model as [VoiceEvent]s — everything the review sheet ever sees.
 * What a real recognizer does is the owner's by-hand check.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DictationFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun open() {
        // The sheet refuses to listen without it, exactly as it does on a phone; an install for
        // a test run grants nothing by itself.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
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
        // Cleared as a screen leaving the stack would, so no flow outlives the database.
        store.clear()
        scope.cancel()
        db.close()
    }

    private val store = ViewModelStore()

    private fun <T : ViewModel> kept(vm: T): T = vm.also { store.put(it.hashCode().toString(), it) }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun items(listId: String): List<Item> = runBlocking { repo.loadState(listId).items.values.toList() }

    /** A recognizer that opens no microphone and never says anything by itself. */
    private class SilentVoice : VoiceSource {
        var listening = 0
        var released = 0

        override fun start(onEvent: (VoiceEvent) -> Unit) {
            listening++
        }

        override fun stop() = Unit

        override fun release() {
            released++
        }
    }

    private val voice = SilentVoice()

    /** What the screen's dictionaries know, as `AppContainer.knownNames` would hand it over. */
    private var known: Dictation.KnownNames = Dictation.KnownNames.NONE

    private fun showList(listId: String): ListViewModel {
        val vm = kept(ListViewModel(repo, listId, { _, _ -> emptyList() }, scope, knownNames = { known }))
        compose.setContent { BuyMyWayTheme { ListScreen(vm, onBack = {}, onOpenCategoryOrder = {}, onOpenShare = {}, voice = voice) } }
        waitFor { exists(hasTestTag("addField")) }
        return vm
    }

    /** Opens the review sheet and hands it one finished utterance. */
    private fun dictate(vm: ListViewModel, utterance: String) {
        compose.runOnUiThread { vm.openDictation() }
        waitFor { exists(hasTestTag("dictationSheet")) }
        compose.runOnUiThread { vm.onVoice(VoiceEvent.Heard(utterance)) }
    }

    /**
     * The acceptance criterion: „dwa kilo ziemniaków, mleko, masło i chleb" becomes four items
     * with the right quantity, unit and category, after one confirmation tap — and nothing at
     * all reaches the list before that tap.
     */
    @Test
    fun oneUtteranceBecomesFourItemsAfterOneTap() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "dwa kilo ziemniaków, mleko, masło i chleb")
        waitFor { exists(hasTestTag("dictated:chleb")) }
        // All four are in the sheet; on a small screen the lines scroll and the buttons do not.
        assertEquals(true, exists(hasTestTag("dictated:ziemniaków")))
        assertEquals(true, exists(hasTestTag("dictated:mleko")))
        assertEquals(true, exists(hasTestTag("dictated:masło")))
        compose.onNodeWithTag("addAll").assertIsDisplayed()
        // The proposed categories are shown before anything is added.
        assertEquals(true, exists(hasText("Nabiał i jaja")))
        assertEquals("dictation never writes directly", emptyList<Item>(), items(listId))

        compose.onNodeWithTag("addAll").performClick()
        waitFor { items(listId).size == 4 }

        val added = items(listId).associateBy { it.name }
        assertEquals(setOf("ziemniaków", "mleko", "masło", "chleb"), added.keys)
        assertEquals(2.0, added.getValue("ziemniaków").quantity!!, 0.0)
        assertEquals("kg", added.getValue("ziemniaków").unit)
        assertEquals("warzywa", added.getValue("ziemniaków").categoryId)
        assertEquals("nabial", added.getValue("mleko").categoryId)
        assertEquals(null, added.getValue("chleb").quantity)
        // The sheet is gone and the rows are on the list.
        waitFor { !exists(hasTestTag("dictationSheet")) }
        waitFor { exists(hasTestTag("item:chleb")) }
    }

    /** „Dyktuj dalej": a second utterance adds to the first, and both go on at once. */
    @Test
    fun aSecondUtteranceAddsToTheSameSheet() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "mleko")
        waitFor { exists(hasTestTag("dictated:mleko")) }
        // The sheet started listening by itself, and now offers „Dyktuj dalej".
        assertEquals(1, voice.listening)
        waitFor { exists(hasText(text(R.string.action_dictate_more))) }
        compose.onNodeWithText(text(R.string.action_dictate_more)).performClick()
        waitFor { voice.listening == 2 }
        compose.runOnUiThread { vm.onVoice(VoiceEvent.Heard("dwa chleby")) }
        waitFor { exists(hasTestTag("dictated:chleby")) }

        compose.onNodeWithTag("addAll").performClick()
        waitFor { items(listId).size == 2 }
        assertEquals(2.0, items(listId).single { it.name == "chleby" }.quantity!!, 0.0)
    }

    /** A line is corrected by hand, quantity and all, and its category follows the new name. */
    @Test
    fun aLineIsEditedInTheSheetBeforeItIsAdded() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "chleb")
        waitFor { exists(hasTestTag("dictated:chleb")) }
        compose.onNodeWithTag("dictatedField:0").performTextReplacement("2 l mleko")
        // „Gotowe" as a person taps it: with the keyboard up there is no room for the buttons.
        compose.onNodeWithTag("dictatedField:0").performImeAction()
        waitFor { exists(hasTestTag("dictated:mleko")) }
        // The proposal followed the new name (the test categoriser files „mleko" under „Nabiał").
        waitFor { exists(hasText("Nabiał i jaja")) }

        compose.onNodeWithTag("addAll").performClick()
        waitFor { items(listId).size == 1 }
        val item = items(listId).single()
        assertEquals("mleko", item.name)
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals("l", item.unit)
        assertEquals("nabial", item.categoryId)
    }

    /** The category chip: a proposal the user disagrees with is changed on the chip. */
    @Test
    fun aCategoryIsChangedOnTheChip() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "mleko")
        waitFor { exists(hasTestTag("dictatedCategory:0")) }
        compose.onNodeWithTag("dictatedCategory:0").performClick()
        waitFor { exists(hasText("Napoje")) }
        compose.onNodeWithText("Napoje").performClick()

        compose.onNodeWithTag("addAll").performClick()
        waitFor { items(listId).size == 1 }
        assertEquals("napoje", items(listId).single().categoryId)
    }

    /** A line dropped in the sheet never reaches the list. */
    @Test
    fun aLineIsRemovedBeforeAdding() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "mleko i chleb")
        waitFor { exists(hasTestTag("dictated:chleb")) }
        compose.onNodeWithContentDescription(text(R.string.action_remove_dictated, "chleb")).performClick()
        waitFor { !exists(hasTestTag("dictated:chleb")) }

        compose.onNodeWithTag("addAll").performClick()
        waitFor { items(listId).size == 1 }
        assertEquals("mleko", items(listId).single().name)
    }

    /** „Anuluj" drops everything that was heard. */
    @Test
    fun cancellingTheSheetAddsNothing() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "mleko i chleb")
        waitFor { exists(hasTestTag("dictated:chleb")) }
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        waitFor { !exists(hasTestTag("dictationSheet")) }
        // The microphone is given back with the sheet.
        waitFor { voice.released == 1 }

        compose.waitForIdle()
        assertEquals(emptyList<Item>(), items(listId))
        // And the next dictation starts empty.
        dictate(vm, "masło")
        waitFor { exists(hasTestTag("dictated:masło")) }
        assertEquals(false, exists(hasTestTag("dictated:chleb")))
    }

    /** Every recognizer error becomes one Polish sentence in the sheet. */
    @Test
    fun anErrorIsSaidInOneSentence() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        compose.runOnUiThread { vm.openDictation() }
        waitFor { exists(hasTestTag("dictationSheet")) }
        compose.runOnUiThread { vm.onVoice(VoiceEvent.Failed(VoiceError.NO_MATCH)) }
        waitFor { exists(hasTestTag("voiceError")) }
        compose.onNodeWithText(text(R.string.voice_error_no_match)).assertIsDisplayed()

        // An utterance that names nothing („dwa kilo" alone) says so rather than adding nothing.
        compose.runOnUiThread { vm.onVoice(VoiceEvent.Heard("dwa kilo")) }
        compose.waitForIdle()
        assertEquals(false, exists(hasTestTag("addAll") and hasText(text(R.string.action_add_all))) && items(listId).isNotEmpty())
        compose.onNodeWithText(text(R.string.voice_error_no_match)).assertIsDisplayed()
    }

    /** What is heard while speaking is shown, and it is not an item yet. */
    @Test
    fun partialWordsAreShownWhileSpeaking() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        compose.runOnUiThread { vm.openDictation() }
        waitFor { exists(hasTestTag("dictationSheet")) }
        compose.runOnUiThread {
            vm.onVoice(VoiceEvent.Listening)
            vm.onVoice(VoiceEvent.Partial("dwa kilo ziem"))
        }
        waitFor { exists(hasTestTag("partial")) }
        compose.onNodeWithText("dwa kilo ziem").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dictation_listening)).assertIsDisplayed()
        assertEquals(emptyList<Item>(), items(listId))

        compose.runOnUiThread { vm.onVoice(VoiceEvent.Heard("dwa kilo ziemniaków")) }
        waitFor { exists(hasTestTag("dictated:ziemniaków")) }
        waitFor { !exists(hasTestTag("partial")) }
    }

    /** Task 4: a phone whose recognizer is missing gets no mic button at all. */
    @Test
    fun theMicButtonIsThereOnlyWhenThereIsARecognizer() {
        var tapped = 0
        compose.setContent {
            BuyMyWayTheme {
                androidx.compose.foundation.layout.Column {
                    AddBar(
                        state = AddBarState(),
                        categories = emptyList(),
                        onTyped = {},
                        onChooseCategory = {},
                        onAdd = { true },
                        onMic = { tapped++ },
                    )
                    AddBar(
                        state = AddBarState(),
                        categories = emptyList(),
                        onTyped = {},
                        onChooseCategory = {},
                        onAdd = { true },
                        onMic = null,
                    )
                }
            }
        }
        waitFor { exists(hasTestTag("mic")) }

        assertEquals("one bar has a mic, the other has none", 1, compose.onAllNodes(hasTestTag("mic")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("mic").performClick()
        compose.waitForIdle()
        assertEquals(1, tapped)
    }

    /**
     * „Zapamiętaj" (PLAN.md Phase 8b, task 2): it is offered only on a line no dictionary here
     * knows (STATE.md decision 91), one tap puts that name in „Moje produkty" with the
     * department on its chip, and nothing else in the sheet writes anything anywhere.
     */
    @Test
    fun zapamietajStoresOnlyTheNameNoDictionaryKnows() {
        val listId = runBlocking { repo.createList("Sobota") }
        known = NameIndex.of(listOf("mleko")).let { index ->
            Dictation.KnownNames { words, from -> index.lengthAt(words, from) }
        }
        val vm = showList(listId)

        dictate(vm, "mleko, chleb wiejski")
        waitFor { exists(hasTestTag("dictated:chleb wiejski")) }

        // The word the dictionary knows is offered nothing; the one it does not is.
        assertEquals(false, exists(hasTestTag("remember:0")))
        waitFor { exists(hasTestTag("remember:1")) }
        assertEquals(emptyList<Pair<String, String>>(), products())

        compose.onNodeWithTag("remember:1").performClick()
        waitFor { products().isNotEmpty() }

        // Stored with the department the chip showed, and the line says so.
        assertEquals(listOf("chleb wiejski" to "warzywa"), products())
        waitFor { exists(hasTestTag("remembered:1")) }
        assertEquals(false, exists(hasTestTag("remember:1")))
        // „Zapamiętaj" is not „Dodaj": the list is still empty.
        assertEquals(emptyList<Item>(), items(listId))

        compose.onNodeWithTag("addAll").performClick()
        waitFor { items(listId).size == 2 }
        // And adding the items adds nothing more to „Moje produkty" (task 2: never on its own).
        assertEquals(listOf("chleb wiejski" to "warzywa"), products())
    }

    /** The department the user picks on the chip is the one „Zapamiętaj" stores. */
    @Test
    fun zapamietajTakesTheDepartmentFromTheChip() {
        val listId = runBlocking { repo.createList("Sobota") }
        val vm = showList(listId)

        dictate(vm, "chleb wiejski")
        waitFor { exists(hasTestTag("remember:0")) }
        compose.onNodeWithTag("dictatedCategory:0").performClick()
        compose.onNodeWithText("Pieczywo").performClick()
        waitFor { exists(hasText("Pieczywo")) }

        compose.onNodeWithTag("remember:0").performClick()
        waitFor { products().isNotEmpty() }
        assertEquals(listOf("chleb wiejski" to "pieczywo"), products())
    }

    private fun products() = runBlocking { repo.observeOwnProducts().first().map { it.name to it.categoryId } }
}
