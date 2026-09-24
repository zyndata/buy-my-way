package dev.gorny.buymyway.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.gorny.buymyway.AppContainer
import dev.gorny.buymyway.BuyMyWayApp
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.CategoryInfo
import dev.gorny.buymyway.ui.categories.CategoryEdits
import dev.gorny.buymyway.ui.categories.CategoryOrderScreen
import dev.gorny.buymyway.ui.categories.CategoryOrderViewModel
import dev.gorny.buymyway.ui.imports.ImportScreen
import dev.gorny.buymyway.ui.imports.ImportViewModel
import dev.gorny.buymyway.ui.list.ListScreen
import dev.gorny.buymyway.ui.list.ListViewModel
import dev.gorny.buymyway.ui.lists.ListsScreen
import dev.gorny.buymyway.ui.lists.ListsViewModel
import dev.gorny.buymyway.ui.products.OwnProductsScreen
import dev.gorny.buymyway.ui.products.OwnProductsViewModel
import dev.gorny.buymyway.ui.settings.AboutScreen
import dev.gorny.buymyway.ui.settings.SettingsScreen
import dev.gorny.buymyway.ui.settings.SettingsViewModel
import dev.gorny.buymyway.ui.share.InviteScreen
import dev.gorny.buymyway.ui.share.InviteViewModel
import dev.gorny.buymyway.ui.share.ShareScreen
import dev.gorny.buymyway.ui.share.ShareViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map

/** The app's routes, as PLAN.md's "Screens & navigation" names them. */
object Routes {
    const val LISTS = "lists"
    const val LIST = "list/{listId}"
    const val LIST_CATEGORIES = "list/{listId}/categories"
    const val SHARE = "list/{listId}/share"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val DEFAULT_ORDER = "settings/categories"
    const val OWN_PRODUCTS = "settings/products"
    const val ABOUT = "settings/about"
    const val INVITE = "invite/{token}"

    fun list(listId: String) = "list/$listId"
    fun listCategories(listId: String) = "list/$listId/categories"
    fun share(listId: String) = "list/$listId/share"
    fun invite(token: String) = "invite/$token"
}

private val listIdArgument = listOf(navArgument("listId") { type = NavType.StringType })

/**
 * [invites] carries the token of an invite link the app was opened with, until it is shown, and
 * [imports] the text shared into the app, until the import screen has taken it.
 */
