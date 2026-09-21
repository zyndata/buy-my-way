package dev.gorny.buymyway.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.model.CategoryInfo
import dev.gorny.buymyway.core.model.Ordering
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Adding, renaming and deleting a list's own categories; null where there is no list. */
class CategoryEdits(
    val add: suspend (name: String) -> Unit,
    val rename: suspend (categoryId: String, name: String) -> Unit,
    val delete: suspend (categoryId: String) -> Unit,
)

/**
 * The category order editor: a list's walk order (Lista → „Kolejność kategorii") or the default
 * order for new lists (Ustawienia). Both are an ordered set of categories and a way to save it.
 */
class CategoryOrderViewModel(
    private val categories: Flow<List<CategoryInfo>?>,
    private val save: suspend (List<String>) -> Unit,
    val edits: CategoryEdits?,
) : ViewModel() {

    /** The order while a row is being dragged; null once the saved order has caught up. */
    private val working = MutableStateFlow<List<String>?>(null)

    /** Null until loaded, or when the list is gone. */
    val state: StateFlow<List<CategoryInfo>?> = combine(categories, working) { saved, order ->
        if (saved == null) return@combine null
        if (order == null) return@combine saved
        val byId = saved.associateBy { it.id }
        order.mapNotNull(byId::get) + saved.filter { it.id !in order }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun move(fromId: String, toId: String) {
        val ids = current()
        working.value = Ordering.move(ids, ids.indexOf(fromId), ids.indexOf(toId))
    }

    fun moveBy(categoryId: String, delta: Int) {
        val ids = current()
        val from = ids.indexOf(categoryId)
        if (from < 0 || from + delta !in ids.indices) return
        working.value = Ordering.move(ids, from, from + delta)
        drop()
    }

    fun drop() {
        val order = working.value ?: return
        viewModelScope.launch {
            runCatching { save(order) }
            // Keep showing the new order until the saved one arrives, so nothing jumps back.
            withTimeoutOrNull(SETTLE_MS) { categories.first { saved -> saved == null || saved.map { it.id } == order } }
            working.compareAndSet(order, null)
        }
    }

    fun add(name: String) = edit { it.add(name) }

    fun rename(categoryId: String, name: String) = edit { it.rename(categoryId, name) }

    fun delete(categoryId: String) = edit { it.delete(categoryId) }

    private fun edit(block: suspend (CategoryEdits) -> Unit) {
        val e = edits ?: return
        viewModelScope.launch { runCatching { block(e) } }
    }

    private fun current(): List<String> = working.value ?: state.value.orEmpty().map { it.id }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_000L
    }
}
