package dev.gorny.buymyway.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.ui.products.OwnProductsScreen
import dev.gorny.buymyway.ui.products.OwnProductsViewModel
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ustawienia → „Moje produkty" on the real screen (PLAN.md Phase 8b, task 3): add, rename,
 * change the department, delete with „Cofnij" as everywhere else in the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OwnProductsFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private lateinit var scope: CoroutineScope
    private val viewModels = ViewModelStore()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ListRepository(db = db, categoryOrder = { BuiltinCategories.IDS }, categorize = { "inne" })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun close() {
        viewModels.clear()
        scope.cancel()
        db.close()
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun products() = runBlocking { repo.observeOwnProducts().first().map { it.name to it.categoryId } }

    private fun show() {
        val vm = OwnProductsViewModel(
            products = repo.observeOwnProducts(),
            store = { name, categoryId, replacing -> repo.setOwnProduct(name, categoryId, replacing) },
            delete = { repo.deleteOwnProduct(it) },
            commitScope = scope,
        )
        viewModels.put("products", vm)
        compose.setContent { BuyMyWayTheme { OwnProductsScreen(vm, onBack = {}) } }
        waitFor { exists(hasTestTag("addProduct")) }
    }

    /** The menu of one row, which a person opens with the „⋮" beside that name. */
    private fun menuOf(name: String) = compose.onNodeWithTag("menu:$name")

    @Test
    fun aProductIsAddedWithItsDepartment() {
        show()
        waitFor { exists(hasTestTag("productsEmpty")) }

        compose.onNodeWithTag("addProduct").performClick()
        compose.onNodeWithTag("productName").performTextReplacement("Chleb wiejski")
        compose.onNodeWithTag("productCategory").performClick()
        compose.onNodeWithText("Pieczywo").performClick()
        compose.onNodeWithTag("saveProduct").performClick()

        waitFor { products().isNotEmpty() }
        assertEquals(listOf("Chleb wiejski" to "pieczywo"), products())
        waitFor { exists(hasTestTag("product:Chleb wiejski")) }
        assertEquals(false, exists(hasTestTag("productsEmpty")))
    }

    /** A rename and a new department, in the same dialog, leave one row. */
    @Test
    fun aProductIsRenamedAndMovedToAnotherDepartment() {
        runBlocking { repo.setOwnProduct("Chleb wiejsky", "inne") }
        show()
        waitFor { exists(hasTestTag("product:Chleb wiejsky")) }

        compose.onNodeWithTag("product:Chleb wiejsky").performClick()
        compose.onNodeWithTag("productName").performTextReplacement("Chleb wiejski")
        compose.onNodeWithTag("productCategory").performClick()
        compose.onNodeWithText("Pieczywo").performClick()
        compose.onNodeWithTag("saveProduct").performClick()

        waitFor { products() == listOf("Chleb wiejski" to "pieczywo") }
        waitFor { !exists(hasTestTag("product:Chleb wiejsky")) }
    }

    /** „Usuń" hides the row at once; „Cofnij" brings it back and nothing was written. */
    @Test
    fun aDeleteIsUndoneWithCofnij() {
        runBlocking { repo.setOwnProduct("Chleb wiejski", "pieczywo") }
        show()
        waitFor { exists(hasTestTag("product:Chleb wiejski")) }

        menuOf("Chleb wiejski").performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        waitFor { !exists(hasTestTag("product:Chleb wiejski")) }

        compose.onNodeWithText(text(R.string.action_undo)).performClick()
        waitFor { exists(hasTestTag("product:Chleb wiejski")) }
        assertEquals(listOf("Chleb wiejski" to "pieczywo"), products())
    }

    /**
     * Left alone, the delete is committed. The next delete closes the first one's snackbar,
     * which is what commits it (STATE.md decision 51), so this is that moment without a wait.
     */
    @Test
    fun aDeleteIsCommittedWhenItsSnackbarCloses() {
        runBlocking {
            repo.setOwnProduct("Chleb wiejski", "pieczywo")
            repo.setOwnProduct("Kefir malinowy", "nabial")
        }
        show()
        waitFor { exists(hasTestTag("product:Chleb wiejski")) }

        menuOf("Chleb wiejski").performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        waitFor { !exists(hasTestTag("product:Chleb wiejski")) }

        menuOf("Kefir malinowy").performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()

        // The first one is really gone now; the second is only held back.
        waitFor { products() == listOf("Kefir malinowy" to "nabial") }
        assertEquals(listOf("Kefir malinowy" to "nabial"), products())
    }
}
