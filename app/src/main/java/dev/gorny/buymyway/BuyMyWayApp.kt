package dev.gorny.buymyway

import android.app.Application
import android.content.Context
import dev.gorny.buymyway.core.categorize.Categorizer
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.prefs.CategoryOrderPreferences
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.data.prefs.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BuyMyWayApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** The app's single instances, created on first use. No DI framework: there are a handful. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.open(appContext) }

    val categoryOrder: CategoryOrderPreferences by lazy { CategoryOrderPreferences(appContext.settingsDataStore) }

    val listOrder: ListOrderPreferences by lazy { ListOrderPreferences(appContext.settingsDataStore) }

    /**
     * Work that must finish even when the screen that started it is gone: a delete committed
     * as its „Cofnij" snackbar closes (STATE.md decision 51). Lives as long as the process.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val categorizerLock = Mutex()
    private var categorizer: Categorizer? = null

    /** The dictionary is ~20 kB of JSON, read from assets once, off the main thread. */
    suspend fun categorizer(): Categorizer = categorizerLock.withLock {
        categorizer ?: withContext(Dispatchers.IO) {
            appContext.assets.open(PRODUCTS_ASSET).bufferedReader().use { Categorizer.fromJson(it.readText()) }
        }.also { categorizer = it }
    }

    /** Dictionary names for the add bar's autocomplete. */
    suspend fun suggestNames(typed: String, limit: Int): List<String> = categorizer().suggest(typed, limit)

    val lists: ListRepository by lazy {
        ListRepository(
            db = database,
            categoryOrder = categoryOrder,
            categorize = { name -> categorizer().categorize(name) },
        )
    }

    private companion object {
        const val PRODUCTS_ASSET = "products-pl.json"
    }
}
