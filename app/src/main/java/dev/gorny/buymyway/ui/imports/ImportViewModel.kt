package dev.gorny.buymyway.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.imports.EatMyWayImport
import dev.gorny.buymyway.core.imports.ImportedItem
import dev.gorny.buymyway.core.imports.ImportedList
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.ListSummary
import dev.gorny.buymyway.data.ImportSummary
import dev.gorny.buymyway.data.ListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where an import is poured: a list that exists, or one made for it. */
sealed interface ImportTarget {
    data class NewList(val name: String) : ImportTarget

    data class Existing(val listId: String) : ImportTarget
}

/** One line of the preview, with a key of its own so removing one does not move the rest. */
data class ImportLine(val key: Int, val item: ImportedItem)

data class ImportUiState(
    val source: ImportedList,
    val lines: List<ImportLine>,
    val target: ImportTarget,
    /** The lists this user may add to; null until Room has answered. */
    val lists: List<ListSummary>?,
    val busy: Boolean = false,
    /** A Polish sentence when the import could not be made. */
    val failure: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * The import preview (PLAN.md Phase 8, task 3). The text is parsed once, here; nothing reaches
 * a list until „Dodaj" is tapped, and what it then writes is one batch of ops
 * ([ListRepository.importItems]).
 */
class ImportViewModel(
    text: String,
    private val repo: ListRepository,
) : ViewModel() {

    private val source: ImportedList = EatMyWayImport.parse(text)

    private val lines = MutableStateFlow(source.items.mapIndexed { index, item -> ImportLine(index, item) })
    private val target = MutableStateFlow<ImportTarget>(ImportTarget.NewList(source.suggestedName))
    private val busy = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)

    val state: StateFlow<ImportUiState> =
        combine(lines, target, repo.observeEditableLists(), busy, failure) { lines, target, lists, busy, failure ->
            ImportUiState(source, lines, target, lists, busy, failure)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ImportUiState(source, lines.value, target.value, null),
        )

    /** The heading a line is shown under, as a label rather than an id. */
    fun headingOf(item: ImportedItem): String? = when {
        item.categoryId != null -> BuiltinCategories.ALL.firstOrNull { it.first == item.categoryId }?.second
        else -> item.categoryName
    }

    fun remove(key: Int) {
        lines.value = lines.value.filterNot { it.key == key }
    }

    fun chooseNewList() {
        target.value = ImportTarget.NewList(source.suggestedName)
    }

    fun rename(name: String) {
        target.value = ImportTarget.NewList(name)
    }

    fun choose(listId: String) {
        target.value = ImportTarget.Existing(listId)
    }

    /**
     * Makes the import. [onDone] gets the list it landed in and what happened, so the screen can
     * open that list and say it. [readOnly] and [failed] are the sentences to show when it could
     * not be made; nothing is written in either case.
     */
    fun run(readOnly: String, failed: String, onDone: (String, ImportSummary) -> Unit) {
        val chosen = target.value
        val items = lines.value.map { it.item }
        if (busy.value || items.isEmpty()) return
        busy.value = true
        failure.value = null
        viewModelScope.launch {
            try {
                val listId = when (chosen) {
                    is ImportTarget.Existing -> chosen.listId
                    is ImportTarget.NewList -> repo.createList(chosen.name.ifBlank { EatMyWayImport.DEFAULT_NAME })
                }
                onDone(listId, repo.importItems(listId, items))
            } catch (e: CancellationException) {
                throw e
            } catch (_: ListRepository.ReadOnlyList) {
                failure.value = readOnly
            } catch (_: Exception) {
                failure.value = failed
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
