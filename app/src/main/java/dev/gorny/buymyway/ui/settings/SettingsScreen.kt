package dev.gorny.buymyway.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.auth.SignInResult
import dev.gorny.buymyway.data.prefs.ThemeChoice
import kotlinx.coroutines.launch

/**
 * Ustawienia (PLAN.md *Screens*): the account (Phase 4), notifications (Phase 9), the default
 * category order for new lists, „Moje produkty" (Phase 8b), „Motyw" and „O aplikacji", which is
 * where the version and the licences live (STATE.md decisions 104 and 105).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDefaultOrder: () -> Unit,
    onOpenOwnProducts: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val account by vm.account.collectAsStateWithLifecycle()
    val theme by vm.themeChoice.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val switches by vm.switches.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messages = rememberCoroutineScope()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var choosingTheme by rememberSaveable { mutableStateOf(false) }

    // Notifications are asked for at the moment the switch is turned on, never at start
    // (PLAN.md Phase 9, task 3). Below Android 13 there is nothing to ask.
    val context = LocalContext.current
    val denied = stringResource(R.string.notifications_denied)
    val settingsLabel = stringResource(R.string.action_app_settings)
    val askToPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setNotificationsEnabled(true)
        } else {
            messages.launch {
                val answer = snackbar.showSnackbar(denied, actionLabel = settingsLabel)
                if (answer == SnackbarResult.ActionPerformed) context.startActivity(appSettings(context))
            }
        }
    }
    val turnOn: () -> Unit = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            vm.setNotificationsEnabled(true)
        } else {
            askToPost.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            AccountSection(
                account = account,
                pending = pending,
                busy = busy,
                onSignIn = signIn,
                onSignOut = { confirmSignOut = true },
                onDeleteData = { confirmDelete = true },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_notifications),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications_enabled)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_hint)) },
                trailingContent = {
                    Switch(
                        checked = switches.enabled,
                        onCheckedChange = { on -> if (on) turnOn() else vm.setNotificationsEnabled(false) },
                        modifier = Modifier.testTag("notifyEnabled"),
                    )
                },
            )
            NotificationSwitch(
                label = stringResource(R.string.settings_notify_added),
                checked = switches.added,
                enabled = switches.enabled,
                tag = "notifyAdded",
                onChange = vm::setNotifyAdded,
            )
            NotificationSwitch(
                label = stringResource(R.string.settings_notify_checked),
                checked = switches.checked,
                enabled = switches.enabled,
                tag = "notifyChecked",
                onChange = vm::setNotifyChecked,
            )
            NotificationSwitch(
                label = stringResource(R.string.settings_notify_shared),
                checked = switches.shared,
                enabled = switches.enabled,
                tag = "notifyShared",
                onChange = vm::setNotifyShared,
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_order)) },
                supportingContent = { Text(stringResource(R.string.settings_default_order_hint)) },
                modifier = Modifier.clickable(onClick = onOpenDefaultOrder),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_own_products)) },
                supportingContent = { Text(stringResource(R.string.settings_own_products_hint)) },
                modifier = Modifier
                    .clickable(onClick = onOpenOwnProducts)
                    .testTag("ownProducts"),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(stringResource(themeLabel(theme))) },
                modifier = Modifier
                    .clickable { choosingTheme = true }
                    .testTag("theme"),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(stringResource(R.string.settings_about_hint)) },
                modifier = Modifier
                    .clickable(onClick = onOpenAbout)
                    .testTag("about"),
            )
        }
    }

    if (choosingTheme) {
        ThemeDialog(
            current = theme,
            onChoose = { choosingTheme = false; vm.setTheme(it) },
            onDismiss = { choosingTheme = false },
        )
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

    if (confirmDelete) {
        val failed = stringResource(R.string.delete_data_failed)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_data_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_data_body))
                    Text(
                        stringResource(R.string.delete_data_body_others),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.deleteEverything { ok -> if (!ok) messages.launch { snackbar.showSnackbar(failed) } }
                    },
                ) {
                    Text(stringResource(R.string.action_delete_data), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** What the „Motyw" row reads under its title, and what each choice is called in the dialog. */
private fun themeLabel(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.SYSTEM -> R.string.theme_system
    ThemeChoice.LIGHT -> R.string.theme_light
    ThemeChoice.DARK -> R.string.theme_dark
}

/** „Motyw": the colours follow the system unless the user says otherwise (decision 104). */
@Composable
private fun ThemeDialog(current: ThemeChoice, onChoose: (ThemeChoice) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeChoice.entries.forEach { choice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = choice == current,
                                role = Role.RadioButton,
                                onClick = { onChoose(choice) },
                            )
                            .testTag("theme:${choice.key}"),
                    ) {
                        RadioButton(selected = choice == current, onClick = null)
                        Text(stringResource(themeLabel(choice)), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One of the three per-kind switches; greyed out while the master switch is off. */
@Composable
private fun NotificationSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(label, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)
        },
        trailingContent = {
            Switch(
                checked = checked && enabled,
                enabled = enabled,
                onCheckedChange = onChange,
                modifier = Modifier.testTag(tag),
            )
        },
    )
}

/** This app's page in the system settings, where a refused permission is turned back on. */
private fun appSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

@Composable
private fun AccountSection(
    account: AccountState,
    pending: Int,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteData: () -> Unit,
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
            // Only where it can work: deleting needs a session to write with (decision 97).
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_delete_data), color = MaterialTheme.colorScheme.error)
                },
                supportingContent = { Text(stringResource(R.string.settings_delete_data_hint)) },
                modifier = Modifier
                    .clickable(enabled = !busy, onClick = onDeleteData)
                    .testTag("deleteData"),
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
