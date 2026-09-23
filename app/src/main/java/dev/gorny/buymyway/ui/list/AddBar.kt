package dev.gorny.buymyway.ui.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.CategoryInfo
import dev.gorny.buymyway.core.parse.ItemParser

/**
 * The add bar (PLAN.md Phase 3, task 3): one field that takes „2 kg ziemniaki, mleko",
 * autocomplete from the dictionary and this device's history above it, and the proposed
 * category as a chip that can be changed before adding. „+" and the keyboard's action add.
 * The mic (Phase 7) opens the review sheet; [onMic] is null on a phone with no recognizer, and
 * then there is no mic button at all (Phase 7, task 4).
 */
@Composable
fun AddBar(
    state: AddBarState,
    categories: List<CategoryInfo>,
    onTyped: (String) -> Unit,
    onChooseCategory: (String) -> Unit,
    onAdd: (String) -> Boolean,
    onMic: (() -> Unit)? = null,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var picking by remember { mutableStateOf(false) }
    // After process death the field comes back with its text; the view model has to hear it.
    LaunchedEffect(Unit) { onTyped(value.text) }

    fun set(text: String) {
        value = TextFieldValue(text, TextRange(text.length))
        onTyped(text)
    }

    fun submit() {
        if (onAdd(value.text)) set("")
    }

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val category = state.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
            if (state.suggestions.isNotEmpty() || category != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (category != null) {
                        val chipDescription = stringResource(R.string.category_chip_description, category.name)
                        Box {
                            AssistChip(
                                onClick = { picking = true },
                                label = { Text(category.name) },
                                modifier = Modifier
                                    .testTag("categoryChip")
                                    .semantics { contentDescription = chipDescription },
                            )
                            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                                categories.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.name) },
                                        onClick = {
                                            picking = false
                                            onChooseCategory(option.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    state.suggestions.forEach { name ->
                        SuggestionChip(
                            onClick = { set(ItemParser.replaceLastName(value.text, name)) },
                            label = { Text(name) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onTyped(it.text)
                    },
                    placeholder = { Text(stringResource(R.string.add_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("addField"),
                )
                if (onMic != null) {
                    IconButton(onClick = onMic, modifier = Modifier.testTag("mic")) {
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.action_dictate))
                    }
                }
                FilledIconButton(
                    onClick = ::submit,
                    enabled = value.text.isNotBlank(),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.action_add))
                }
            }
        }
    }
}
