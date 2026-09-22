package dev.gorny.buymyway.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.gorny.buymyway.core.sync.NodeCodec
import kotlinx.coroutines.flow.first

/**
 * One text preference and the time it was last set here, so that the copy in
 * `/users/{uid}/prefs` and this one can be merged by that time (STATE.md decision 59).
 */
class StampedPreference(
    private val dataStore: DataStore<Preferences>,
    name: String,
    private val clock: () -> Long,
) {
    val key = stringPreferencesKey(name)
    private val atKey = longPreferencesKey("${name}At")

    /** A change made on this phone. */
    suspend fun set(value: String) {
        dataStore.edit {
            it[key] = value
            it[atKey] = maxOf(clock(), (it[atKey] ?: 0) + 1)
        }
    }

    /** Null until it was set on this phone or arrived from RTDB. */
    suspend fun stamped(): NodeCodec.Stamped? {
        val prefs = dataStore.data.first()
        val value = prefs[key] ?: return null
        return NodeCodec.Stamped(value, prefs[atKey] ?: 0)
    }

    /** RTDB's copy, kept only if it is newer. Returns whether it was. */
    suspend fun applyRemote(remote: NodeCodec.Stamped): Boolean {
        var applied = false
        dataStore.edit {
            if (remote.updatedAt > (it[atKey] ?: 0)) {
                it[key] = remote.value
                it[atKey] = remote.updatedAt
                applied = true
            }
        }
        return applied
    }
}
