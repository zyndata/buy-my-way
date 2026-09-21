package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The order of the lists on the home screen, as list ids (STATE.md decision 44). It is this
 * user's own and stays on this device until Phase 4 moves it to `/users/{uid}/prefs`; lists it
 * does not name are shown after the ones it does, oldest first.
 */
class ListOrderPreferences(private val dataStore: DataStore<Preferences>) {

    val order: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    }

    suspend fun setOrder(listIds: List<String>) {
        dataStore.edit { it[KEY] = listIds.distinct().joinToString(",") }
    }

    private companion object {
        val KEY = stringPreferencesKey("listOrder")
    }
}
