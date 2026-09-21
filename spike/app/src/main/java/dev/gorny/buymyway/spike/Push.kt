package dev.gorny.buymyway.spike

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asks the Apps Script to push to the FCM token stored in its script properties. When that is
 * this phone's own token, the end-to-end latency is measured on one clock
 * (see [SpikeMessagingService]).
 */
object Push {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val samples = mutableListOf<Long>()

    @Synchronized fun received(ms: Long) { samples += ms }
    @Synchronized fun summary() = stats("PUSH end-to-end", samples.toList())

    suspend fun send(idToken: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("idToken", idToken).put("sentAt", System.currentTimeMillis()).toString()
        val t0 = SystemClock.elapsedRealtime()
        // Apps Script answers a POST with a 302 to googleusercontent.com; OkHttp follows it
        // as a GET, which is what the script expects.
        http.newCall(
            Request.Builder().url(BuildConfig.PUSH_ENDPOINT).post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { r ->
            SpikeLog.i("PUSH script answered in ${SystemClock.elapsedRealtime() - t0} ms: HTTP ${r.code} ${r.body?.string()?.take(300)}")
        }
    }
}
