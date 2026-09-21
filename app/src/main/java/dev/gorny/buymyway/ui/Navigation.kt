package dev.gorny.buymyway.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.gorny.buymyway.R

/** The app's routes, as PLAN.md's "Screens & navigation" names them. */
object Routes {
    const val LISTS = "lists"
    const val LIST = "list/{listId}"
    const val SHARE = "list/{listId}/share"
    const val IMPORT = "import"
    const val SETTINGS = "settings"

    fun list(listId: String) = "list/$listId"
    fun share(listId: String) = "list/$listId/share"
}

private val listIdArgument = listOf(navArgument("listId") { type = NavType.StringType })

/** Placeholder destinations; each later phase replaces one with the real screen. */
@Composable
fun BuyMyWayNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LISTS) {
        composable(Routes.LISTS) {
            PlaceholderScreen(
                title = R.string.title_lists,
                links = listOf(
                    R.string.placeholder_open_list to { nav.navigate(Routes.list(SAMPLE_LIST_ID)) },
                    R.string.title_import to { nav.navigate(Routes.IMPORT) },
                    R.string.title_settings to { nav.navigate(Routes.SETTINGS) },
                ),
            )
        }
        composable(Routes.LIST, arguments = listIdArgument) { entry ->
            val listId = entry.arguments?.getString("listId").orEmpty()
            PlaceholderScreen(
                title = R.string.title_list,
                onBack = nav::popBackStack,
                links = listOf(
                    R.string.placeholder_open_share to { nav.navigate(Routes.share(listId)) },
                ),
            )
        }
        composable(Routes.SHARE, arguments = listIdArgument) {
            PlaceholderScreen(title = R.string.title_share, onBack = nav::popBackStack)
        }
        composable(Routes.IMPORT) {
            PlaceholderScreen(title = R.string.title_import, onBack = nav::popBackStack)
        }
        composable(Routes.SETTINGS) {
            PlaceholderScreen(title = R.string.title_settings, onBack = nav::popBackStack)
        }
    }
}

/** Stands in for a real list id until Phase 3 has lists to open. */
private const val SAMPLE_LIST_ID = "sample"
