package dev.gorny.buymyway.ui.categories

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.CategoryInfo
import dev.gorny.buymyway.ui.common.DragHandle
import dev.gorny.buymyway.ui.common.NameDialog
import dev.gorny.buymyway.ui.common.moveActions
import dev.gorny.buymyway.ui.common.rememberReorderState
import dev.gorny.buymyway.ui.common.reorderableItem

/**
 * Kolejność kategorii: drag the departments into the order the shop is walked. For a list, its
 * own categories can also be added, renamed and deleted here (STATE.md decision 50).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryOrderScreen(
    vm: CategoryOrderViewModel,
    @StringRes title: Int,
    onBack: () -> Unit,
) {
    val categories by vm.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val edits = vm.edits

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (edits != null) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_category)) },
                )
            }
        },
    ) { padding ->
        val rows = categories ?: return@Scaffold
        val listState = rememberLazyListState()
        val reorder = rememberReorderState(
            listState,
            canMove = { _, _ -> true },
            onMove = { from, to -> vm.move(from as String, to as String) },
            onDrop = { vm.drop() },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("categories"),
        ) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.category_order_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            itemsIndexed(rows, key = { _, c -> c.id }) { index, category ->
                CategoryRow(
                    category = category,
                    editable = edits != null && !category.builtin,
                    onMoveUp = if (index > 0) ({ vm.moveBy(category.id, -1) }) else null,
                    onMoveDown = if (index < rows.lastIndex) ({ vm.moveBy(category.id, 1) }) else null,
                    onRename = { renaming = category.id },
                    onDelete = { deleting = category.id },
                    handle = { DragHandle(reorder, category.id) },
                    modifier = Modifier.reorderableItem(this, reorder, category.id),
                )
            }
        }
    }

    if (adding) {
        NameDialog(
            title = stringResource(R.string.action_add_category),
            label = stringResource(R.string.field_category_name),
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = { adding = false; vm.add(it) },
            onDismiss = { adding = false },
        )
    }
    val renamed = renaming?.let { id -> categories?.firstOrNull { it.id == id } }
    if (renamed != null) {
        NameDialog(
            title = stringResource(R.string.rename_category_title),
            label = stringResource(R.string.field_category_name),
            confirmLabel = stringResource(R.string.action_save),
            initial = renamed.name,
            onConfirm = { renaming = null; vm.rename(renamed.id, it) },
            onDismiss = { renaming = null },
        )
    }
    val deleted = deleting?.let { id -> categories?.firstOrNull { it.id == id } }
    if (deleted != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_category_title, deleted.name)) },
            text = { Text(stringResource(R.string.delete_category_body)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.delete(deleted.id) }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    // A list deleted while its editor is open: nothing left to order.
    LaunchedEffect(categories) {
        if (categories?.isEmpty() == true) onBack()
    }
}

@Composable
private fun CategoryRow(
    category: CategoryInfo,
    editable: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    handle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    var menu by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(
                category.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { customActions = moveActions(upLabel, downLabel, onMoveUp, onMoveDown) }
                    .padding(horizontal = 16.dp)
                    .testTag("category:${category.name}"),
            )
            if (editable) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            handle()
        }
    }
}
