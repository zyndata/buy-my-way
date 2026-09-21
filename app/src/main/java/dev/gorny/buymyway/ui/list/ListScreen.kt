package dev.gorny.buymyway.ui.list

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ListDetail
import dev.gorny.buymyway.core.text.QuantityFormat
import dev.gorny.buymyway.ui.common.DragHandle
import dev.gorny.buymyway.ui.common.NameDialog
import dev.gorny.buymyway.ui.common.ReorderState
import dev.gorny.buymyway.ui.common.moveActions
import dev.gorny.buymyway.ui.common.rememberReorderState
import dev.gorny.buymyway.ui.common.reorderableItem

/** Lista (PLAN.md *Screens*): the items to buy by department, „Kupione" below, the add bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    vm: ListViewModel,
    onBack: () -> Unit,
    onOpenCategoryOrder: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val addBar by vm.addBar.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var boughtOpen by rememberSaveable { mutableStateOf(false) }

    // Leaves the screen when the list is deleted, here or (from Phase 5) by someone else.
    LaunchedEffect(state) {
        if (!state.loading && state.detail == null) onBack()
    }
    LaunchedEffect(vm) {
        vm.revived.collect { name -> vm.held.snackbar.showSnackbar(resources.getString(R.string.revived, name)) }
    }

    val detail = state.detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(detail?.list?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItem(R.string.action_category_order) { menu = false; onOpenCategoryOrder() }
                            MenuItem(R.string.action_check_all) { menu = false; vm.setAllChecked(true) }
                            MenuItem(R.string.action_uncheck_all) { menu = false; vm.setAllChecked(false) }
                            MenuItem(R.string.action_rename) { menu = false; renaming = true }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (detail != null) {
                AddBar(
                    state = addBar,
                    categories = detail.categories,
                    onTyped = vm::onTyped,
                    onChooseCategory = vm::chooseCategory,
                    onAdd = vm::add,
                )
            }
        },
        snackbarHost = { SnackbarHost(vm.held.snackbar) },
    ) { padding ->
        if (detail != null) {
            ListContent(
                detail = detail,
                vm = vm,
                boughtOpen = boughtOpen,
                onToggleBought = { boughtOpen = !boughtOpen },
                onEdit = { editing = it.id },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }

    if (renaming && detail != null) {
        NameDialog(
            title = stringResource(R.string.rename_list_title),
            label = stringResource(R.string.field_list_name),
            confirmLabel = stringResource(R.string.action_save),
            initial = detail.list.name,
            onConfirm = { renaming = false; vm.rename(it) },
            onDismiss = { renaming = false },
        )
    }

    val edited = editing?.let { id -> detail?.let { d -> (d.sections.flatMap { it.items } + d.bought).firstOrNull { it.id == id } } }
    if (edited != null && detail != null) {
        EditItemSheet(
            item = edited,
            categories = detail.categories,
            onSave = { content ->
                editing = null
                vm.update(edited.id, content)
            },
            onDelete = {
                editing = null
                vm.delete(
                    edited.id,
                    resources.getString(R.string.deleted_item, edited.name),
                    resources.getString(R.string.action_undo),
                )
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun ListContent(
    detail: ListDetail,
    vm: ListViewModel,
    boughtOpen: Boolean,
    onToggleBought: () -> Unit,
    onEdit: (Item) -> Unit,
    modifier: Modifier,
) {
    if (detail.sections.isEmpty() && detail.bought.isEmpty()) {
        EmptyList(modifier)
        return
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        canMove = { from, to -> vm.canMove(from as String, to as String) },
        onMove = { from, to -> vm.move(from as String, to as String) },
        onDrop = { vm.drop(it as String) },
    )
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = modifier.testTag("items"),
    ) {
        if (detail.sections.isEmpty()) {
            item(key = "allBought") {
                Text(
                    stringResource(R.string.list_all_bought),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(24.dp)
                        .animateItem(),
                )
            }
        }
        for (section in detail.sections) {
            item(key = "h:${section.category.id}") {
                SectionHeader(section.category.name, Modifier.animateItem())
            }
            itemsWithMoves(section.items, reorder, vm, onEdit)
        }
        if (detail.bought.isNotEmpty()) {
            item(key = "bought") {
                BoughtHeader(
                    count = detail.bought.size,
                    open = boughtOpen,
                    onToggle = onToggleBought,
                    onClear = vm::clearChecked,
                    modifier = Modifier.animateItem(),
                )
            }
            if (boughtOpen) {
                items(detail.bought, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        onToggle = { vm.toggle(item) },
                        onEdit = { onEdit(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.itemsWithMoves(items: List<Item>, reorder: ReorderState, vm: ListViewModel, onEdit: (Item) -> Unit) {
    items.forEachIndexed { index, item ->
        item(key = item.id) {
            val movable = !item.checked
            ItemRow(
                item = item,
                onToggle = { vm.toggle(item) },
                onEdit = { onEdit(item) },
                reorder = if (movable) reorder else null,
                onMoveUp = if (movable && index > 0) ({ vm.moveBy(item.id, -1) }) else null,
                onMoveDown = if (movable && index < items.lastIndex && !items[index + 1].checked) ({ vm.moveBy(item.id, 1) }) else null,
                modifier = Modifier.reorderableItem(this, reorder, item.id),
            )
        }
    }
}

@Composable
private fun EmptyList(modifier: Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.list_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.list_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SectionHeader(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun BoughtHeader(count: Int, open: Boolean, onToggle: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val stateLabel = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
    Column(modifier) {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .combinedClickable(onClick = onToggle, role = Role.Button)
                    .semantics {
                        heading()
                        stateDescription = stateLabel
                    }
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.bought_header, count),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                )
            }
            TextButton(onClick = onClear, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.action_clear_bought))
            }
        }
    }
}

/**
 * One item. A tap ticks it (or, in „Kupione", brings it back); a long press edits it. A ticked
 * row is struck through, and TalkBack hears „kupione" (PLAN.md Phase 3, task 7).
 */
@Composable
private fun ItemRow(
    item: Item,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    reorder: ReorderState? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    val stateLabel = stringResource(if (item.checked) R.string.state_bought else R.string.state_to_buy)
    val quantity = QuantityFormat.format(item.quantity, item.unit)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .testTag("item:${item.name}")
                .combinedClickable(
                    role = Role.Checkbox,
                    onClickLabel = stringResource(if (item.checked) R.string.action_uncheck else R.string.action_check),
                    onLongClickLabel = stringResource(R.string.action_edit),
                    onLongClick = onEdit,
                    onClick = {
                        haptics.performHapticFeedback(if (item.checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onToggle()
                    },
                )
                .semantics {
                    toggleableState = ToggleableState(item.checked)
                    stateDescription = stateLabel
                    customActions = moveActions(upLabel, downLabel, onMoveUp, onMoveDown)
                }
                .padding(start = 16.dp, end = if (reorder == null) 16.dp else 0.dp),
        ) {
            Icon(
                painterResource(if (item.checked) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked),
                contentDescription = null,
                tint = if (item.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .alpha(if (item.checked) 0.6f else 1f),
            ) {
                val decoration = if (item.checked) TextDecoration.LineThrough else null
                Text(item.name, style = MaterialTheme.typography.bodyLarge, textDecoration = decoration)
                val details = listOfNotNull(quantity, item.note).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = decoration,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (reorder != null) {
            DragHandle(reorder, item.id, Modifier.size(48.dp))
        }
    }
}
