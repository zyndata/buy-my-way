package dev.gorny.buymyway.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.data.OwnProduct
import dev.gorny.buymyway.ui.common.HeldDeletes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Ustawienia → „Moje produkty" (PLAN.md Phase 8b, task 3): the words the user curated, with the
 * department each belongs to. Nothing lands here on its own (STATE.md decision 81) — this
 * screen and „Zapamiętaj" in the dictation sheet are the only two ways in.
 *
 * A delete is held back while its „Cofnij" snackbar is open, as everywhere else (decision 43),
 * so „Cofnij" simply never writes: the row is hidden and the tombstone is made when the
 * snackbar closes.
 */
class OwnProductsViewModel(
    products: Flow<List<OwnProduct>>,
    private val store: suspend (name: String, categoryId: String, replacing: String?) -> Unit,
    delete: suspend (key: String) -> Unit,
    commitScope: CoroutineScope,
) : ViewModel() {

    val held = HeldDeletes(commitScope) { delete(it) }

    /** Null until Room has answered, so the screen does not flash its empty state. */
    val state: StateFlow<List<OwnProduct>?> = combine(products, held.hidden) { rows, hidden ->
        rows.filterNot { it.key in hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Adds a product, or replaces [replacing] when a rename gives it a different key. */
    fun save(name: String, categoryId: String, replacing: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch { runCatching { store(name, categoryId, replacing) } }
    }

    /** „Usuń": the row goes now, the tombstone is written when the snackbar closes. */
    fun delete(key: String, message: String, undoLabel: String) = held.hold(viewModelScope, key, message, undoLabel)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
