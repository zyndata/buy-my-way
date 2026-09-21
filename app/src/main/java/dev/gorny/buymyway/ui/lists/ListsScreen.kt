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
import androidx.compose.material3.Card
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.gorny.buymyway.ui.common.DragHandle
import dev.gorny.buymyway.ui.common.NameDialog
import dev.gorny.buymyway.ui.common.ReorderState
import dev.gorny.buymyway.ui.common.moveActions
import dev.gorny.buymyway.ui.common.rememberReorderState
import dev.gorny.buymyway.ui.common.reorderableItem

/** Listy, the home screen (PLAN.md *Screens*). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    vm: ListsViewModel,
    onOpenList: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }

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
        val lists = state.lists
        when {
            lists == null -> Unit
            lists.isEmpty() -> EmptyLists(Modifier.padding(padding))
            else -> {
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
                        .padding(padding)
                        .testTag("lists"),
                ) {
                    items(lists, key = { it.list.id }) { summary ->
                        val id = summary.list.id
                        val index = lists.indexOf(summary)
                        ListCard(
                            summary = summary,
                            reorder = reorder,
                            onOpen = { onOpenList(id) },
                            onRename = { renaming = id },
                            onDelete = {
                                vm.delete(
                                    id,
                                    resources.getString(R.string.deleted_list, summary.list.name),
                                    resources.getString(R.string.action_undo),
                                )
                            },
                            onMoveUp = if (index > 0) ({ vm.moveBy(id, -1) }) else null,
                            onMoveDown = if (index < lists.lastIndex) ({ vm.moveBy(id, 1) }) else null,
                            modifier = Modifier.reorderableItem(this, reorder, id),
                        )
                    }
                }
            }
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
private fun EmptyLists(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
    reorder: ReorderState,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
                    if (!summary.list.shared) {
                        Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = stringResource(R.string.list_private),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        summary.list.name,
                        style = MaterialTheme.typography.titleMedium,
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
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
            DragHandle(reorder, summary.list.id)
        }
    }
}
