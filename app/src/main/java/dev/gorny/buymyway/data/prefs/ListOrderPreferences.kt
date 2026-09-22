package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The order of the lists on the home screen, as list ids (STATE.md decision 44). It is this
 * user's own: in DataStore, and once signed in in `/users/{uid}/prefs/listOrder` (decision 59).
 * Lists it does not name are shown after the ones it does, oldest first.
 */
class ListOrderPreferences(
    dataStore: DataStore<Preferences>,
    clock: () -> Long = System::currentTimeMillis,
) {
    val stored = StampedPreference(dataStore, "listOrder", clock)

    val order: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[stored.key]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    }

    suspend fun setOrder(listIds: List<String>) {
        stored.set(listIds.distinct().joinToString(","))
    }
}
