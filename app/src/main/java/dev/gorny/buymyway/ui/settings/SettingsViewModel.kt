package dev.gorny.buymyway.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.auth.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ustawienia's account section (PLAN.md Phase 4, task 1; STATE.md decision 57). */
class SettingsViewModel(
    account: Flow<AccountState>,
    pendingOps: Flow<Int>,
    private val signInWith: suspend (Context) -> SignInResult,
    private val signOutAll: suspend () -> Unit,
) : ViewModel() {

    val account: StateFlow<AccountState> =
        account.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountState.Loading)

    /** Changes made on this phone that RTDB has not acknowledged yet. */
    val pending: StateFlow<Int> = pendingOps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** [activity] must be the current Activity (Credential Manager shows its sheet over it). */
    fun signIn(activity: Context, onResult: (SignInResult) -> Unit) = run { onResult(signInWith(activity)) }

    fun signOut() = run { signOutAll() }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
