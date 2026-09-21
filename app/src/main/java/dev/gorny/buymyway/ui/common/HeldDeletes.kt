package dev.gorny.buymyway.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Deletes held back while their „Cofnij" snackbar is open (STATE.md decisions 43 and 51).
 *
 * [hold] hides the thing at once and shows the snackbar in the caller's scope (a view model's).
 * „Cofnij" shows it again; any other way the snackbar closes (its timeout, the next delete
 * replacing it, the scope ending because the user left the screen) commits the delete in
 * [commitScope], which outlives the screen. Only the process dying loses a held delete, and then
 * the thing is simply still there.
 */
class HeldDeletes(
    private val commitScope: CoroutineScope,
    private val commit: suspend (String) -> Unit,
) {
    val snackbar = SnackbarHostState()

    private val hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = hiddenIds.asStateFlow()

    fun hold(scope: CoroutineScope, id: String, message: String, undoLabel: String) {
        hiddenIds.update { it + id }
        // One snackbar at a time: the delete it was offering is committed now.
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar(
                    message = message,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Long,
                ) == SnackbarResult.ActionPerformed
            } finally {
                if (undone) {
                    hiddenIds.update { it - id }
                } else {
                    commitScope.launch {
                        // Already gone (deleted elsewhere) is as good as deleted.
                        runCatching { commit(id) }
                        hiddenIds.update { it - id }
                    }
                }
            }
        }
    }
}
