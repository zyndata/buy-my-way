package dev.gorny.buymyway.ui.list

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.app.ActivityCompat
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ListDetail
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.text.QuantityFormat
import dev.gorny.buymyway.core.text.QuantityStep
import dev.gorny.buymyway.core.voice.VoiceSource
import dev.gorny.buymyway.data.voice.VoiceRecognizer
import dev.gorny.buymyway.data.photo.PhotoRef
import dev.gorny.buymyway.ui.common.DragHandle
import dev.gorny.buymyway.ui.common.NameDialog
import dev.gorny.buymyway.ui.common.ReorderState
import dev.gorny.buymyway.ui.common.moveActions
import dev.gorny.buymyway.ui.common.rememberReorderState
import dev.gorny.buymyway.ui.common.reorderableItem
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import dev.gorny.buymyway.ui.common.FocusSafeDropdownMenu

/** Lista (PLAN.md *Screens*): the items to buy by department, „Kupione" below, the add bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    vm: ListViewModel,
    onBack: () -> Unit,
    onOpenCategoryOrder: () -> Unit,
    onOpenShare: () -> Unit,
    /** What „Dyktowanie" listens with; a test hands it utterances instead of a microphone. */
    voice: VoiceSource? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val addBar by vm.addBar.collectAsStateWithLifecycle()
    // Collected while the screen is shown: that is what keeps the listeners attached (decision 65).
    val watching by vm.watching.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var boughtOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val pendingPhotos by vm.pendingPhotos.collectAsStateWithLifecycle()
    val photoBusy by vm.photoBusy.collectAsStateWithLifecycle()
    val dictation by vm.dictation.collectAsStateWithLifecycle()
    val moveTargets by vm.moveTargets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val putAwayKeyboard = rememberPutAwayKeyboard()
    val scope = rememberCoroutineScope()
    // A phone with no speech recognizer gets no mic button at all (Phase 7, task 4).
    val canDictate = remember(voice) { voice != null || SpeechRecognizer.isRecognitionAvailable(context) }
    val phoneVoice = remember(context) { VoiceRecognizer(context) }
    var askingMic by rememberSaveable { mutableStateOf(false) }
    val deniedMic = stringResource(R.string.mic_denied)
    val settingsLabel = stringResource(R.string.action_app_settings)
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.openDictation()
        } else {
            scope.launch {
                val answer = vm.held.snackbar.showSnackbar(deniedMic, actionLabel = settingsLabel)
                if (answer == SnackbarResult.ActionPerformed) context.startActivity(appSettings(context))
            }
        }
    }
    val onMic: () -> Unit = {
        putAwayKeyboard()
        val activity = context as? Activity
        when {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                vm.openDictation()
            // One sentence on why, but only where Android says the user has been asked before.
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO) ->
                askingMic = true
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Which item the camera or the gallery is choosing for: kept across process death, since
    // the camera app may be in front long enough for Android to end this one.
    var cameraFor by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryFor by rememberSaveable { mutableStateOf<String?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val itemId = cameraFor
        cameraFor = null
        val file = CameraFile.file(context)
        if (taken && itemId != null) vm.setPhoto(itemId, { file.inputStream() }, done = { file.delete() }) else file.delete()
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val itemId = galleryFor
        galleryFor = null
        if (uri != null && itemId != null) vm.setPhoto(itemId, opener(context, uri))
    }

    // While this list is resumed it is „on screen": a push about it is silent, and what it had
    // counted is forgotten (Phase 9, decision 95).
    LifecycleResumeEffect(vm.listId) {
        vm.onScreen(true)
        onPauseOrDispose { vm.onScreen(false) }
    }

    // Leaves the screen when the list is deleted, here or by someone else, or taken away.
    LaunchedEffect(state) {
        if (!state.loading && state.detail == null) onBack()
    }
    LaunchedEffect(vm) {
        vm.revived.collect { name -> vm.held.snackbar.showSnackbar(resources.getString(R.string.revived, name)) }
    }
    LaunchedEffect(vm) {
        vm.moved.collect { moved ->
            val text = when {
                moved.listName == null -> resources.getString(R.string.move_failed, moved.name)
                moved.removed -> resources.getString(R.string.moved_item, moved.name, moved.listName)
                else -> resources.getString(R.string.copied_item, moved.name, moved.listName)
            }
            vm.held.snackbar.showSnackbar(text)
        }
    }
    LaunchedEffect(vm) {
        vm.photoFailed.collect { vm.held.snackbar.showSnackbar(resources.getString(R.string.photo_failed)) }
    }

    val detail = state.detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(detail?.list?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (watching.isNotEmpty()) {
                            Text(
                                watchingText(watching),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("watching"),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenShare) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                        }
                        FocusSafeDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItem(R.string.action_sort) { menu = false; sorting = true }
                            if (state.canEdit) {
                                MenuItem(R.string.action_category_order) { menu = false; onOpenCategoryOrder() }
                                MenuItem(R.string.action_check_all) { menu = false; vm.setAllChecked(true) }
                                MenuItem(R.string.action_uncheck_all) { menu = false; vm.setAllChecked(false) }
                                MenuItem(R.string.action_rename) { menu = false; renaming = true }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (detail != null && !state.canEdit) {
                ReadOnlyBar()
            } else if (detail != null) {
                AddBar(
                    state = addBar,
                    categories = detail.categories,
                    onTyped = vm::onTyped,
                    onChooseCategory = vm::chooseCategory,
                    onAdd = vm::add,
                    onMic = if (canDictate) onMic else null,
                )
            }
        },
        snackbarHost = { SnackbarHost(vm.held.snackbar) },
    ) { padding ->
        if (detail != null) {
            ListContent(
                detail = detail,
                state = state,
                vm = vm,
                boughtOpen = boughtOpen,
                onToggleBought = { boughtOpen = !boughtOpen },
                onClearBought = { confirmClear = true },
                onEdit = { putAwayKeyboard(); editing = it.id },
                photoOf = { vm.photoOf(it, pendingPhotos) },
                onOpenPhoto = { putAwayKeyboard(); viewing = it.id },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }

    // „Wyczyść kupione" cannot be undone, and on a shared list it empties everybody's „Kupione",
    // so it asks first.
    if (confirmClear && detail != null && detail.bought.isNotEmpty()) {
        val count = detail.bought.size
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_bought_title)) },
            text = { Text(pluralStringResource(R.plurals.clear_bought_body, count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        vm.clearChecked()
                    },
                    modifier = Modifier.testTag("confirmClearBought"),
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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

    if (askingMic) {
        AlertDialog(
            onDismissRequest = { askingMic = false },
            title = { Text(stringResource(R.string.mic_rationale_title)) },
            text = { Text(stringResource(R.string.mic_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askingMic = false
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text(stringResource(R.string.action_allow))
                }
            },
            dismissButton = { TextButton(onClick = { askingMic = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (dictation.open && detail != null) {
        DictationSheet(
            state = dictation,
            categories = detail.categories,
            voice = voice ?: phoneVoice,
            onEvent = vm::onVoice,
            onEdit = vm::editDictated,
            onChooseCategory = vm::setDictatedCategory,
            onRemember = vm::rememberDictated,
            onRemove = vm::removeDictated,
            onAddAll = vm::addDictated,
            onDismiss = vm::closeDictation,
        )
    }

    if (sorting && detail != null) {
        SortDialog(
            current = detail.view,
            onChoose = { view ->
                sorting = false
                vm.setView(view)
            },
            onDismiss = { sorting = false },
        )
    }

    val allItems = detail?.let { d -> d.sections.flatMap { it.items } + d.bought }.orEmpty()
    val edited = editing?.let { id -> allItems.firstOrNull { it.id == id } }
    if (edited != null && detail != null) {
        val photoActions = if (vm.hasPhotos && state.canEdit) {
            PhotoActions(
                ref = vm.photoOf(edited, pendingPhotos),
                busy = edited.id in photoBusy,
                load = vm::loadPhoto,
                onTake = {
                    cameraFor = edited.id
                    try {
                        takePicture.launch(CameraFile.uri(context))
                    } catch (_: ActivityNotFoundException) {
                        cameraFor = null
                        scope.launch { vm.held.snackbar.showSnackbar(resources.getString(R.string.photo_no_camera)) }
                    }
                },
                onPick = {
                    galleryFor = edited.id
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemove = { vm.removePhoto(edited.id) },
                onOpen = { viewing = edited.id },
            )
        } else {
            null
        }
        EditItemSheet(
            item = edited,
            categories = detail.categories,
            nameOf = { uid -> uid?.let { ListViewModel.displayName(state.members[it]) }?.ifEmpty { null } },
            photo = photoActions,
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
            moveTargets = moveTargets,
            onMove = { content, target, removeHere ->
                editing = null
                vm.moveTo(edited.id, content, target, removeHere)
            },
        )
    }

    val viewed = viewing?.let { id -> allItems.firstOrNull { it.id == id } }
    val viewedRef = viewed?.let { vm.photoOf(it, pendingPhotos) }
    if (viewed != null && viewedRef != null) {
        PhotoViewer(viewedRef, viewed.name, vm::loadPhoto, onDismiss = { viewing = null })
    }
}

/**
 * Where the camera app writes the photo it takes (`res/xml/photo_paths.xml`, STATE.md decision
 * 71). One file: it is read and deleted as soon as the camera returns.
 */
private object CameraFile {
    fun file(context: Context): File = File(context.cacheDir, "camera/photo.jpg").also { it.parentFile?.mkdirs() }

    fun uri(context: Context): Uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file(context))
}

/** This app's page in the system settings, where a refused microphone is turned back on. */
private fun appSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Reads a picked image with the application's resolver: it is read after the screen may be gone. */
private fun opener(context: Context, uri: Uri): () -> InputStream {
    val resolver = context.applicationContext.contentResolver
    return { resolver.openInputStream(uri) ?: throw IOException("the picked image cannot be read") }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

/** „Ania ogląda", „Ania i Tomek oglądają". */
@Composable
fun watchingText(names: List<String>): String {
    val shown = names.map { it.ifEmpty { stringResource(R.string.someone) } }
    return if (shown.size == 1) {
        stringResource(R.string.watching_one, shown.single())
    } else {
        stringResource(R.string.watching_many, shown.dropLast(1).joinToString(", "), shown.last())
    }
}

/** A viewer sees the list and cannot change it (PLAN.md *Sharing & permissions*). */
@Composable
private fun ReadOnlyBar() {
    Text(
        stringResource(R.string.read_only_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp)
            .testTag("readOnly"),
    )
}

/** „Sortowanie" (STATE.md decision 62): this user's view of this list. */
@Composable
private fun SortDialog(current: SortView, onChoose: (SortView) -> Unit, onDismiss: () -> Unit) {
    val labels = mapOf(
        SortView.DEPARTMENTS to R.string.sort_departments,
        SortView.ALPHABETICAL to R.string.sort_alphabetical,
        SortView.MANUAL to R.string.sort_manual,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_sort)) },
        text = {
            Column {
                SortView.entries.forEach { view ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = view == current, role = Role.RadioButton, onClick = { onChoose(view) })
                            .testTag("sort:${view.key}"),
                    ) {
                        RadioButton(selected = view == current, onClick = null)
                        Text(stringResource(labels.getValue(view)), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ListContent(
    detail: ListDetail,
    state: ListUiState,
    vm: ListViewModel,
    boughtOpen: Boolean,
    onToggleBought: () -> Unit,
    onClearBought: () -> Unit,
    onEdit: (Item) -> Unit,
    photoOf: (Item) -> PhotoRef?,
    onOpenPhoto: (Item) -> Unit,
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
            // The flat views have no headings (decision 62).
            section.category?.let { category ->
                item(key = "h:${category.id}") {
                    SectionHeader(category.name, Modifier.animateItem())
                }
            }
            itemsWithMoves(section.items, state, reorder, vm, onEdit, photoOf, onOpenPhoto)
        }
        if (detail.bought.isNotEmpty()) {
            item(key = "bought") {
                BoughtHeader(
                    count = detail.bought.size,
                    open = boughtOpen,
                    onToggle = onToggleBought,
                    onClear = onClearBought,
                    modifier = Modifier.animateItem(),
                )
            }
            if (boughtOpen) {
                items(detail.bought, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        onToggle = if (state.canEdit) ({ vm.toggle(item) }) else null,
                        onEdit = if (state.canEdit) ({ onEdit(item) }) else null,
                        photo = photoOf(item),
                        loadPhoto = vm::loadPhoto,
                        onOpenPhoto = { onOpenPhoto(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.itemsWithMoves(
    items: List<Item>,
    state: ListUiState,
    reorder: ReorderState,
    vm: ListViewModel,
    onEdit: (Item) -> Unit,
    photoOf: (Item) -> PhotoRef?,
    onOpenPhoto: (Item) -> Unit,
) {
    items.forEachIndexed { index, item ->
        item(key = item.id) {
            val movable = !item.checked && state.canEdit
            ItemRow(
                item = item,
                initial = state.remoteTicks[item.id],
                onToggle = if (state.canEdit) ({ vm.toggle(item) }) else null,
                onEdit = if (state.canEdit) ({ onEdit(item) }) else null,
                onQuantity = if (state.canEdit) ({ quantity, unit -> vm.setQuantity(item.id, quantity, unit) }) else null,
                photo = photoOf(item),
                loadPhoto = vm::loadPhoto,
                onOpenPhoto = { onOpenPhoto(item) },
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
 * One item, in two parts (STATE.md decision 121). The circle, a column the row's full height, ticks
 * it (or, in „Kupione", brings it back). A tap on the name opens the quick „−/+" for the quantity;
 * on a ticked item it brings it back instead, as the circle does. A long press on the name edits
 * it. A ticked row is struck through, and TalkBack hears „kupione" (PLAN.md Phase 3, task 7).
 * [initial] is shown on a tick someone else just made (Phase 5, task 5). A viewer gets none of
 * these. A [photo] shows as a thumbnail that opens full screen, for a viewer too (Phase 6).
 */
@Composable
private fun ItemRow(
    item: Item,
    onToggle: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onQuantity: ((Double?, String?) -> Unit)? = null,
    photo: PhotoRef? = null,
    loadPhoto: suspend (PhotoRef, Int) -> ImageBitmap? = { _, _ -> null },
    onOpenPhoto: () -> Unit = {},
    initial: String? = null,
    reorder: ReorderState? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val putAwayKeyboard = rememberPutAwayKeyboard()
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    val stateLabel = stringResource(if (item.checked) R.string.state_bought else R.string.state_to_buy)
    val toggleLabel = stringResource(if (item.checked) R.string.action_uncheck else R.string.action_check)
    val quantity = QuantityFormat.format(item.quantity, item.unit)
    var adjusting by remember { mutableStateOf(false) }
    val tick: (() -> Unit)? = onToggle?.let { toggle ->
        {
            haptics.performHapticFeedback(if (item.checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            toggle()
        }
    }
    // A ticked item's quantity is not worth changing: its name brings it back, as the circle does.
    val onName: (() -> Unit)? = when {
        item.checked -> tick
        onQuantity != null -> ({
            putAwayKeyboard()
            adjusting = true
        })
        else -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 56.dp)
            .testTag("item:${item.name}"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxHeight()
                .width(TICK_WIDTH)
                .clickable(enabled = tick != null, role = Role.Checkbox, onClickLabel = toggleLabel) { tick?.invoke() }
                .semantics {
                    contentDescription = item.name
                    toggleableState = ToggleableState(item.checked)
                    stateDescription = stateLabel
                }
                .testTag("tick:${item.name}"),
        ) {
            if (initial != null) {
                // Someone else's tick: their initial where the tick mark would be.
                Text(
                    initial,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .wrapContentSize(Alignment.Center)
                        .testTag("tickedBy:${item.name}"),
                )
            } else {
                Icon(
                    painterResource(if (item.checked) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked),
                    contentDescription = null,
                    tint = if (item.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val end = when {
            photo != null -> 8.dp
            reorder == null -> 16.dp
            else -> 0.dp
        }
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .combinedClickable(
                    enabled = onName != null,
                    onClickLabel = if (item.checked) toggleLabel else stringResource(R.string.action_change_quantity),
                    onLongClickLabel = onEdit?.let { stringResource(R.string.action_edit) },
                    onLongClick = onEdit,
                    onClick = { onName?.invoke() },
                )
                .semantics { customActions = moveActions(upLabel, downLabel, onMoveUp, onMoveDown) }
                .testTag("name:${item.name}")
                .padding(top = 8.dp, bottom = 8.dp, end = end),
        ) {
            Column(Modifier.alpha(if (item.checked) 0.6f else 1f)) {
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
            if (onQuantity != null) {
                QuantityMenu(item, expanded = adjusting && !item.checked, onSet = onQuantity, onDismiss = { adjusting = false })
            }
        }
        if (photo != null) {
            PhotoThumbnail(
                photo,
                item.name,
                40.dp,
                loadPhoto,
                onOpenPhoto,
                Modifier.padding(end = if (reorder == null) 16.dp else 0.dp),
            )
        }
        if (reorder != null) {
            DragHandle(reorder, item.id, Modifier.size(48.dp))
        }
    }
}

/**
 * The quick menu under an item's name (STATE.md decisions 121 and 123): „−/+", the number itself
 * (a tap types one), and the unit. Every change is saved at once; a tap outside or „Wstecz" closes
 * it. „−" on the last step clears the quantity, never the item. While it is open it remembers each
 * unit's number, so 100 g → szt. (1) → g is 100 g again; closing it forgets them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantityMenu(item: Item, expanded: Boolean, onSet: (Double?, String?) -> Unit, onDismiss: () -> Unit) {
    // Taps land faster than Room answers: count from what this menu last set, not from the row.
    var shown by remember(item.id, expanded) { mutableStateOf(item.quantity) }
    var unit by remember(item.id, expanded) { mutableStateOf(item.unit) }
    val remembered = remember(item.id, expanded) { mutableMapOf<String, Pair<Double?, String?>>() }
    var typing by remember(item.id, expanded) { mutableStateOf(false) }
    var draft by remember(item.id, expanded) { mutableStateOf(TextFieldValue()) }
    val typed = QuantityFormat.parse(draft.text)
    fun set(value: Double?, newUnit: String? = unit) {
        shown = value
        unit = newUnit
        onSet(value, newUnit)
    }
    fun choose(option: String) {
        if (QuantityStep.key(option) == QuantityStep.key(unit)) return
        remembered[QuantityStep.key(unit)] = shown to unit
        val (value, spelling) = remembered[QuantityStep.key(option)] ?: (QuantityStep.start(option) to option)
        set(value, spelling)
    }
    fun finishTyping() {
        if (typed.isFailure) return
        set(typed.getOrNull())
        typing = false
    }
    // The item's own unit („ząbki", „opak.") stays on offer beside the usual ones.
    val options = remember(item.id, expanded) {
        val own = item.unit?.takeIf { u -> QuantityStep.UNITS.none { QuantityStep.key(it) == QuantityStep.key(u) } }
        listOfNotNull(own) + QuantityStep.UNITS
    }
    FocusSafeDropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            // Closing keeps a number typed so far, but an emptied field is no reason to lose one.
            if (typing) typed.getOrNull()?.let { set(it) }
            onDismiss()
        },
    ) {
        Column(Modifier.padding(horizontal = 8.dp).testTag("quantityMenu")) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                IconButton(
                    onClick = { set(QuantityStep.down(shown, unit)) },
                    enabled = shown != null && !typing,
                    modifier = Modifier.testTag("quantityDown"),
                ) {
                    Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.action_quantity_down))
                }
                if (typing) {
                    val field = remember { FocusRequester() }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        isError = typed.isFailure,
                        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishTyping() }),
                        modifier = Modifier
                            .width(112.dp)
                            .focusRequester(field)
                            .testTag("quantityField"),
                    )
                    LaunchedEffect(Unit) { field.requestFocus() }
                } else {
                    Text(
                        QuantityFormat.format(shown, unit) ?: stringResource(R.string.quantity_none),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(min = 112.dp)
                            .clickable(onClickLabel = stringResource(R.string.action_type_quantity)) {
                                val text = shown?.let(QuantityFormat::number).orEmpty()
                                draft = TextFieldValue(text, TextRange(0, text.length))
                                typing = true
                            }
                            .padding(vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("quantityValue"),
                    )
                }
                IconButton(
                    onClick = { set(QuantityStep.up(shown, unit)) },
                    enabled = !typing,
                    modifier = Modifier.testTag("quantityUp"),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.action_quantity_up))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = QuantityStep.key(option) == QuantityStep.key(unit),
                        onClick = {
                            if (typing) finishTyping()
                            choose(option)
                        },
                        label = { Text(option) },
                        modifier = Modifier.testTag("unit:$option"),
                    )
                }
            }
        }
    }
}

/**
 * The add bar keeps its focus after an item is added, so that the next one can follow; whatever
 * opens over the list lets go of it first, or the list's window would bring its keyboard back up
 * when it takes the focus back (STATE.md decision 122).
 */
@Composable
private fun rememberPutAwayKeyboard(): () -> Unit {
    val focus = LocalFocusManager.current
    return remember(focus) { { focus.clearFocus() } }
}

/** The circle's column: the same 56 dp the circle and its gap always took, now all of it a target. */
private val TICK_WIDTH = 56.dp
