package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which account this phone's lists belong to (STATE.md decision 57): its uid, email and name,
 * which are ids and labels, never a token. It outlives a lost Firebase session, which is how the
 * app knows to ask for *that* account again instead of silently signing out.
 */
class AccountRecord(private val dataStore: DataStore<Preferences>) {
    data class Account(val uid: String, val email: String?, val name: String?)

    val account: Flow<Account?> = dataStore.data.map { prefs ->
        prefs[UID]?.let { Account(it, prefs[EMAIL], prefs[NAME]) }
    }

    suspend fun set(account: Account) {
        dataStore.edit { prefs ->
            prefs[UID] = account.uid
            account.email?.let { prefs[EMAIL] = it } ?: prefs.remove(EMAIL)
            account.name?.let { prefs[NAME] = it } ?: prefs.remove(NAME)
        }
    }

    private companion object {
        val UID = stringPreferencesKey("account.uid")
        val EMAIL = stringPreferencesKey("account.email")
        val NAME = stringPreferencesKey("account.name")
    }
}
