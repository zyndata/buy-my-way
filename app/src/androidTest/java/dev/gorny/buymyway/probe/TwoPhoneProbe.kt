package dev.gorny.buymyway.probe

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gorny.buymyway.AppContainer
import dev.gorny.buymyway.BuyMyWayApp
import dev.gorny.buymyway.MainActivity
import dev.gorny.buymyway.core.model.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Phase 5 latency check on two real phones (PLAN.md Phase 5, first acceptance criterion),
 * run by hand, never in CI: without the `probe` argument it is skipped. It measures an echo, as
 * the Phase 0 spike did (STATE.md decision 16), so the two phones' clocks are never compared:
 * phone A ticks „ping N", phone B ticks „pong N" the moment it sees it, and A times the round
 * trip on its own monotonic clock. Each leg is the app's whole path: Room → outbox → RTDB →
 * the other phone's listener → its Room. One way ≈ half the round trip.
 *
 * Both phones must be signed in, on accounts that share a list named [LIST], with the debug
 * build and this test APK installed. Start B first, then A (docs/DEVELOPMENT.md):
 * `adb -s <B> shell am instrument -w -e probe b -e class dev.gorny.buymyway.probe.TwoPhoneProbe dev.gorny.buymyway.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class TwoPhoneProbe {
    private val role: String? = InstrumentationRegistry.getArguments().getString("probe")

    @Test
    fun echo() {
        assumeTrue("run by hand on two phones: -e probe a|b", role == "a" || role == "b")
        val container = ApplicationProvider.getApplicationContext<BuyMyWayApp>().container
        // In the foreground, as a user would be: sync runs, the connection is open.
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withContext(Dispatchers.Default) {
                    val listId = withTimeout(60_000) {
                        container.lists.observeLists().map { lists -> lists.firstOrNull { it.list.name == LIST }?.list?.id }.first { it != null }!!
                    }
                    val present = MutableStateFlow<Set<String>>(emptySet())
                    val watching = launch { container.listLive.watch(listId).collect { present.value = it } }
                    if (role == "a") ping(container, listId, present) else pong(container, listId)
                    watching.cancel()
                }
            }
        }
    }

    private suspend fun ping(c: AppContainer, listId: String, present: StateFlow<Set<String>>) {
        val me = c.listLive.myUid()
        // A fresh start: every ping and pong there and unticked.
        for (i in 1..ROUNDS) {
            for (name in listOf("ping $i", "pong $i")) {
                val item = find(c, listId, name)
                if (item == null) c.lists.addItem(listId, name) else if (item.checked) c.lists.setChecked(item.id, false)
            }
        }
        log("waiting for the other phone to open the list")
        withTimeout(120_000) { present.first { others -> others.any { it != me } } }
        // Let B's listener catch up with the reset before the first ping.
        delay(3_000)
        val rtts = mutableListOf<Long>()
        for (i in 1..ROUNDS) {
            val ping = find(c, listId, "ping $i")!!
            val start = SystemClock.elapsedRealtime()
            c.lists.setChecked(ping.id, true)
            withTimeout(10_000) {
                c.lists.observeItems(listId).first { items -> items.any { it.name == "pong $i" && it.checked && it.checkedBy != me } }
            }
            val rtt = SystemClock.elapsedRealtime() - start
            rtts += rtt
            log("round $i: rtt $rtt ms")
            delay(1_000)
        }
        val sorted = rtts.sorted()
        val median = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
        log("RESULT n=${rtts.size} rtt median=$median p95=$p95 max=${sorted.last()} one-way median≈${median / 2} p95≈${p95 / 2} (ms)")
    }

    private suspend fun pong(c: AppContainer, listId: String) {
        val me = c.listLive.myUid()
        // A resets the list first; a pong left ticked by the last run is not the end of this one.
        withTimeout(5 * 60_000) { c.lists.observeItems(listId).first { items -> items.none { it.name == "pong $ROUNDS" && it.checked } } }
        log("answering pings")
        withTimeout(10 * 60_000) {
            c.lists.observeItems(listId).first { items ->
                for (item in items.filter { it.name.startsWith("ping ") && it.checked && it.checkedBy != me }) {
                    val pong = items.firstOrNull { it.name == "pong ${item.name.removePrefix("ping ")}" }
                    if (pong != null && !pong.checked) c.lists.setChecked(pong.id, true)
                }
                items.any { it.name == "pong $ROUNDS" && it.checked }
            }
        }
        log("done")
    }

    private suspend fun find(c: AppContainer, listId: String, name: String): Item? =
        c.lists.observeItems(listId).first().firstOrNull { it.name == name }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val LIST = "Pomiar"
        const val ROUNDS = 20
        const val TAG = "BuyMyWayProbe"
    }
}
