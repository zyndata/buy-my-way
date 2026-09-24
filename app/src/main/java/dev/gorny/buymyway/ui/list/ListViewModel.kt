package dev.gorny.buymyway.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.ListDetail
import dev.gorny.buymyway.core.model.ListViews
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Ordering
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.parse.ItemParser
import dev.gorny.buymyway.core.text.TextKey
import dev.gorny.buymyway.core.voice.Dictation
import dev.gorny.buymyway.core.voice.VoiceError
import dev.gorny.buymyway.core.voice.VoiceEvent
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.photo.ItemPhotos
import dev.gorny.buymyway.data.photo.PhotoRef
import dev.gorny.buymyway.ui.common.HeldDeletes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.ImageBitmap
import java.io.InputStream

/**
 * Null [detail] with [loading] false: the list is gone (deleted, here or elsewhere, or no
 * longer shared). [remoteTicks] are items someone else just ticked, by the initial shown on
 * them while they linger (PLAN.md Phase 5, task 5).
 */
data class ListUiState(
    val loading: Boolean,
    val detail: ListDetail?,
    val role: Role = Role.OWNER,
    val members: Map<String, Member> = emptyMap(),
    val remoteTicks: Map<String, String> = emptyMap(),
) {
    val canEdit: Boolean get() = role != Role.VIEWER
}

/** What the add bar shows under the field: names to complete, and the category proposal. */
data class AddBarState(
    val suggestions: List<String> = emptyList(),
    /** The category the single typed item will go to; null when nothing or several are typed. */
    val categoryId: String? = null,
    /** True when the user picked [categoryId] on the chip rather than taking the proposal. */
    val chosen: Boolean = false,
)

/**
 * One thing that was dictated, as the review sheet holds it before anything is added
 * (PLAN.md Phase 7, task 3). [key] only tells two chips apart while the sheet is open.
 */
data class DictatedItem(
    val key: Long,
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val categoryId: String? = null,
    /** True once the user picked [categoryId] themselves, so a proposal no longer overrides it. */
    val chosen: Boolean = false,
    /** True when no dictionary knows this name yet, so „Zapamiętaj" is worth offering (decision 91). */
    val canRemember: Boolean = false,
    /** True once „Zapamiętaj" has put it in „Moje produkty". */
    val remembered: Boolean = false,
)

/** The review sheet: open from the first tap on the mic until „Dodaj wszystkie" or „Anuluj". */
data class DictationState(
    val open: Boolean = false,
    /** The microphone is on. */
    val listening: Boolean = false,
    /** Speech has ended and the recognizer is still working. */
    val thinking: Boolean = false,
    /** What is being heard right now; it is replaced by an item when the utterance ends. */
    val partial: String = "",
    val items: List<DictatedItem> = emptyList(),
    val error: VoiceError? = null,
) {
    val busy: Boolean get() = listening || thinking
}

/** What the list screen needs besides the repository; absent where it is tested alone. */
interface ListLive {
    /** The uid changes are made under, or null signed out. */
    suspend fun myUid(): String?

    /** Watches the list while collected (decision 65) and emits the uids looking at it. */
    fun watch(listId: String): Flow<Set<String>>

    fun sortView(listId: String): Flow<SortView>

    suspend fun setSortView(listId: String, view: SortView)

