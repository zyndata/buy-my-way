package dev.gorny.buymyway.ui.products

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.data.OwnProduct

/**
 * Ustawienia → „Moje produkty" (PLAN.md Phase 8b, task 3): add, rename, change the department,
 * delete with „Cofnij". The department is one of the nine built-in ones, because this list
 * belongs to the account and a list's own categories do not (STATE.md decision 89).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnProductsScreen(vm: OwnProductsViewModel, onBack: () -> Unit) {
    val products by vm.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_own_products)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(vm.held.snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_product)) },
                modifier = Modifier.testTag("addProduct"),
            )
        },
    ) { padding ->
        val rows = products ?: return@Scaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("products"),
        ) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.own_products_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.own_products_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag("productsEmpty"),
                    )
                }
            }
            items(rows, key = { it.key }) { product ->
                ProductRow(
                    product = product,
                    onEdit = { editing = product.key },
                    onDelete = {
                        vm.delete(
                            key = product.key,
                            message = resources.getString(R.string.deleted_product, product.name),
                            undoLabel = resources.getString(R.string.action_undo),
                        )
                    },
                )
            }
        }
    }

    if (adding) {
        ProductDialog(
            title = stringResource(R.string.add_product_title),
            onConfirm = { name, categoryId ->
                adding = false
                vm.save(name, categoryId)
            },
            onDismiss = { adding = false },
        )
    }
    val edited = editing?.let { key -> products.orEmpty().firstOrNull { it.key == key } }
    if (edited != null) {
        ProductDialog(
            title = stringResource(R.string.edit_product_title),
            initialName = edited.name,
            initialCategoryId = edited.categoryId,
            onConfirm = { name, categoryId ->
                editing = null
                // A renamed product has a different key, so the old entry is replaced.
                vm.save(name, categoryId, replacing = edited.key)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ProductRow(product: OwnProduct, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val department = BuiltinCategories.ALL.firstOrNull { it.first == product.categoryId }?.second.orEmpty()
    val description = stringResource(R.string.product_description, product.name, department)
    ListItem(
        headlineContent = { Text(product.name) },
        supportingContent = { Text(department) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu:${product.name}")) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menu = false
                            onEdit()
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
        },
        modifier = Modifier
            .clickable(onClick = onEdit)
            .testTag("product:${product.name}")
            .semantics { contentDescription = description },
    )
}

/** One product: its name and the department it belongs to. Blank cannot be confirmed. */
@Composable
private fun ProductDialog(
    title: String,
    initialName: String = "",
    initialCategoryId: String = BuiltinCategories.FALLBACK,
    onConfirm: (name: String, categoryId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length)))
    }
    var categoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank()
    val confirm = { if (valid) onConfirm(value.text.trim(), categoryId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.product_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag("productName"),
                )
                Box {
                    TextButton(onClick = { picking = true }, modifier = Modifier.testTag("productCategory")) {
                        Text(
                            stringResource(R.string.product_category_label) + ": " +
                                BuiltinCategories.ALL.first { it.first == categoryId }.second,
                        )
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        BuiltinCategories.ALL.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    picking = false
                                    categoryId = id
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = valid, modifier = Modifier.testTag("saveProduct")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
