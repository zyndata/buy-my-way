package dev.gorny.buymyway.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.text.QuantityFormat
import dev.gorny.buymyway.data.ImportSummary
import dev.gorny.buymyway.ui.common.FocusSafeDropdownMenu

/**
 * The import preview (PLAN.md Phase 8, task 3): what the shared text turned out to hold, where
 * it will go, and one button that puts it there. Nothing is written before that button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    onBack: () -> Unit,
    onImported: (String, ImportSummary) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val readOnly = stringResource(R.string.import_read_only)
    val failed = stringResource(R.string.import_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_import)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Source and count share a row: on a short phone every line this header does
                // not take is a line of the list the person can actually see.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (state.source.fromEatMyWay) R.string.import_source_eatmyway else R.string.import_source_text,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("importSource"),
                    )
                    if (!state.isEmpty) {
                        Text(
                            pluralStringResource(R.plurals.import_count, state.lines.size, state.lines.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("importCount"),
                        )
                    }
                }
                if (state.isEmpty) {
                    Text(stringResource(R.string.import_nothing), modifier = Modifier.testTag("importEmpty"))
                } else {
                    Text(
                        stringResource(R.string.import_check),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TargetPicker(vm, state)
                }
                state.failure?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("importFailure"))
                }
            }

            HorizontalDivider()

            // The lines scroll; the button below them stays in sight however many there are.
            LazyColumn(modifier = Modifier.weight(1f).testTag("importLines")) {
                var heading: String? = null
                var first = true
                state.lines.forEach { line ->
                    val label = vm.headingOf(line.item)
                    if (first || label != heading) {
                        heading = label
                        first = false
                        item(key = "h-${line.key}") { Heading(label) }
                    }
                    item(key = line.key) {
                        ImportedRow(
                            name = line.item.name,
                            amount = QuantityFormat.format(line.item.quantity, line.item.unit),
                            onRemove = { vm.remove(line.key) },
                        )
                    }
                }
            }

            if (!state.isEmpty) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Button(
                        onClick = { vm.run(readOnly, failed, onImported) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("doImport"),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(pluralStringResource(R.plurals.action_import, state.lines.size, state.lines.size))
                    }
                }
            }
        }
    }
}

/** „Nowa lista: tydzień 15.09 – 21.09", or one of the lists this user may add to. */
@Composable
private fun TargetPicker(vm: ImportViewModel, state: ImportUiState) {
    var open by remember { mutableStateOf(false) }
    val lists = state.lists.orEmpty()
    val chosen = state.target
    val label = when (chosen) {
        is ImportTarget.NewList -> stringResource(R.string.import_new_list)
        is ImportTarget.Existing -> lists.firstOrNull { it.list.id == chosen.listId }?.list?.name.orEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.import_target),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("importTarget")) {
                Text(label)
            }
            FocusSafeDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.import_new_list)) },
                    onClick = {
                        open = false
                        vm.chooseNewList()
                    },
                    modifier = Modifier.testTag("targetNew"),
                )
                lists.forEach { summary ->
                    DropdownMenuItem(
                        text = { Text(summary.list.name) },
                        onClick = {
                            open = false
                            vm.choose(summary.list.id)
                        },
                        modifier = Modifier.testTag("target-${summary.list.id}"),
                    )
                }
            }
        }
        if (chosen is ImportTarget.NewList) {
            // „Gotowe" puts the keyboard away, so the button below is never left under it.
            val focus = LocalFocusManager.current
            OutlinedTextField(
                value = chosen.name,
                onValueChange = vm::rename,
                label = { Text(stringResource(R.string.import_list_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag("importListName"),
            )
        }
    }
}

@Composable
private fun Heading(label: String?) {
    Text(
        text = label ?: stringResource(R.string.import_no_category),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ImportedRow(name: String, amount: String?, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            amount?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.action_remove_imported, name),
            )
        }
    }
}
