package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.sync.NodeCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * „Sortowanie", chosen per list by this user (STATE.md decisions 62 and 67): one stamped
 * preference per list in DataStore, and once signed in `/users/{uid}/prefs/listSort/{listId}`.
 * Another member's choice never changes this one.
 */
class ListSortPreferences(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun pref(listId: String) = StampedPreference(dataStore, "$PREFIX$listId", clock)

    fun view(listId: String): Flow<SortView> {
        val key = pref(listId).key
        return dataStore.data.map { SortView.of(it[key]) }.distinctUntilChanged()
    }

    suspend fun set(listId: String, view: SortView) = pref(listId).set(view.key)

    /** Every list's choice made or read on this phone, by list id. */
    suspend fun all(): Map<String, NodeCodec.Stamped> {
        val ids = dataStore.data.first().asMap().keys.map { it.name }
            .filter { it.startsWith(PREFIX) && !it.endsWith("At") }
            .map { it.removePrefix(PREFIX) }
        return ids.mapNotNull { id -> pref(id).stamped()?.let { id to it } }.toMap()
    }

    /** RTDB's copy for [listId], kept if newer. */
    suspend fun applyRemote(listId: String, remote: NodeCodec.Stamped): Boolean = pref(listId).applyRemote(remote)

    private companion object {
        const val PREFIX = "listSort."
    }
}
