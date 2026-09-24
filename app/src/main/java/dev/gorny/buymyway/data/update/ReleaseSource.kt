package dev.gorny.buymyway.data.update

import android.util.Log
import dev.gorny.buymyway.core.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Where the latest release is read from. The app has one; a test stands in for it. */
fun interface ReleaseSource {
    /** The `releases/latest` document, or null when it could not be read. */
    suspend fun latest(): String?
}

/**
 * The second and last plain HTTPS call the app makes outside Firebase (PLAN.md Phase 10, task
 * 3): one unauthenticated `GET` of a public JSON document, at most once a day.
 * `HttpURLConnection`, not OkHttp, for the reason decision 92 gave and decision 109 repeats.
 *
 * Nothing about the user travels with it — no token, no account, no list, not even a
 * `User-Agent` beyond the platform's own. GitHub sees an anonymous request for a public page.
 */
class GitHubReleases(
    private val url: String = Updates.LATEST_RELEASE_URL,
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : ReleaseSource {
    override suspend fun latest(): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = open(url).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 404 until the first release exists, 403 if the anonymous hourly limit is
                // spent. Both mean „ask again tomorrow", and neither is worth a word to the user.
                Log.i(TAG, "releases/latest answered HTTP $code")
                return@withContext null
            }
            readAtMost(connection)
        } catch (_: IOException) {
            null // no network, or GitHub did not answer
        } finally {
            connection?.disconnect()
        }
    }

    /** At most [MAX_BYTES], however much the other end offers. */
    private fun readAtMost(connection: HttpURLConnection): String {
        val text = StringBuilder()
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8 * 1024)
            while (text.length < MAX_BYTES) {
                val read = reader.read(buffer)
                if (read < 0) break
                text.appendRange(buffer, 0, read)
            }
        }
        return text.toString()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000

        /** A release document is a few kilobytes; this only stops a hostile one growing. */
        const val MAX_BYTES = 256 * 1024
        const val TAG = "BuyMyWayUpdate"
    }
}
