package dev.gorny.buymyway.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.BuildConfig
import dev.gorny.buymyway.R
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.auth.SignInResult
import kotlinx.coroutines.launch

/**
 * Ustawienia (PLAN.md *Screens*): the account (Phase 4), the default category order for new
 * lists, the theme (it follows the system), the version. Notifications, backup and the update
 * check join in their phases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onOpenDefaultOrder: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val messages = rememberCoroutineScope()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    val signIn: () -> Unit = {
        if (activity != null) {
            vm.signIn(activity) { result ->
                val message = when (result) {
                    SignInResult.Done, SignInResult.Cancelled -> null
                    SignInResult.NoAccount -> resources.getString(R.string.sign_in_no_account)
                    SignInResult.Failed -> resources.getString(R.string.sign_in_failed)
                    is SignInResult.OtherAccount -> result.expectedEmail
                        ?.let { resources.getString(R.string.sign_in_other_account, it) }
                        ?: resources.getString(R.string.sign_in_other_account_generic)
                }
                if (message != null) messages.launch { snackbar.showSnackbar(message) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.settings_account),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            AccountSection(account, pending, busy, onSignIn = signIn, onSignOut = { confirmSignOut = true })
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_order)) },
                supportingContent = { Text(stringResource(R.string.settings_default_order_hint)) },
                modifier = Modifier.clickable(onClick = onOpenDefaultOrder),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(stringResource(R.string.settings_theme_value)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_icons)) },
                supportingContent = { Text(stringResource(R.string.settings_icons_value)) },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.sign_out_body))
                    if (pending > 0) {
                        Text(
                            pluralStringResource(R.plurals.sign_out_pending, pending, pending),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        vm.signOut()
                    },
                ) { Text(stringResource(R.string.action_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AccountSection(
    account: AccountState,
    pending: Int,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (account) {
        AccountState.Loading -> Unit
        AccountState.SignedOut -> ListItem(
            headlineContent = { Text(stringResource(R.string.action_sign_in)) },
            supportingContent = { Text(stringResource(R.string.account_sign_in_hint)) },
            modifier = Modifier.clickable(enabled = !busy, onClick = onSignIn),
        )
        is AccountState.SignedIn -> {
            ListItem(
                headlineContent = { Text(account.email ?: account.name ?: stringResource(R.string.account_signed_in)) },
                supportingContent = {
                    Text(
                        if (pending == 0) {
                            stringResource(R.string.account_all_sent)
                        } else {
                            pluralStringResource(R.plurals.pending_changes, pending, pending)
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_sign_out)) },
                modifier = Modifier.clickable(enabled = !busy, onClick = onSignOut),
            )
        }
        is AccountState.SessionLost -> {
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_sign_in_again)) },
                supportingContent = {
                    Text(
                        account.email?.let { stringResource(R.string.account_session_lost, it) }
                            ?: stringResource(R.string.account_session_lost_generic),
                    )
                },
                modifier = Modifier.clickable(enabled = !busy, onClick = onSignIn),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_sign_out)) },
                modifier = Modifier.clickable(enabled = !busy, onClick = onSignOut),
            )
        }
    }
}
