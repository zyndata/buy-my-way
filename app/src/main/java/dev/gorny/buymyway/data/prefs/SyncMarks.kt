package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * How far this phone's preferences have been sent to and read from `/users/{uid}/prefs`
 * (STATE.md decision 59). Times, never content; cleared with everything else at sign-out.
 */
class SyncMarks(private val dataStore: DataStore<Preferences>) {
    enum class Mark(val key: Preferences.Key<Long>) {
        DEFAULT_ORDER_SENT(longPreferencesKey("sync.defaultOrderSentAt")),
        LIST_ORDER_SENT(longPreferencesKey("sync.listOrderSentAt")),
        MEMORY_SENT(longPreferencesKey("sync.memorySentAt")),

        /** Server time: the largest `changedAt` of a category memory entry read. */
        MEMORY_SEEN(longPreferencesKey("sync.memorySeenUpTo")),
    }

    suspend fun get(mark: Mark): Long = dataStore.data.first()[mark.key] ?: 0

    /** Marks only move forward. */
    suspend fun advance(mark: Mark, to: Long) {
        dataStore.edit { it[mark.key] = maxOf(it[mark.key] ?: 0, to) }
    }
}
