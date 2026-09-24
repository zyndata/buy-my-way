package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.gorny.buymyway.data.update.AppUpdates
import kotlinx.coroutines.flow.first

/**
 * When the app last looked for a new release, and which one the user has already waved away
 * (PLAN.md Phase 10, task 3). A device setting like the theme and the notification switches:
 * whether *this* phone has been told about a version is a property of the phone.
 */
class UpdatePreferences(private val dataStore: DataStore<Preferences>) : AppUpdates.Store {
    /** The tag the user dismissed, so the banner does not come back for that version. */
    override suspend fun dismissed(): String? = dataStore.data.first()[DISMISSED]

    /** Whether enough time has passed to ask GitHub again (`Updates.CHECK_INTERVAL_MS`). */
    override suspend fun isCheckDue(now: Long, intervalMs: Long): Boolean {
        val last = dataStore.data.first()[LAST_CHECK] ?: 0L
        // A clock that has gone backwards (a phone whose time was corrected) would otherwise
        // stop the check for as long as the jump was.
        return last > now || now - last >= intervalMs
    }

    override suspend fun checked(now: Long) {
        dataStore.edit { it[LAST_CHECK] = now }
    }

    override suspend fun dismiss(tag: String) {
        dataStore.edit { it[DISMISSED] = tag }
    }

    private companion object {
        val LAST_CHECK = longPreferencesKey("update.lastCheck")
        val DISMISSED = stringPreferencesKey("update.dismissed")
    }
}