@Composable
fun BuyMyWayNavHost(
    invites: MutableStateFlow<String?> = MutableStateFlow(null),
    imports: MutableStateFlow<String?> = MutableStateFlow(null),
    /** The list a tapped notification asks for (Phase 9, decision 95), until it is opened. */
    opens: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val container = (LocalContext.current.applicationContext as BuyMyWayApp).container
    val nav = rememberNavController()
    val invite by invites.collectAsStateWithLifecycle()
    val shared by imports.collectAsStateWithLifecycle()
    val opening by opens.collectAsStateWithLifecycle()
    LaunchedEffect(invite) {
        val token = invite ?: return@LaunchedEffect
        invites.value = null
        nav.navigate(Routes.invite(token))
    }
    // A notification was tapped: its list, already caught up by the worker the push started.
    LaunchedEffect(opening) {
        val listId = opening ?: return@LaunchedEffect
        opens.value = null
        nav.navigate(Routes.list(listId))
    }
    // The text itself stays in the flow: the import screen's view model takes it when it is
    // built, which is the one place that may not miss it.
    LaunchedEffect(shared) {
        if (shared != null) nav.navigate(Routes.IMPORT)
    }
    NavHost(navController = nav, startDestination = Routes.LISTS) {
        composable(Routes.LISTS) {
            ListsScreen(
                vm = viewModel { ListsViewModel(container.lists, container.listOrder, container.appScope, container.listsSync) },
                onOpenList = { nav.navigate(Routes.list(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenShare = { nav.navigate(Routes.share(it)) },
                onPasteImport = { imports.value = it },
            )
        }
        composable(Routes.LIST, arguments = listIdArgument) { entry ->
            val listId = entry.arguments?.getString("listId").orEmpty()
            ListScreen(
                vm = viewModel { listViewModel(container, listId) },
                onBack = { nav.popBackStack(Routes.LIST, inclusive = true) },
                onOpenCategoryOrder = { nav.navigate(Routes.listCategories(listId)) },
                onOpenShare = { nav.navigate(Routes.share(listId)) },
            )
        }
        composable(Routes.LIST_CATEGORIES, arguments = listIdArgument) { entry ->
            val listId = entry.arguments?.getString("listId").orEmpty()
            CategoryOrderScreen(
                vm = viewModel { listCategoryOrder(container, listId) },
                title = R.string.title_category_order,
                onBack = { nav.popBackStack(Routes.LIST_CATEGORIES, inclusive = true) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = viewModel {
                    SettingsViewModel(
                        account = container.account.state,
                        pendingOps = container.pendingOps,
                        signInWith = container::signIn,
                        signOutAll = container::signOut,
                        notifications = container.notificationPrefs,
                        deleteEverything = container::deleteAllMyData,
                        theme = container.themePrefs,
                    )
                },
                onBack = { nav.popBackStack(Routes.SETTINGS, inclusive = true) },
                onOpenDefaultOrder = { nav.navigate(Routes.DEFAULT_ORDER) },
                onOpenOwnProducts = { nav.navigate(Routes.OWN_PRODUCTS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack(Routes.ABOUT, inclusive = true) })
        }
        composable(Routes.OWN_PRODUCTS) {
            OwnProductsScreen(
                vm = viewModel { ownProducts(container) },
                onBack = { nav.popBackStack(Routes.OWN_PRODUCTS, inclusive = true) },
            )
        }
        composable(Routes.DEFAULT_ORDER) {
            CategoryOrderScreen(
                vm = viewModel { defaultCategoryOrder(container) },
                title = R.string.title_default_order,
                onBack = { nav.popBackStack(Routes.DEFAULT_ORDER, inclusive = true) },
            )
        }
        composable(Routes.SHARE, arguments = listIdArgument) { entry ->
            val listId = entry.arguments?.getString("listId").orEmpty()
            ShareScreen(
                vm = viewModel { ShareViewModel(container.lists, listId, container.sharing, container.account.state) },
                onBack = { nav.popBackStack(Routes.SHARE, inclusive = true) },
                onLeft = { nav.popBackStack(Routes.LISTS, inclusive = false) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.INVITE, arguments = listOf(navArgument("token") { type = NavType.StringType })) { entry ->
            val token = entry.arguments?.getString("token").orEmpty()
            InviteScreen(
                vm = viewModel { InviteViewModel(token, container.sharing, container.account.state) },
                onBack = { nav.popBackStack(Routes.INVITE, inclusive = true) },
                onOpenList = { listId ->
                    nav.navigate(Routes.list(listId)) { popUpTo(Routes.INVITE) { inclusive = true } }
                },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(
                // Taking the text here empties the flow, so leaving and coming back cannot
                // import it a second time, and the navigate above does not fire again.
                vm = viewModel { ImportViewModel(imports.getAndUpdate { null }.orEmpty(), container.lists) },
                onBack = { nav.popBackStack(Routes.IMPORT, inclusive = true) },
                onImported = { listId, _ ->
                    nav.navigate(Routes.list(listId)) { popUpTo(Routes.IMPORT) { inclusive = true } }
                },
            )
        }
    }
}

private fun listViewModel(container: AppContainer, listId: String) = ListViewModel(
    repo = container.lists,
    listId = listId,
    dictionary = container::suggestNames,
    knownNames = container::knownNames,
    commitScope = container.appScope,
    live = container.listLive,
    photos = container.photos,
)

private fun listCategoryOrder(container: AppContainer, listId: String): CategoryOrderViewModel {
    val repo = container.lists
    return CategoryOrderViewModel(
        categories = repo.observeList(listId).map { it?.categories.orEmpty() },
        save = { repo.setCategoryOrder(listId, it) },
        edits = CategoryEdits(
            add = { repo.addCategory(listId, it) },
            rename = { id, name -> repo.renameCategory(listId, id, name) },
            delete = { repo.deleteCategory(listId, it) },
        ),
    )
}

private fun ownProducts(container: AppContainer): OwnProductsViewModel {
    val repo = container.lists
    return OwnProductsViewModel(
        products = repo.observeOwnProducts(),
        store = { name, categoryId, replacing -> repo.setOwnProduct(name, categoryId, replacing) },
        delete = { repo.deleteOwnProduct(it) },
        commitScope = container.appScope,
    )
}

private fun defaultCategoryOrder(container: AppContainer): CategoryOrderViewModel {
    val names = BuiltinCategories.ALL.toMap()
    return CategoryOrderViewModel(
        categories = container.categoryOrder.order.map { ids -> ids.map { CategoryInfo(it, names.getValue(it), builtin = true) } },
        save = { container.categoryOrder.setOrder(it) },
        edits = null,
    )
}
