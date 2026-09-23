package dev.gorny.buymyway.data.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteLists
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This device's FCM registration at `/fcmTokens/{uid}/{token}` (PLAN.md Phase 9, task 1;
 * STATE.md decision 98). Written by its own user, read by nobody — the Apps Script reads it
 * with the project owner's own token, which is what lets it push without a service-account key.
 *
 * The token last written is kept in DataStore, so a sign-out can remove exactly that node and
 * a refresh can tell a new token from the one already up there. Nothing here retries: an
 * unwritten token means no push until the next catch-up, and a catch-up comes every 3 hours.
 */
class PushTokens(
    private val remote: RemoteLists,
    private val dataStore: DataStore<Preferences>,
    /** `FirebaseMessaging.getInstance().token`, or null when Play services cannot answer. */
    private val currentToken: suspend () -> String?,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /**
     * Makes sure RTDB holds this device's token for [uid]. Called once per process at the first
     * sync after sign-in, and again whenever FCM hands over a new one.
     */
    suspend fun ensureRegistered(uid: String): Boolean {
        val token = withTimeoutOrNull(timeoutMs) { currentToken() } ?: return false
        val known = dataStore.data.first()[REGISTERED]
        if (known == "$uid/$token") return true
        // A token this phone registered for the same account under another value is stale.
        if (known != null && known.startsWith("$uid/")) {
            write(RemoteWrites.removeToken(uid, known.removePrefix("$uid/")))
        }
        if (!write(RemoteWrites.registerToken(uid, token))) return false
        dataStore.edit { it[REGISTERED] = "$uid/$token" }
        return true
    }

    /** Sign-out and „Usuń moje dane": the node goes before the session that may write it does. */
    suspend fun unregister(uid: String) {
        val known = dataStore.data.first()[REGISTERED]?.takeIf { it.startsWith("$uid/") }?.removePrefix("$uid/")
            ?: withTimeoutOrNull(timeoutMs) { currentToken() }
            ?: return
        write(RemoteWrites.removeToken(uid, known))
        dataStore.edit { it.remove(REGISTERED) }
    }

    private suspend fun write(paths: Map<String, Any?>): Boolean = try {
        acknowledged(remote.update(paths)) != null
    } catch (_: RemoteDenied) {
        false
    } catch (_: Exception) {
        false
    }

    private suspend fun acknowledged(pending: Deferred<Unit>): Unit? =
        withTimeoutOrNull(timeoutMs) { pending.await() }

    private companion object {
        /** `{uid}/{token}`: which token this phone last wrote, and for whom. */
        val REGISTERED = stringPreferencesKey("push.registeredToken")
        const val TIMEOUT_MS = 15_000L
    }
}
