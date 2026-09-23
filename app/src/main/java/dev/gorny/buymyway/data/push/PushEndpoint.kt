package dev.gorny.buymyway.data.push

import android.util.Log
import dev.gorny.buymyway.core.push.PushSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Whoever can be asked to push. The app has one; a test stands in for it. */
interface PushEndpoint {
    /** True when the request was accepted. A refusal is not retried: see [PushSender]. */
    suspend fun send(idToken: String, listId: String, kind: String, counts: PushSignal.Counts): Boolean
}

/**
 * The one HTTPS call the app makes outside Firebase: „please push about this list" to the
 * Apps Script web app (PLAN.md *Push sender*). `HttpURLConnection`, not OkHttp — one POST of a
 * couple of hundred bytes does not earn a library (STATE.md decision 92).
 *
 * The ID token goes in the body, as the script's contract has said since Phase 0, and is never
 * logged or kept. Nothing else travels: a list id, three numbers, a kind.
 */
class AppsScriptPush(
    private val url: String,
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : PushEndpoint {
    override suspend fun send(
        idToken: String,
        listId: String,
        kind: String,
        counts: PushSignal.Counts,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject(
            mapOf(
                "idToken" to JsonPrimitive(idToken),
                PushSignal.LIST_ID to JsonPrimitive(listId),
                PushSignal.KIND to JsonPrimitive(kind),
                PushSignal.ADDED to JsonPrimitive(counts.added),
                PushSignal.CHECKED to JsonPrimitive(counts.checked),
                PushSignal.CHANGED to JsonPrimitive(counts.changed),
            ),
        ).toString().toByteArray()

        var connection: HttpURLConnection? = null
        try {
            connection = open(url).apply {
                requestMethod = "POST"
                doOutput = true
                // The script's web app answers a POST with a 302 to script.googleusercontent.com.
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "endpoint answered HTTP $code")
                return@withContext false
            }
            // The answer says what the script did; nothing here depends on it beyond `ok`.
            val answer = connection.inputStream.bufferedReader().use { it.readText() }
            val json = runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonObject
            when {
                json == null -> {
                    // An HTML page, almost always: the script's authorisation is incomplete.
                    Log.i(TAG, "endpoint did not answer JSON")
                    false
                }
                json["ok"]?.toString() == "true" -> true
                else -> {
                    // One of the script's own words — `forbidden`, `unauthenticated`,
                    // `misconfigured` — never anything the caller sent. Without this line a
                    // failed push is indistinguishable from one that was never tried.
                    Log.i(TAG, "endpoint refused: ${json["error"]?.toString()?.take(40)}")
                    false
                }
            }
        } catch (_: IOException) {
            false // no network, or the script did not answer: a missed push, nothing more
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val TAG = "BuyMyWayPush"
    }
}
