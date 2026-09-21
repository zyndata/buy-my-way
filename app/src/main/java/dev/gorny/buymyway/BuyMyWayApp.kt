package dev.gorny.buymyway

import android.app.Application
import android.content.Context
import dev.gorny.buymyway.core.categorize.Categorizer
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.prefs.CategoryOrderPreferences
import dev.gorny.buymyway.data.prefs.settingsDataStore
import kotlinx.coroutines.Dispatchers
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

    private val categorizerLock = Mutex()
    private var categorizer: Categorizer? = null

    /** The dictionary is ~20 kB of JSON, read from assets once, off the main thread. */
    suspend fun categorizer(): Categorizer = categorizerLock.withLock {
        categorizer ?: withContext(Dispatchers.IO) {
            appContext.assets.open(PRODUCTS_ASSET).bufferedReader().use { Categorizer.fromJson(it.readText()) }
        }.also { categorizer = it }
    }

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
