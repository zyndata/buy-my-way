package dev.gorny.buymyway.ui.share

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.ui.list.ListViewModel
import dev.gorny.buymyway.ui.common.FocusSafeDropdownMenu

/** Udostępnianie (PLAN.md *Screens*): who has the list, and the ways to invite someone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    vm: ShareViewModel,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var role by rememberSaveable { mutableStateOf(Role.EDITOR) }
    var offerLinkTo by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmPrivate by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { event ->
            when (event) {
                is ShareEvent.Link -> {
                    val text = resources.getString(R.string.share_link_text, event.listName, event.url)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, resources.getString(R.string.share_link_chooser)))
                }
                is ShareEvent.Message -> snackbar.showSnackbar(
                    if (event.arg != null) resources.getString(event.text, event.arg) else resources.getString(event.text),
                )
                is ShareEvent.OfferLink -> offerLinkTo = event.email
                ShareEvent.Left -> onLeft()
            }
        }
    }
    LaunchedEffect(state.gone, state.loading) {
        if (!state.loading && state.gone) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_share))
                        Text(state.listName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("share"),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.loading -> Unit
                !state.signedIn -> SignInFirst(onOpenSettings)
                state.isOwner -> OwnerPart(
                    state = state,
                    role = role,
                    onRole = { role = it },
                    onLink = { vm.inviteLink(role) },
                    onEmail = { vm.inviteByEmail(it, role) },
                )
                else -> Text(stringResource(R.string.share_member_hint), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.signedIn && state.members.isNotEmpty()) {
                Heading(stringResource(R.string.share_people))
                for (member in state.members) {
                    MemberRow(
                        member = member,
                        isMe = member.uid == state.myUid,
                        canManage = state.isOwner && member.role != Role.OWNER && !state.busy,
                        onRole = { vm.setRole(member, it) },
                        onRemove = { vm.remove(member) },
                    )
                }
            }
            if (state.signedIn && state.isOwner && state.shared) {
                HorizontalDivider()
                TextButton(onClick = { confirmPrivate = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.action_make_private))
                }
            }
            if (state.signedIn && !state.isOwner) {
                HorizontalDivider()
                TextButton(onClick = { confirmLeave = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.action_leave_list))
                }
            }
        }
    }

    offerLinkTo?.let { email ->
        AlertDialog(
            onDismissRequest = { offerLinkTo = null },
            title = { Text(stringResource(R.string.share_email_not_found_title)) },
            text = { Text(stringResource(R.string.share_email_not_found, email)) },
            confirmButton = {
                TextButton(onClick = {
                    offerLinkTo = null
                    vm.inviteLink(role)
                }) { Text(stringResource(R.string.action_send_link)) }
            },
            dismissButton = { TextButton(onClick = { offerLinkTo = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmPrivate) {
        AlertDialog(
            onDismissRequest = { confirmPrivate = false },
            title = { Text(stringResource(R.string.make_private_title)) },
            text = { Text(stringResource(R.string.make_private_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrivate = false
                    vm.makePrivate()
                }) { Text(stringResource(R.string.action_make_private)) }
            },
            dismissButton = { TextButton(onClick = { confirmPrivate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmLeave) {
        LeaveDialog(
            listName = state.listName,
            onConfirm = {
                confirmLeave = false
                vm.leave()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** „Opuścić listę …?", shared by this screen and the home screen's card menu. */
@Composable
fun LeaveDialog(listName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leave_title, listName)) },
        text = { Text(stringResource(R.string.leave_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_leave_list)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SignInFirst(onOpenSettings: () -> Unit) {
    Text(stringResource(R.string.share_sign_in_needed), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_sign_in)) }
}

@Composable
private fun OwnerPart(state: ShareUiState, role: Role, onRole: (Role) -> Unit, onLink: () -> Unit, onEmail: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    if (!state.shared) Text(stringResource(R.string.share_private_hint), style = MaterialTheme.typography.bodyMedium)
    Heading(stringResource(R.string.share_invite))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = role == Role.EDITOR, onClick = { onRole(Role.EDITOR) }, label = { Text(stringResource(R.string.role_editor)) })
        FilterChip(selected = role == Role.VIEWER, onClick = { onRole(Role.VIEWER) }, label = { Text(stringResource(R.string.role_viewer)) })
    }
    Button(onClick = onLink, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("inviteLink")) {
        Icon(painterResource(R.drawable.ic_share), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_invite_link))
    }
    Text(stringResource(R.string.share_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.field_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { onEmail(email) },
        enabled = !state.busy && email.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_invite_email))
    }
    Text(stringResource(R.string.share_email_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun MemberRow(member: Member, isMe: Boolean, canManage: Boolean, onRole: (Role) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val name = ListViewModel.displayName(member).ifEmpty { stringResource(R.string.someone) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp).testTag("member:$name")) {
        Column(Modifier.weight(1f)) {
            Text(if (isMe) stringResource(R.string.member_me, name) else name, style = MaterialTheme.typography.bodyLarge)
            val details = listOfNotNull(member.email?.takeIf { it != name }, stringResource(roleLabel(member.role)))
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canManage) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.member_actions, name))
                }
                FocusSafeDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.role_editor)) }, onClick = { menu = false; onRole(Role.EDITOR) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.role_viewer)) }, onClick = { menu = false; onRole(Role.VIEWER) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_member)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

fun roleLabel(role: Role): Int = when (role) {
    Role.OWNER -> R.string.role_owner
    Role.EDITOR -> R.string.role_editor
    Role.VIEWER -> R.string.role_viewer
}
