package dev.gorny.buymyway.ui.lists

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.ListSummary
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.data.share.Sharing
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.ui.common.DragHandle
import dev.gorny.buymyway.ui.common.NameDialog
import dev.gorny.buymyway.ui.common.ReorderState
import dev.gorny.buymyway.ui.common.moveActions
import dev.gorny.buymyway.ui.common.rememberReorderState
import dev.gorny.buymyway.ui.common.reorderableItem
import dev.gorny.buymyway.ui.list.watchingText
import dev.gorny.buymyway.ui.share.LeaveDialog
import dev.gorny.buymyway.ui.share.ShareViewModel
import kotlinx.coroutines.launch

/** Listy, the home screen (PLAN.md *Screens*). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    vm: ListsViewModel,
    onOpenList: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShare: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lost by vm.lostLists.collectAsStateWithLifecycle(emptyList())
    val account by vm.account.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val messages = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }

    // A shared list taken away from this user leaves with a sentence (PLAN.md Phase 5, task 7).
    LaunchedEffect(lost) {
        val name = lost.firstOrNull() ?: return@LaunchedEffect
        vm.held.snackbar.showSnackbar(resources.getString(R.string.list_lost, name))
        vm.lostShown(name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_lists)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.title_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.action_new_list)) },
            )
        },
        snackbarHost = { SnackbarHost(vm.held.snackbar) },
    ) { padding ->
        val pull = rememberPullToRefreshState()
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Only a signed-in phone has anywhere to refresh from.
                .pullToRefresh(
                    isRefreshing = refreshing,
                    state = pull,
                    enabled = account is AccountState.SignedIn,
                    onRefresh = {
                        vm.refresh {
                            messages.launch { vm.held.snackbar.showSnackbar(resources.getString(R.string.refresh_failed)) }
                        }
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                (account as? AccountState.SessionLost)?.let { lost ->
                    SessionLostBanner(lost.email, onSignIn = onOpenSettings)
                }
                val lists = state.lists
                when {
                    lists == null -> Unit
                    lists.isEmpty() -> EmptyLists(Modifier)
                    else -> ListCards(
                        vm = vm,
                        lists = lists,
                        roles = state.roles,
                        watching = state.watching,
                        onOpenList = onOpenList,
                        onShare = onOpenShare,
                        onLeave = { leaving = it },
                        onRename = { renaming = it },
                        onDelete = { summary ->
                            vm.delete(
                                summary.list.id,
                                resources.getString(R.string.deleted_list, summary.list.name),
                                resources.getString(R.string.action_undo),
                            )
                        },
                    )
                }
            }
            PullToRefreshDefaults.Indicator(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.action_new_list),
            label = stringResource(R.string.field_list_name),
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = { name ->
                creating = false
                vm.create(name, onOpenList)
            },
            onDismiss = { creating = false },
        )
    }
    val left = leaving?.let { id -> state.lists?.firstOrNull { it.list.id == id } }
    if (left != null) {
        LeaveDialog(
            listName = left.list.name,
            onConfirm = {
                leaving = null
                vm.leave(left.list.id) { failure ->
                    val message = (failure as? Sharing.SharingFailure)?.let(ShareViewModel::messageFor) ?: R.string.share_failed
                    messages.launch { vm.held.snackbar.showSnackbar(resources.getString(message)) }
                }
            },
            onDismiss = { leaving = null },
        )
    }
    val renamed = renaming?.let { id -> state.lists?.firstOrNull { it.list.id == id } }
    if (renamed != null) {
        NameDialog(
            title = stringResource(R.string.rename_list_title),
            label = stringResource(R.string.field_list_name),
            confirmLabel = stringResource(R.string.action_save),
            initial = renamed.list.name,
            onConfirm = { name ->
                renaming = null
                vm.rename(renamed.list.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun ListCards(
    vm: ListsViewModel,
    lists: List<ListSummary>,
    roles: Map<String, Role>,
    watching: Map<String, List<String>>,
    onOpenList: (String) -> Unit,
    onShare: (String) -> Unit,
    onLeave: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (ListSummary) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        canMove = { _, _ -> true },
        onMove = { from, to -> vm.move(from as String, to as String) },
        onDrop = { vm.drop() },
    )
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("lists"),
    ) {
        items(lists, key = { it.list.id }) { summary ->
            val id = summary.list.id
            val index = lists.indexOf(summary)
            val role = roles[id] ?: Role.OWNER
            ListCard(
                summary = summary,
                watching = watching[id].orEmpty(),
                reorder = reorder,
                onOpen = { onOpenList(id) },
                onShare = { onShare(id) },
                onRename = if (role != Role.VIEWER) ({ onRename(id) }) else null,
                // Only the owner deletes a list; anyone else leaves it (decision 64).
                onDelete = if (role == Role.OWNER) ({ onDelete(summary) }) else null,
                onLeave = if (role != Role.OWNER) ({ onLeave(id) }) else null,
                onMoveUp = if (index > 0) ({ vm.moveBy(id, -1) }) else null,
                onMoveDown = if (index < lists.lastIndex) ({ vm.moveBy(id, 1) }) else null,
                modifier = Modifier.reorderableItem(this, reorder, id),
            )
        }
    }
}

/** Decision 23: the session is gone, the lists are not; say so and offer the way back. */
@Composable
private fun SessionLostBanner(email: String?, onSignIn: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                if (email != null) stringResource(R.string.session_lost_banner, email) else stringResource(R.string.session_lost_banner_generic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onSignIn, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_sign_in_again))
            }
        }
    }
}

@Composable
private fun EmptyLists(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.lists_empty), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.lists_empty_hint), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListCard(
    summary: ListSummary,
    watching: List<String>,
    reorder: ReorderState,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onLeave: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    val progress = stringResource(R.string.list_progress_description, summary.checked, summary.total)
    Card(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { menu = true },
                        onLongClickLabel = stringResource(R.string.action_more),
                    )
                    .semantics { customActions = moveActions(upLabel, downLabel, onMoveUp, onMoveDown) }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (summary.list.shared) R.drawable.ic_group else R.drawable.ic_lock),
                        contentDescription = stringResource(if (summary.list.shared) R.string.list_shared else R.string.list_private),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        summary.list.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (watching.isNotEmpty()) {
                    Text(
                        watchingText(watching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { if (summary.total == 0) 0f else summary.checked.toFloat() / summary.total },
                        modifier = Modifier
                            .weight(1f)
                            .clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.list_progress, summary.checked, summary.total),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { contentDescription = progress },
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        onClick = {
                            menu = false
                            onShare()
                        },
                    )
                    onRename?.let { rename ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = {
                                menu = false
                                rename()
                            },
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = {
                                menu = false
                                delete()
                            },
                        )
                    }
                    onLeave?.let { leave ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_leave_list)) },
                            onClick = {
                                menu = false
                                leave()
                            },
                        )
                    }
                }
            }
            DragHandle(reorder, summary.list.id)
        }
    }
}
