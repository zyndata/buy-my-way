package dev.gorny.buymyway.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.ListDetail
import dev.gorny.buymyway.core.model.Ordering
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

/** Null [detail] with [loading] false: the list is gone (deleted, here or elsewhere). */
data class ListUiState(val loading: Boolean, val detail: ListDetail?)

/** What the add bar shows under the field: names to complete, and the category proposal. */
data class AddBarState(
    val suggestions: List<String> = emptyList(),
    /** The category the single typed item will go to; null when nothing or several are typed. */
    val categoryId: String? = null,
    /** True when the user picked [categoryId] on the chip rather than taking the proposal. */
    val chosen: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModel(
    private val repo: ListRepository,
    val listId: String,
    private val dictionary: suspend (typed: String, limit: Int) -> List<String>,
    commitScope: CoroutineScope,
) : ViewModel() {

    val held = HeldDeletes(commitScope) { repo.deleteItem(it) }

    /** Items just ticked here, still shown in place (STATE.md decision 51). */
    private val lingering = MutableStateFlow<Set<String>>(emptySet())

    /** Sort keys of items being dragged, shown before Room has them. */
    private val movedKeys = MutableStateFlow<Map<String, Double>>(emptyMap())

    val state: StateFlow<ListUiState> = combine(
        repo.observeList(listId, lingering),
        held.hidden,
        movedKeys,
    ) { detail, hidden, moved ->
        ListUiState(loading = false, detail = detail?.let { present(it, hidden, moved) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListUiState(loading = true, detail = null))

    init {
        sweep()
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
        viewModelScope.launch {
            for (item in parsed) {
                val result = runCatching {
                    repo.addItem(listId, item.name, item.quantity, item.unit, categoryId = category)
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
        viewModelScope.launch {
            if (!item.checked) {
                lingering.update { it + item.id }
                runCatching { repo.setChecked(item.id, true) }
                delay(LINGER_MS)
                lingering.update { it - item.id }
            } else {
                lingering.update { it - item.id }
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

    // --- Reorder within a category --------------------------------------------------------

    /** Whether [toId] is an item of the same category to buy as [fromId]: the only valid drop. */
    fun canMove(fromId: String, toId: String): Boolean {
        val sections = state.value.detail?.sections ?: return false
        return sections.any { section -> section.items.any { it.id == fromId } && section.items.any { it.id == toId && !it.checked } }
    }

    fun move(fromId: String, toId: String) {
        val section = state.value.detail?.sections?.firstOrNull { s -> s.items.any { it.id == fromId } } ?: return
        val ids = section.items.map { it.id }
        val reordered = Ordering.move(section.items, ids.indexOf(fromId), ids.indexOf(toId))
        val index = reordered.indexOfFirst { it.id == fromId }
        val keys = reordered.map { it.sortKey }
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
        viewModelScope.launch {
            runCatching { repo.moveItem(itemId, key) }
            // Keep showing the new place until Room has it, so the row does not jump back.
            withTimeoutOrNull(SETTLE_MS) {
                repo.observeItem(itemId).first { it == null || it.sortKey == key }
            }
            movedKeys.update { it - itemId }
        }
    }

    /** Run when the list is opened: the once-a-day expiry (STATE.md decisions 36 and 42). */
    private fun sweep() {
        viewModelScope.launch { runCatching { repo.sweep(listId) } }
    }

    private fun present(detail: ListDetail, hidden: Set<String>, moved: Map<String, Double>): ListDetail {
        if (hidden.isEmpty() && moved.isEmpty()) return detail
        val sections = detail.sections.mapNotNull { section ->
            val items = section.items
                .filterNot { it.id in hidden }
                .map { item -> moved[item.id]?.let { item.copy(sortKey = it) } ?: item }
                .sortedWith(compareBy<Item>({ it.sortKey }, { it.createdAt }, { it.id }))
            if (items.isEmpty()) null else section.copy(items = items)
        }
        return detail.copy(sections = sections, bought = detail.bought.filterNot { it.id in hidden })
    }

    companion object {
        const val LINGER_MS = 800L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 1_000L
        private const val SUGGESTIONS = 6
        private const val MIN_SUGGEST = 2
    }
}
