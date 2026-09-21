package dev.gorny.buymyway.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.gorny.buymyway.core.model.BuiltinCategories
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where a new list's category order comes from. */
fun interface CategoryOrderSource {
    suspend fun defaultOrder(): List<String>
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The user's default category order for new lists (Ustawienia, PLAN.md *Screens*), in DataStore.
 * Without a saved order it is the nine departments in Eat My Way's walk order. Only built-in ids
 * are kept: a custom category belongs to one list.
 */
class CategoryOrderPreferences(private val dataStore: DataStore<Preferences>) : CategoryOrderSource {

    val order: Flow<List<String>> = dataStore.data.map { prefs ->
        BuiltinCategories.completeOrder(prefs[KEY]?.split(',').orEmpty())
    }

    override suspend fun defaultOrder(): List<String> = order.first()

    suspend fun setOrder(order: List<String>) {
        dataStore.edit { it[KEY] = BuiltinCategories.completeOrder(order).joinToString(",") }
    }

    private companion object {
        val KEY = stringPreferencesKey("defaultCategoryOrder")
    }
}
