package dev.gorny.buymyway.ui.share

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.share.Sharing
import dev.gorny.buymyway.data.share.Sharing.SharingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface InviteUiState {
    data object Loading : InviteUiState

    /** The invite is read only by a signed-in user (the rules), so sign in first. */
    data object NeedsSignIn : InviteUiState

    data class Ready(val invite: Sharing.Invite, val joining: Boolean = false) : InviteUiState

    data class Failed(@param:StringRes val message: Int) : InviteUiState

    data class Joined(val listId: String) : InviteUiState
}

/** An invite link opened in the app (PLAN.md *Sharing & permissions*). */
class InviteViewModel(private val token: String, private val sharing: Sharing, account: Flow<AccountState>) : ViewModel() {
    private val _state = MutableStateFlow<InviteUiState>(InviteUiState.Loading)
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            account.map { Pair(it is AccountState.SignedIn, it != AccountState.Loading) }.distinctUntilChanged().collect { (signedIn, settled) ->
                when {
                    !settled -> Unit
                    !signedIn -> _state.value = InviteUiState.NeedsSignIn
                    _state.value is InviteUiState.NeedsSignIn || _state.value is InviteUiState.Loading -> load()
                }
            }
        }
    }

    fun retry() = viewModelScope.launch { load() }

    fun accept() {
        val ready = _state.value as? InviteUiState.Ready ?: return
        if (ready.joining) return
        _state.value = ready.copy(joining = true)
        viewModelScope.launch {
            _state.value = attempt { InviteUiState.Joined(sharing.accept(token)) }
        }
    }

    private suspend fun load() {
        _state.value = InviteUiState.Loading
        _state.value = attempt {
            val invite = sharing.readInvite(token)
            if (invite.expiresAt <= System.currentTimeMillis()) InviteUiState.Failed(R.string.invite_expired) else InviteUiState.Ready(invite)
        }
    }

    private suspend fun attempt(block: suspend () -> InviteUiState): InviteUiState = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SharingFailure) {
        InviteUiState.Failed(ShareViewModel.messageFor(e))
    } catch (_: Exception) {
        InviteUiState.Failed(R.string.share_failed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(vm: InviteViewModel, onBack: () -> Unit, onOpenList: (String) -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        (state as? InviteUiState.Joined)?.let { onOpenList(it.listId) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_invite)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .testTag("invite"),
        ) {
            when (val s = state) {
                InviteUiState.Loading, is InviteUiState.Joined -> CircularProgressIndicator()
                InviteUiState.NeedsSignIn -> {
                    Text(stringResource(R.string.invite_sign_in), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_sign_in)) }
                }
                is InviteUiState.Ready -> {
                    val by = s.invite.byName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.someone)
                    val roleText = stringResource(if (s.invite.role == Role.VIEWER) R.string.invite_as_viewer else R.string.invite_as_editor)
                    Text(stringResource(R.string.invite_body, by, s.invite.listName), style = MaterialTheme.typography.titleMedium)
                    Text(roleText, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::accept, enabled = !s.joining, modifier = Modifier.fillMaxWidth().testTag("acceptInvite")) {
                        Text(stringResource(R.string.action_join))
                    }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_not_now)) }
                }
                is InviteUiState.Failed -> {
                    Text(stringResource(s.message), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { vm.retry() }) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}
