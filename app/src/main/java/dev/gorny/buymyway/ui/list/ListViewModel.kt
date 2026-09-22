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
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.ui.common.HeldDeletes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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

/** What the list screen needs besides the repository; absent where it is tested alone. */
interface ListLive {
    /** The uid changes are made under, or null signed out. */
    suspend fun myUid(): String?

    /** Watches the list while collected (decision 65) and emits the uids looking at it. */
    fun watch(listId: String): Flow<Set<String>>

    fun sortView(listId: String): Flow<SortView>

    suspend fun setSortView(listId: String, view: SortView)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModel(
    private val repo: ListRepository,
    val listId: String,
    private val dictionary: suspend (typed: String, limit: Int) -> List<String>,
    commitScope: CoroutineScope,
    private val live: ListLive? = null,
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
            val me = live?.myUid()
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
        const val LINGER_MS = 800L

        /** How long someone else's tick holds before it slides (PLAN.md *Screens*). */
        const val REMOTE_LINGER_MS = 1_500L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 1_000L
        private const val SUGGESTIONS = 6
        private const val MIN_SUGGEST = 2

        /** A member as the screens name them: their name, else their e-mail, else nothing. */
        fun displayName(member: Member?): String = member?.name?.takeIf { it.isNotBlank() } ?: member?.email.orEmpty()
    }
}