    /**
     * Whether this list is in front of the user right now (Phase 9). While it is, a push about
     * it says nothing: the live listeners have already brought the change to the screen.
     */
    fun onScreen(listId: String, open: Boolean) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModel(
    private val repo: ListRepository,
    val listId: String,
    private val dictionary: suspend (typed: String, limit: Int) -> List<String>,
    private val commitScope: CoroutineScope,
    private val live: ListLive? = null,
    private val photos: ItemPhotos? = null,
    /** The dictionary that says where one dictated item ends (Phase 7, STATE.md decision 78). */
    private val knownNames: suspend () -> Dictation.KnownNames = { Dictation.KnownNames.NONE },
) : ViewModel() {

    val held = HeldDeletes(commitScope) { repo.deleteItem(it) }

    /** Items just ticked here, still shown in place (STATE.md decision 51). */
    private val lingering = MutableStateFlow<Set<String>>(emptySet())

    /** Items someone else just ticked, held in place with their initial (PLAN.md Phase 5, task 5). */
    private val remoteLingering = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Keys of items being dragged (`sortKey`, or `manualKey` in „Ręcznie"), shown before Room has them. */
    private val movedKeys = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** „Ręcznie" positions a drag writes for items not placed yet (decision 67). */
    private var pendingPlacements: Map<String, Double> = emptyMap()

    private val view: Flow<SortView> = live?.sortView(listId) ?: flowOf(SortView.DEPARTMENTS)

    private val members: Flow<Map<String, Member>> = repo.observeMembers(listId).map { rows -> rows.associateBy { it.uid } }

    private val shown: Flow<ListDetail?> = repo.observeList(
        listId,
        combine(lingering, remoteLingering) { own, remote -> own + remote.keys },
        view,
    )

    /** Follows the owner and the members node, which is all a role depends on. */
    private val role: Flow<Role> = combine(shown.map { it?.list?.ownerUid }.distinctUntilChanged(), members) { _, _ ->
        repo.roleOf(listId) ?: Role.VIEWER
    }.distinctUntilChanged()

    val state: StateFlow<ListUiState> = combine(
        shown,
        combine(held.hidden, movedKeys) { hidden, moved -> hidden to moved },
        role,
        members,
        remoteLingering,
    ) { detail, (hidden, moved), role, members, remote ->
        ListUiState(
            loading = false,
            detail = detail?.let { present(it, hidden, moved) },
            role = role,
            members = members,
            remoteTicks = remote,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListUiState(loading = true, detail = null))

    /**
     * The names of the others looking at this list now („Ania ogląda"). Collecting this is
     * what keeps the listeners attached, so the screen collects it while it is shown.
     */
    val watching: StateFlow<List<String>> = (live?.watch(listId) ?: flowOf(emptySet()))
        .combine(members) { uids, known ->
            val me = live?.myUid()
            uids.filter { it != me }.map { uid -> displayName(known[uid]) }.sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        sweep()
        watchRemoteTicks()
    }

    /** The screen was resumed or left (Phase 9): while it is shown, a push about it is silent. */
    fun onScreen(open: Boolean) = live?.onScreen(listId, open) ?: Unit

    // --- Add bar --------------------------------------------------------------------------

    private val typed = MutableStateFlow("")
    private val chosenCategory = MutableStateFlow<String?>(null)

    private val suggestions: Flow<List<String>> = typed
        .map { ItemParser.lastName(it).trim() }
        .distinctUntilChanged()
        .flatMapLatest { name ->
            if (TextKey.fold(name).length < MIN_SUGGEST) {
                flowOf(emptyList())
            } else {
                repo.observeSuggestions(name, SUGGESTIONS).map { history ->
                    val fromDictionary = dictionary(name, SUGGESTIONS)
                    (history.map { it.name } + fromDictionary)
                        .distinctBy { TextKey.fold(it) }
                        .filterNot { TextKey.fold(it) == TextKey.fold(name) }
                        .take(SUGGESTIONS)
                        .map { suggestion -> suggestion.replaceFirstChar { it.uppercaseChar() } }
                }
            }
        }

    private val proposal: Flow<String?> = typed
        .map { text -> ItemParser.parseAll(text).singleOrNull()?.name }
        .distinctUntilChanged()
        .mapLatest { name -> name?.let { repo.proposeCategory(listId, it) } }

    val addBar: StateFlow<AddBarState> = combine(suggestions, proposal, chosenCategory, typed) { names, proposed, chosen, text ->
        val single = ItemParser.parseAll(text).size == 1
        AddBarState(
            suggestions = names,
            categoryId = if (single) chosen ?: proposed else null,
            chosen = single && chosen != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddBarState())

    /** Items that came back from „Kupione" instead of being added twice, for a snackbar. */
    private val revivedNames = Channel<String>(Channel.BUFFERED)
    val revived: Flow<String> = revivedNames.receiveAsFlow()

    fun onTyped(text: String) {
        typed.value = text
        if (text.isBlank()) chosenCategory.value = null
    }

    fun chooseCategory(categoryId: String) {
        chosenCategory.value = categoryId
    }

    /**
     * Adds everything [text] names („2 kg ziemniaki, mleko"). Returns false when it names
     * nothing, so the field keeps what was typed.
     */
    fun add(text: String): Boolean {
        val parsed = ItemParser.parseAll(text)
        if (parsed.isEmpty()) return false
        val category = if (parsed.size == 1) chosenCategory.value else null
        chosenCategory.value = null
        val placeLast = state.value.detail?.view == SortView.MANUAL
        viewModelScope.launch {
            for (item in parsed) {
                val result = runCatching {
                    repo.addItem(listId, item.name, item.quantity, item.unit, categoryId = category, placeLast = placeLast)
                }.getOrNull()
                if (result is ListRepository.AddResult.Revived) revivedNames.send(item.name)
            }
        }
        return true
    }

    // --- Dictation (Phase 7, task 3) ------------------------------------------------------

    private val _dictation = MutableStateFlow(DictationState())

    /** The review sheet's contents. Nothing here is on the list until „Dodaj wszystkie". */
    val dictation: StateFlow<DictationState> = _dictation

    private var dictatedKeys = 0L

    /** The mic in the add bar: opens the review sheet, which starts listening. */
    fun openDictation() {
        if (!state.value.canEdit) return
        dictatedKeys = 0
        _dictation.value = DictationState(open = true)
    }

    /** „Anuluj", or the sheet dismissed: everything heard is dropped. */
    fun closeDictation() {
        _dictation.value = DictationState()
    }

    /** Everything the recognizer says while the sheet is open. */
    fun onVoice(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Listening -> _dictation.update { it.copy(listening = true, thinking = false, partial = "", error = null) }
            is VoiceEvent.Partial -> _dictation.update { if (it.listening) it.copy(partial = event.text) else it }
            is VoiceEvent.Thinking -> _dictation.update { it.copy(listening = false, thinking = true) }
            is VoiceEvent.Heard -> {
                _dictation.update { it.copy(listening = false, thinking = false, partial = "") }
                heard(event.text)
            }
            is VoiceEvent.Failed ->
                _dictation.update { it.copy(listening = false, thinking = false, partial = "", error = event.error) }
        }
    }

    /** One finished utterance becomes chips, each with the category it would be filed under. */
    private fun heard(utterance: String) {
        viewModelScope.launch {
            val known = runCatching { knownNames() }.getOrDefault(Dictation.KnownNames.NONE)
            val parsed = Dictation.parse(utterance, known)
            val heard = parsed.map { item ->
                DictatedItem(
                    key = dictatedKeys++,
                    name = item.name,
                    quantity = item.quantity,
                    unit = item.unit,
                    categoryId = runCatching { repo.proposeCategory(listId, item.name) }.getOrNull(),
                    canRemember = isNew(item.name, known),
                )
            }
            _dictation.update { state ->
                // „Nie zrozumiałem" rather than a silent sheet when an utterance named nothing.
                if (heard.isEmpty() && state.items.isEmpty()) {
                    state.copy(error = VoiceError.NO_MATCH)
                } else {
                    state.copy(items = state.items + heard, error = if (heard.isEmpty()) VoiceError.NO_MATCH else null)
                }
            }
        }
    }

    /**
     * A chip edited by hand. [text] is read the way the add bar reads what is typed („2 kg
     * ziemniaki"), so a wrong quantity is corrected in the same field as a wrong name. The
     * category follows the new name unless the user chose one.
     */
    fun editDictated(key: Long, text: String) {
        val parsed = ItemParser.parse(text)
        _dictation.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.key == key) {
                        it.copy(name = parsed?.name ?: text.trim(), quantity = parsed?.quantity, unit = parsed?.unit)
                    } else {
                        it
                    }
                },
            )
        }
        val item = _dictation.value.items.firstOrNull { it.key == key } ?: return
        if (item.name.isBlank()) return
        viewModelScope.launch {
            val known = runCatching { knownNames() }.getOrDefault(Dictation.KnownNames.NONE)
            // A corrected line is a different name, so it may now be one worth remembering.
            val fresh = isNew(item.name, known)
            val proposed = if (item.chosen) null else runCatching { repo.proposeCategory(listId, item.name) }.getOrNull()
            _dictation.update { state ->
                state.copy(
                    items = state.items.map {
                        when {
                            it.key != key || it.name != item.name -> it
                            proposed == null || it.chosen -> it.copy(canRemember = fresh, remembered = false)
                            else -> it.copy(categoryId = proposed, canRemember = fresh, remembered = false)
                        }
                    },
                )
            }
        }
    }

    /** Whether no dictionary this phone has knows [name] whole, so it is worth curating. */
    private fun isNew(name: String, known: Dictation.KnownNames): Boolean {
        val words = TextKey.words(name)
        return words.isNotEmpty() && known.lengthAt(words, 0) < words.size
    }

    /**
     * „Zapamiętaj" on a dictated line (PLAN.md Phase 8b, task 2): the name, with the department
     * on its chip, joins „Moje produkty". Nothing else in the sheet writes anything.
     */
    fun rememberDictated(key: Long) {
        val item = _dictation.value.items.firstOrNull { it.key == key } ?: return
        val categoryId = item.categoryId ?: return
        if (item.name.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.setOwnProduct(item.name, categoryId) }.onSuccess {
                _dictation.update { state ->
                    state.copy(items = state.items.map { if (it.key == key) it.copy(remembered = true) else it })
                }
            }
        }
    }

    fun setDictatedCategory(key: Long, categoryId: String) {
        _dictation.update { state ->
            state.copy(items = state.items.map { if (it.key == key) it.copy(categoryId = categoryId, chosen = true) else it })
        }
    }

    fun removeDictated(key: Long) {
        _dictation.update { state -> state.copy(items = state.items.filterNot { it.key == key }) }
    }

    /** „Dodaj wszystkie": the chips become items, in the order they were said. */
    fun addDictated() {
        val heard = _dictation.value.items.filter { it.name.isNotBlank() }
        _dictation.value = DictationState()
        if (heard.isEmpty()) return
        val placeLast = state.value.detail?.view == SortView.MANUAL
        viewModelScope.launch {
            for (item in heard) {
                val result = runCatching {
                    repo.addItem(listId, item.name, item.quantity, item.unit, categoryId = item.categoryId, placeLast = placeLast)
                }.getOrNull()
                if (result is ListRepository.AddResult.Revived) revivedNames.send(item.name)
            }
        }
    }

    // --- Rows -----------------------------------------------------------------------------

    /**
     * A tap on a row. Ticking writes at once and keeps the row in place, struck through, for
     * [LINGER_MS] before it moves to „Kupione"; a tap in „Kupione" brings it back.
     */
    fun toggle(item: Item) {
        if (!state.value.canEdit) return
        viewModelScope.launch {
            if (!item.checked) {
                lingering.update { it + item.id }
                runCatching { repo.setChecked(item.id, true) }
                delay(LINGER_MS)
                lingering.update { it - item.id }
            } else {
                lingering.update { it - item.id }
                remoteLingering.update { it - item.id }
                runCatching { repo.setChecked(item.id, false) }
            }
        }
    }

    fun setAllChecked(checked: Boolean) {
        lingering.value = emptySet()
        viewModelScope.launch { runCatching { repo.setAllChecked(listId, checked) } }
    }

    fun clearChecked() {
        viewModelScope.launch { runCatching { repo.clearChecked(listId) } }
    }

    fun rename(name: String) {
        viewModelScope.launch { runCatching { repo.renameList(listId, name) } }
    }

    fun update(itemId: String, content: ItemContent) {
        viewModelScope.launch { runCatching { repo.updateItem(itemId, content) } }
    }

    fun delete(itemId: String, message: String, undoLabel: String) = held.hold(viewModelScope, itemId, message, undoLabel)

    // --- Photos (Phase 6, decision 71) ----------------------------------------------------

    /** Photos set here and not sent yet (item id → `at`): a row shows these first. */
    val pendingPhotos: StateFlow<Map<String, Long>> = (photos?.pending ?: flowOf(emptyMap()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Items whose new photo is being prepared, for a progress indicator. */
    private val _photoBusy = MutableStateFlow<Set<String>>(emptySet())
    val photoBusy: StateFlow<Set<String>> = _photoBusy

    private val photoErrors = Channel<Unit>(Channel.CONFLATED)

    /** A chosen image could not be read or turned into a photo. */
    val photoFailed: Flow<Unit> = photoErrors.receiveAsFlow()

    /** Whether photos can be shown at all (not in a test without them). */
    val hasPhotos: Boolean get() = photos != null

    /** The photo a row shows: one waiting to be sent, else the item's `photoAt`. */
    fun photoOf(item: Item, pending: Map<String, Long>): PhotoRef? = PhotoRef.of(listId, item.id, item.photoAt, pending[item.id])

    suspend fun loadPhoto(ref: PhotoRef, maxPx: Int): ImageBitmap? = photos?.load(ref, maxPx)

    /**
     * A photo from the camera or the gallery. It is prepared in the app's scope, so leaving the
     * screen does not lose it; [done] runs afterwards either way (the camera's file is deleted).
     */
    fun setPhoto(itemId: String, open: () -> InputStream, done: () -> Unit = {}) {
        val photos = photos ?: return done()
        if (!state.value.canEdit) return done()
        _photoBusy.update { it + itemId }
        commitScope.launch {
            try {
                photos.set(listId, itemId, open)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                photoErrors.trySend(Unit) // not an image, or the file could not be read
            } finally {
                _photoBusy.update { it - itemId }
                done()
            }
        }
    }

    fun removePhoto(itemId: String) {
        val photos = photos ?: return
        if (!state.value.canEdit) return
        commitScope.launch { runCatching { photos.remove(listId, itemId) } }
    }

    // --- Sorting (decision 67) ------------------------------------------------------------

    /** „Sortowanie": this user's view of this list; „Ręcznie" places what is not placed yet. */
    fun setView(view: SortView) {
        viewModelScope.launch {
            live?.setSortView(listId, view)
            if (view == SortView.MANUAL) runCatching { repo.placeAllManually(listId) }
        }
    }

    // --- Reorder: within a category, or anywhere in „Ręcznie" -----------------------------

    /** Whether [toId] is an item to buy in the same section as [fromId]: the only valid drop. */
    fun canMove(fromId: String, toId: String): Boolean {
        val sections = state.value.detail?.sections ?: return false
        return sections.any { section -> section.items.any { it.id == fromId } && section.items.any { it.id == toId && !it.checked } }
    }

    fun move(fromId: String, toId: String) {
        val detail = state.value.detail ?: return
        val section = detail.sections.firstOrNull { s -> s.items.any { it.id == fromId } } ?: return
        val manual = detail.view == SortView.MANUAL
        if (manual && pendingPlacements.isEmpty()) pendingPlacements = ListViews.placements(section.items)
        val ids = section.items.map { it.id }
        val reordered = Ordering.move(section.items, ids.indexOf(fromId), ids.indexOf(toId))
        val index = reordered.indexOfFirst { it.id == fromId }
        val keys = reordered.map { if (manual) it.manualKey ?: pendingPlacements[it.id] ?: 0.0 else it.sortKey }
        movedKeys.update { it + (fromId to Ordering.sortKeyAt(keys, index)) }
    }

    /** TalkBack's „Przesuń wyżej / niżej". */
    fun moveBy(itemId: String, delta: Int) {
        val items = state.value.detail?.sections?.firstOrNull { s -> s.items.any { it.id == itemId } }?.items ?: return
        val target = items.getOrNull(items.indexOfFirst { it.id == itemId } + delta) ?: return
        move(itemId, target.id)
        drop(itemId)
    }

    fun drop(itemId: String) {
        val key = movedKeys.value[itemId] ?: return
        val manual = state.value.detail?.view == SortView.MANUAL
        val placements = pendingPlacements - itemId
        pendingPlacements = emptyMap()
        viewModelScope.launch {
            runCatching {
                if (manual) {
                    repo.placeManually(listId, placements)
                    repo.moveItemManually(itemId, key)
                } else {
                    repo.moveItem(itemId, key)
                }
            }
            // Keep showing the new place until Room has it, so the row does not jump back.
            withTimeoutOrNull(SETTLE_MS) {
                repo.observeItem(itemId).first { it == null || (if (manual) it.manualKey else it.sortKey) == key }
            }
            movedKeys.update { it - itemId }
        }
    }

    /** Run when the list is opened: the once-a-day expiry (STATE.md decisions 36 and 42). */
    private fun sweep() {
        viewModelScope.launch { runCatching { repo.sweep(listId) } }
    }

    /**
     * Someone else's tick (PLAN.md *Screens*): the row stays where it was, struck through with
     * their initial, for [REMOTE_LINGER_MS], then slides into „Kupione". Only ticks that arrive
     * while the screen is open, never what was already ticked when it opened.
     */
    private fun watchRemoteTicks() {
        viewModelScope.launch {
            try {
                watchTicks(live?.myUid())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A database that cannot be read any more must not take the app down with it;
                // someone else's tick simply stops being animated until the screen is reopened.
            }
        }
    }

    private suspend fun watchTicks(me: String?) = coroutineScope {
        var before: Map<String, Item>? = null
        repo.observeItems(listId).collect { items ->
            val previous = before
            before = items.associateBy { it.id }
            if (previous == null) return@collect
            for (item in items) {
                val old = previous[item.id]
                val newlyTicked = item.checked && (old == null || !old.checked)
                if (!newlyTicked || item.checkedBy == null || item.checkedBy == me) continue
                val initial = initialOf(item.checkedBy)
                remoteLingering.update { it + (item.id to initial) }
                launch {
                    delay(REMOTE_LINGER_MS)
                    remoteLingering.update { it - item.id }
                }
            }
        }
    }

    private suspend fun initialOf(uid: String): String {
        val member = repo.observeMembers(listId).first().firstOrNull { it.uid == uid }
        return displayName(member).firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }

    private fun present(detail: ListDetail, hidden: Set<String>, moved: Map<String, Double>): ListDetail {
        if (hidden.isEmpty() && moved.isEmpty()) return detail
        val manual = detail.view == SortView.MANUAL
        val sections = detail.sections.mapNotNull { section ->
            val items = section.items
                .filterNot { it.id in hidden }
                .map { item -> moved[item.id]?.let { if (manual) item.copy(manualKey = it) else item.copy(sortKey = it) } ?: item }
                .let { list ->
                    if (manual) {
                        list.sortedWith(compareBy<Item>({ it.manualKey ?: pendingPlacements[it.id] ?: Double.MAX_VALUE }))
                    } else {
                        list.sortedWith(compareBy<Item>({ it.sortKey }, { it.createdAt }, { it.id }))
                    }
                }
            if (items.isEmpty()) null else section.copy(items = items)
        }
        return detail.copy(sections = sections, bought = detail.bought.filterNot { it.id in hidden })
    }

    companion object {
        const val LINGER_MS = 1_500L

        /** How long someone else's tick holds before it slides (PLAN.md *Screens*). */
        const val REMOTE_LINGER_MS = 2_500L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 1_000L
        private const val SUGGESTIONS = 6
        private const val MIN_SUGGEST = 2

        /** A member as the screens name them: their name, else their e-mail, else nothing. */
        fun displayName(member: Member?): String = member?.name?.takeIf { it.isNotBlank() } ?: member?.email.orEmpty()
    }
}
