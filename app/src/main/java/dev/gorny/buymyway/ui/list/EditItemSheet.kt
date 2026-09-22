package dev.gorny.buymyway.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.CategoryInfo
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.text.DateText
import dev.gorny.buymyway.core.text.QuantityFormat
import dev.gorny.buymyway.data.photo.PhotoRef
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The edit sheet (PLAN.md Phase 3, task 4): name, quantity and unit, category, note, photo
 * (Phase 6: camera, gallery, remove; [photo] is null where photos are not available), delete.
 * Below them, read-only, when the item was last edited and, if bought, when it was ticked, with
 * who did it where they are known (Phase 5, task 8). [nameOf] names a member by uid, or gives
 * null. The photo changes at once, not with „Zapisz" (STATE.md decision 71).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditItemSheet(
    item: Item,
    categories: List<CategoryInfo>,
    nameOf: (String?) -> String?,
    photo: PhotoActions?,
    onSave: (ItemContent) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var quantity by rememberSaveable(item.id) { mutableStateOf(item.quantity?.let(QuantityFormat::number).orEmpty()) }
    var unit by rememberSaveable(item.id) { mutableStateOf(item.unit.orEmpty()) }
    var categoryId by rememberSaveable(item.id) { mutableStateOf(item.categoryId) }
    var note by rememberSaveable(item.id) { mutableStateOf(item.note.orEmpty()) }
    var picking by rememberSaveable { mutableStateOf(false) }

    val parsedQuantity = QuantityFormat.parse(quantity)
    val valid = name.isNotBlank() && parsedQuantity.isSuccess

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .testTag("editSheet"),
        ) {
            Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text(stringResource(R.string.field_quantity)) },
                    singleLine = true,
                    isError = parsedQuantity.isFailure,
                    supportingText = if (parsedQuantity.isFailure) {
                        { Text(stringResource(R.string.field_quantity_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text(stringResource(R.string.field_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            ExposedDropdownMenuBox(expanded = picking, onExpandedChange = { picking = it }) {
                OutlinedTextField(
                    value = categories.firstOrNull { it.id == categoryId }?.name.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.field_category)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = picking) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    categories.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                categoryId = option.id
                                picking = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.field_note)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (photo != null) PhotoSlot(item.name, photo)
            ItemDates(item, nameOf)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_delete))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(
                            item.content.copy(
                                name = name.trim(),
                                quantity = parsedQuantity.getOrNull(),
                                unit = unit.trim().ifEmpty { null },
                                categoryId = categoryId,
                                note = note.trim().ifEmpty { null },
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/** „Edytowano 22.09.2026, 08:56 · Ania" and, for a bought item, „Kupiono …". */
@Composable
private fun ItemDates(item: Item, nameOf: (String?) -> String?) {
    Column(Modifier.testTag("itemDates")) {
        DateLine(stringResource(R.string.item_edited, DateText.format(item.updatedAt)), nameOf(item.updatedBy))
        val checkedAt = item.checkedAt
        if (item.checked && checkedAt != null) {
            DateLine(stringResource(R.string.item_bought, DateText.format(checkedAt)), nameOf(item.checkedBy))
        }
    }
}

@Composable
private fun DateLine(text: String, by: String?) {
    Text(
        if (by != null) stringResource(R.string.item_date_by, text, by) else text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What the edit sheet's photo slot shows and does (PLAN.md Phase 6). */
class PhotoActions(
    val ref: PhotoRef?,
    /** A new photo is being prepared. */
    val busy: Boolean,
    val load: suspend (PhotoRef, Int) -> ImageBitmap?,
    val onTake: () -> Unit,
    val onPick: () -> Unit,
    val onRemove: () -> Unit,
    val onOpen: () -> Unit,
)

@Composable
private fun PhotoSlot(name: String, photo: PhotoActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("photoSlot")) {
        val ref = photo.ref
        when {
            photo.busy -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(THUMBNAIL)) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
            ref != null -> PhotoThumbnail(ref, name, THUMBNAIL, photo.load, photo.onOpen)
        }
        Column(Modifier.weight(1f).padding(start = if (photo.busy || ref != null) 12.dp else 0.dp)) {
            if (photo.busy) {
                Text(
                    stringResource(R.string.photo_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                PhotoButton(R.drawable.ic_photo_camera, R.string.action_take_photo, photo.onTake)
                PhotoButton(R.drawable.ic_image, R.string.action_pick_photo, photo.onPick)
                if (ref != null) PhotoButton(R.drawable.ic_delete, R.string.action_remove_photo, photo.onRemove)
            }
        }
    }
}

@Composable
private fun PhotoButton(icon: Int, label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label))
    }
}

private val THUMBNAIL = 72.dp
