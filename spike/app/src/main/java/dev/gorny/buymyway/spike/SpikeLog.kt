package dev.gorny.buymyway.spike

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-screen and Logcat log. Every result the spike produces goes through here (`adb logcat -s BMW-SPIKE`). */
object SpikeLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun i(msg: String) {
        Log.i("BMW-SPIKE", msg)
        val line = clock.format(Date()) + "  " + msg
        lines.update { (listOf(line) + it).take(300) }
    }
}

/** Median, nearest-rank p95, min and max of a sample, in milliseconds. */
fun stats(label: String, samples: List<Long>): String {
    if (samples.isEmpty()) return "$label: no samples"
    val s = samples.sorted()
    val median = if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    val p95 = s[(Math.ceil(0.95 * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
    return "$label: n=${s.size} median=${"%.0f".format(Locale.ROOT, median)} ms p95=$p95 ms " +
        "min=${s.first()} max=${s.last()}  raw=$samples"
}
