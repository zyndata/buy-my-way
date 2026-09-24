package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.gorny.buymyway.core.push.PushSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Whether this **phone** says anything when a shared list changes (PLAN.md Phase 9, task 3;
 * STATE.md decision 94). It is a device setting, not an account one, and it is off until the
 * user turns it on — which is the moment `POST_NOTIFICATIONS` is asked for, never at start.
 *
 * The per-kind switches default to on, so turning the master switch on is the only thing a
 * person has to do to hear about a shared list.
 */
class NotificationPreferences(private val dataStore: DataStore<Preferences>) {
    data class Switches(
        val enabled: Boolean = false,
        val added: Boolean = true,
        val checked: Boolean = true,
        val shared: Boolean = true,
    ) {
        /** Whether a message of [kind] may be shown at all. */
        fun allows(kind: String): Boolean = enabled && when (kind) {
            PushSignal.KIND_SHARED -> shared
            else -> added || checked
        }

        /** Which of the three numbers of a change message this phone wants to hear about. */
        fun filter(counts: PushSignal.Counts): PushSignal.Counts = PushSignal.Counts(
            added = if (added) counts.added else 0,
            checked = if (checked) counts.checked else 0,
            // „Zmienione" has no switch of its own: it rides with „dodane" (decision 94).
            changed = if (added) counts.changed else 0,
        )
    }

    val switches: Flow<Switches> = dataStore.data.map { prefs ->
        Switches(
            enabled = prefs[ENABLED] ?: false,
            added = prefs[ADDED] ?: true,
            checked = prefs[CHECKED] ?: true,
            shared = prefs[SHARED] ?: true,
        )
    }

    suspend fun current(): Switches = switches.first()

    suspend fun setEnabled(on: Boolean) = set(ENABLED, on)

    suspend fun setAdded(on: Boolean) = set(ADDED, on)

    suspend fun setChecked(on: Boolean) = set(CHECKED, on)

    suspend fun setShared(on: Boolean) = set(SHARED, on)

    private suspend fun set(key: Preferences.Key<Boolean>, on: Boolean) {
        dataStore.edit { it[key] = on }
    }

    // --- The per-list tally a notification is built from (decision 95) ----------------------

    private fun tallyKey(listId: String) = stringPreferencesKey("$TALLY_PREFIX$listId")

    /** Adds one message to [listId]'s tally and returns what the notification should now say. */
    suspend fun addToTally(listId: String, counts: PushSignal.Counts, actor: String?): PushSignal.Tally {
        val key = tallyKey(listId)
        var result = PushSignal.Tally()
        dataStore.edit { prefs ->
            result = PushSignal.decodeTally(prefs[key]).plus(counts, actor)
            prefs[key] = PushSignal.encodeTally(result)
        }
        return result
    }

    /**
     * The catch-up has read what a push could only count (decision 106): the names of what was
     * bought join [listId]'s tally. Null when there is no tally — the notification was dismissed,
     * or the list has since been opened — and then nothing is written, so names never outlive
     * the numbers they belong to.
     */
    suspend fun addBoughtNames(listId: String, names: List<String>): PushSignal.Tally? {
        val key = tallyKey(listId)
        var result: PushSignal.Tally? = null
        dataStore.edit { prefs ->
            val stored = prefs[key] ?: return@edit
            val grown = PushSignal.decodeTally(stored).plusBought(names)
            prefs[key] = PushSignal.encodeTally(grown)
            result = grown
        }
        return result
    }

    /** The list was opened, or its notification dismissed: the next push starts from zero. */
    suspend fun clearTally(listId: String) {
        dataStore.edit { it.remove(tallyKey(listId)) }
    }

    /** Which lists a tally is held for, so „Usuń moje dane" and sign-out leave none behind. */
    suspend fun talliedLists(): List<String> = dataStore.data.first().asMap().keys
        .map { it.name }
        .filter { it.startsWith(TALLY_PREFIX) }
        .map { it.removePrefix(TALLY_PREFIX) }

    private companion object {
        val ENABLED = booleanPreferencesKey("notify.enabled")
        val ADDED = booleanPreferencesKey("notify.added")
        val CHECKED = booleanPreferencesKey("notify.checked")
        val SHARED = booleanPreferencesKey("notify.shared")
        const val TALLY_PREFIX = "notify.tally."
    }
}
