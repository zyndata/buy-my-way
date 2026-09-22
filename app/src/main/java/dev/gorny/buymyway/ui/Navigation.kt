package dev.gorny.buymyway.ui

import androidx.compose.runtime.Composable
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
import dev.gorny.buymyway.ui.list.ListScreen
import dev.gorny.buymyway.ui.list.ListViewModel
import dev.gorny.buymyway.ui.lists.ListsScreen
import dev.gorny.buymyway.ui.lists.ListsViewModel
import dev.gorny.buymyway.ui.settings.SettingsScreen
import dev.gorny.buymyway.ui.settings.SettingsViewModel
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

    fun list(listId: String) = "list/$listId"
    fun listCategories(listId: String) = "list/$listId/categories"
}

private val listIdArgument = listOf(navArgument("listId") { type = NavType.StringType })

@Composable
fun BuyMyWayNavHost() {
    val container = (LocalContext.current.applicationContext as BuyMyWayApp).container
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LISTS) {
        composable(Routes.LISTS) {
            ListsScreen(
                vm = viewModel { ListsViewModel(container.lists, container.listOrder, container.appScope, container.listsSync) },
                onOpenList = { nav.navigate(Routes.list(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.LIST, arguments = listIdArgument) { entry ->
            val listId = entry.arguments?.getString("listId").orEmpty()
            ListScreen(
                vm = viewModel { listViewModel(container, listId) },
                onBack = { nav.popBackStack(Routes.LIST, inclusive = true) },
                onOpenCategoryOrder = { nav.navigate(Routes.listCategories(listId)) },
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
                    )
                },
                onBack = { nav.popBackStack(Routes.SETTINGS, inclusive = true) },
                onOpenDefaultOrder = { nav.navigate(Routes.DEFAULT_ORDER) },
            )
        }
        composable(Routes.DEFAULT_ORDER) {
            CategoryOrderScreen(
                vm = viewModel { defaultCategoryOrder(container) },
                title = R.string.title_default_order,
                onBack = { nav.popBackStack(Routes.DEFAULT_ORDER, inclusive = true) },
            )
        }
        // Filled by Phase 5 (Udostępnianie) and Phase 8 (Import); nothing links here yet.
        composable(Routes.SHARE, arguments = listIdArgument) {
            PlaceholderScreen(title = R.string.title_share, onBack = nav::popBackStack)
        }
        composable(Routes.IMPORT) {
            PlaceholderScreen(title = R.string.title_import, onBack = nav::popBackStack)
        }
    }
}

private fun listViewModel(container: AppContainer, listId: String) = ListViewModel(
    repo = container.lists,
    listId = listId,
    dictionary = container::suggestNames,
    commitScope = container.appScope,
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

private fun defaultCategoryOrder(container: AppContainer): CategoryOrderViewModel {
    val names = BuiltinCategories.ALL.toMap()
    return CategoryOrderViewModel(
        categories = container.categoryOrder.order.map { ids -> ids.map { CategoryInfo(it, names.getValue(it), builtin = true) } },
        save = { container.categoryOrder.setOrder(it) },
        edits = null,
    )
}
