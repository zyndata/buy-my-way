package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** „Motyw" in Ustawienia: which colour scheme the app draws in. */
enum class ThemeChoice(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** An unknown or missing value is „zgodnie z systemem", which is what the app starts at. */
        fun of(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Which theme this **phone** draws in. A device setting, not an account one, for the same reason
 * the notification switches are (STATE.md decision 94): another phone signed into the same
 * account keeps its own answer, and nothing about it travels to RTDB.
 */
class ThemePreferences(private val dataStore: DataStore<Preferences>) {
    val choice: Flow<ThemeChoice> = dataStore.data
        .map { ThemeChoice.of(it[CHOICE]) }
        .distinctUntilChanged()

    suspend fun set(choice: ThemeChoice) {
        dataStore.edit { it[CHOICE] = choice.key }
    }

    private companion object {
        val CHOICE = stringPreferencesKey("theme.choice")
    }
}
