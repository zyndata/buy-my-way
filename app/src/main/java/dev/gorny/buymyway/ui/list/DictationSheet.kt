package dev.gorny.buymyway.ui.list

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.CategoryInfo
import dev.gorny.buymyway.core.text.QuantityFormat
import dev.gorny.buymyway.core.voice.VoiceError
import dev.gorny.buymyway.core.voice.VoiceEvent
import dev.gorny.buymyway.core.voice.VoiceSource

/**
 * The review sheet (PLAN.md Phase 7, task 3). Dictation never writes to the list: what was
 * heard waits here as one editable line per item with its proposed category, and only
 * „Dodaj wszystkie" adds them. „Dyktuj dalej" says one more sentence into the same sheet.
 *
 * The recognizer belongs to this sheet: it is started when the sheet opens and given back when
 * it closes, so the microphone is on only while this is on screen. [voice] is the app's
 * `VoiceRecognizer`, or, in a test, one that says what a phone would have heard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationSheet(
    state: DictationState,
    categories: List<CategoryInfo>,
    voice: VoiceSource,
    onEvent: (VoiceEvent) -> Unit,
    onEdit: (Long, String) -> Unit,
    onChooseCategory: (Long, String) -> Unit,
    onRemove: (Long) -> Unit,
    onAddAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun listen() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onEvent(VoiceEvent.Failed(VoiceError.PERMISSION))
            return
        }
        voice.start(onEvent)
    }

    // The mic goes on with the sheet and is given back with it, whatever closed it.
    LaunchedEffect(Unit) { listen() }
    DisposableEffect(Unit) { onDispose { voice.release() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .testTag("dictationSheet"),
        ) {
            Text(stringResource(R.string.dictation_title), style = MaterialTheme.typography.titleLarge)
            Status(state)
            // Only the lines scroll. „Dodaj wszystkie" stays in sight however many were heard.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.items.forEach { item ->
                    DictatedRow(
                        item = item,
                        categories = categories,
                        onEdit = { text -> onEdit(item.key, text) },
                        onChooseCategory = { id -> onChooseCategory(item.key, id) },
                        onRemove = { onRemove(item.key) },
                    )
                }
            }
            // Three buttons do not fit across a phone, so the mic takes its own row: on a small
            // screen the last of a Row is squeezed to a circle with its label broken up.
            FilledTonalButton(
                onClick = { if (state.busy) voice.stop() else listen() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dictateMore"),
            ) {
                Icon(
                    painterResource(if (state.busy) R.drawable.ic_stop else R.drawable.ic_mic),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.busy) R.string.action_stop_dictating else R.string.action_dictate_more))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAddAll,
                    enabled = state.items.any { it.name.isNotBlank() },
                    modifier = Modifier.testTag("addAll"),
                ) {
                    Text(stringResource(R.string.action_add_all))
                }
            }
        }
    }
}

/** „Słucham…", the words heard so far, an error, or what to do next. */
@Composable
private fun Status(state: DictationState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = when {
                    state.listening -> stringResource(R.string.dictation_listening)
                    state.thinking -> stringResource(R.string.dictation_thinking)
                    state.items.isNotEmpty() -> stringResource(R.string.dictation_check)
                    state.error == null -> stringResource(R.string.dictation_empty)
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("dictationStatus"),
            )
        }
        if (state.partial.isNotBlank()) {
            Text(state.partial, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("partial"))
        }
        val error = state.error
        if (error != null) {
            Text(
                stringResource(voiceErrorText(error)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("voiceError"),
            )
        }
        if (state.items.isEmpty() && !state.busy) {
            Text(
                stringResource(R.string.dictation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One thing heard: the line as the add bar would read it („2 kg ziemniaki"), the category it
 * would go to, and a way to drop it. Editing the line re-reads the quantity and the unit.
 */
@Composable
private fun DictatedRow(
    item: DictatedItem,
    categories: List<CategoryInfo>,
    onEdit: (String) -> Unit,
    onChooseCategory: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var text by rememberSaveable(item.key) {
        mutableStateOf(listOfNotNull(QuantityFormat.format(item.quantity, item.unit), item.name).joinToString(" "))
    }
    var picking by remember { mutableStateOf(false) }
    val category = categories.firstOrNull { it.id == item.categoryId }
    val removeLabel = stringResource(R.string.action_remove_dictated, item.name)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("dictated:${item.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onEdit(it)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .weight(1f)
                    .testTag("dictatedField:${item.key}"),
            )
            IconButton(onClick = onRemove, modifier = Modifier.padding(start = 4.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = removeLabel)
            }
        }
        if (category != null) {
            val description = stringResource(R.string.dictation_item_description, item.name, category.name)
            Box {
                AssistChip(
                    onClick = { picking = true },
                    label = { Text(category.name) },
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .testTag("dictatedCategory:${item.key}")
                        .semantics { contentDescription = description },
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
    }
}

/** One Polish sentence per recognizer error (PLAN.md Phase 7, task 1). */
fun voiceErrorText(error: VoiceError): Int = when (error) {
    VoiceError.NO_SPEECH -> R.string.voice_error_no_speech
    VoiceError.NO_MATCH -> R.string.voice_error_no_match
    VoiceError.AUDIO -> R.string.voice_error_audio
    VoiceError.NETWORK -> R.string.voice_error_network
    VoiceError.PERMISSION -> R.string.voice_error_permission
    VoiceError.BUSY -> R.string.voice_error_busy
    VoiceError.LANGUAGE -> R.string.voice_error_language
    VoiceError.OTHER -> R.string.voice_error_other
}
