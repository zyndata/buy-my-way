package dev.gorny.buymyway.data.update

import dev.gorny.buymyway.core.update.Updates
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app asks GitHub, and what it does with the answer (STATE.md decision 108). The
 * once-a-day rule is the battery half of the feature, so it is tested rather than trusted.
 */
class AppUpdatesTest {
    /** A real wall clock, because „a day has passed" is measured against the epoch. */
    private val NOW = 1_800_000_000_000L

    private val document = """
        {
          "tag_name": "v1.1.0",
          "assets": [{
            "name": "buy-my-way-v1.1.0.apk",
            "browser_download_url": "https://github.com/zyndata/buy-my-way/releases/download/v1.1.0/buy-my-way-v1.1.0.apk"
          }]
        }
    """.trimIndent()

    /** The phone's DataStore, in memory. */
    private class Memory(var lastCheck: Long = 0L, var dismissed: String? = null) : AppUpdates.Store {
        override suspend fun isCheckDue(now: Long, intervalMs: Long) = lastCheck > now || now - lastCheck >= intervalMs

        override suspend fun checked(now: Long) {
            lastCheck = now
        }

        override suspend fun dismissed(): String? = dismissed

        override suspend fun dismiss(tag: String) {
            dismissed = tag
        }
    }

    private class Counting(private val answer: String?) : ReleaseSource {
        var calls = 0

        override suspend fun latest(): String? {
            calls++
            return answer
        }
    }

    private fun updates(source: ReleaseSource, store: Memory, installed: String = "1.0.0", clock: () -> Long) =
        AppUpdates(source, store, installed, clock)

    @Test
    fun `the first look asks, and a second one the same day does not`() = runTest {
        val source = Counting(document)
        val store = Memory()
        var now = NOW
        val updates = updates(source, store) { now }

        updates.checkIfDue()
        assertEquals(1, source.calls)
        assertEquals("v1.1.0", updates.update.value?.tag)

        now += 60 * 60 * 1000L // an hour later, the app is opened again
        updates.checkIfDue()
        assertEquals("asked GitHub twice in one day", 1, source.calls)
    }

    @Test
    fun `a day later it asks again`() = runTest {
        val source = Counting(document)
        val store = Memory()
        var now = NOW
        val updates = updates(source, store) { now }

        updates.checkIfDue()
        now += Updates.CHECK_INTERVAL_MS
        updates.checkIfDue()
        assertEquals(2, source.calls)
    }

    @Test
    fun `a check that could not reach GitHub is not remembered as a check`() = runTest {
        // Otherwise a phone that was offline this morning would wait until tomorrow.
        val source = Counting(null)
        val store = Memory()
        val updates = updates(source, store) { NOW }

        assertEquals(AppUpdates.Outcome.Unreachable, updates.check())
        assertEquals(0L, store.lastCheck)
        assertNull(updates.update.value)
        updates.checkIfDue()
        assertEquals(2, source.calls)
    }

    @Test
    fun `nothing is offered when this is already the newest`() = runTest {
        val updates = updates(Counting(document), Memory(), installed = "1.1.0") { NOW }
        assertEquals(AppUpdates.Outcome.UpToDate, updates.check())
        assertNull(updates.update.value)
    }

    @Test
    fun `a dismissed version is not shown again, and a newer one still is`() = runTest {
        val store = Memory()
        val updates = updates(Counting(document), store) { NOW }
        updates.check()
        assertEquals("v1.1.0", updates.update.value?.tag)

        updates.dismiss()
        assertNull(updates.update.value)
        assertEquals("v1.1.0", store.dismissed)

        // The same release, asked for again: still hidden.
        updates.check()
        assertNull(updates.update.value)

        // A later one is not.
        val newer = document.replace("1.1.0", "1.2.0")
        val next = updates(Counting(newer), store) { NOW }
        next.check()
        assertEquals("v1.2.0", next.update.value?.tag)
    }

    @Test
    fun `a clock that jumped backwards does not stop the check`() = runTest {
        val store = Memory(lastCheck = NOW + 30 * Updates.CHECK_INTERVAL_MS)
        val source = Counting(document)
        val updates = updates(source, store) { NOW } // the phone's time was corrected
        updates.checkIfDue()
        assertTrue(source.calls == 1)
    }
}
