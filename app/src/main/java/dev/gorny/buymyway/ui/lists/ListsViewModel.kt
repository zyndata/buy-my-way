package dev.gorny.buymyway.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.model.ListSummary
import dev.gorny.buymyway.core.model.Ordering
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.ui.common.HeldDeletes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Null until Room has answered, so the empty state is not flashed on every start. */
data class ListsUiState(val lists: List<ListSummary>?)

class ListsViewModel(
    private val repo: ListRepository,
    private val order: ListOrderPreferences,
    commitScope: CoroutineScope,
) : ViewModel() {

    val held = HeldDeletes(commitScope) { repo.deleteList(it) }

    /** The order while a card is being dragged; saved to DataStore when it is dropped. */
    private val dragOrder = MutableStateFlow<List<String>?>(null)

    val state: StateFlow<ListsUiState> = combine(repo.observeLists(), order.order, held.hidden, dragOrder) { lists, saved, hidden, dragging ->
        ListsUiState(Ordering.byIds(lists.filterNot { it.list.id in hidden }, dragging ?: saved) { it.list.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListsUiState(null))

    /** Creates the list and hands its id to [then] (the screen opens it). */
    fun create(name: String, then: (String) -> Unit) {
        viewModelScope.launch { then(repo.createList(name)) }
    }

    fun rename(listId: String, name: String) {
        viewModelScope.launch { runCatching { repo.renameList(listId, name) } }
    }

    fun delete(listId: String, message: String, undoLabel: String) = held.hold(viewModelScope, listId, message, undoLabel)

    fun move(fromId: String, toId: String) {
        val ids = current()
        dragOrder.value = Ordering.move(ids, ids.indexOf(fromId), ids.indexOf(toId))
    }

    /** TalkBack's „Przesuń wyżej / niżej": a move and a drop in one. */
    fun moveBy(listId: String, delta: Int) {
        val ids = current()
        val from = ids.indexOf(listId)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        dragOrder.value = Ordering.move(ids, from, to)
        drop()
    }

    fun drop() {
        val ids = dragOrder.value ?: return
        viewModelScope.launch {
            order.setOrder(ids)
            // Keep showing the new order until DataStore has it, so nothing jumps back.
            withTimeoutOrNull(SETTLE_MS) { order.order.first { it == ids } }
            dragOrder.compareAndSet(ids, null)
        }
    }

    private fun current(): List<String> = dragOrder.value ?: state.value.lists.orEmpty().map { it.list.id }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_000L
    }
}
