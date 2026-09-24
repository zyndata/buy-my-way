package dev.gorny.buymyway.data.update

import dev.gorny.buymyway.core.update.Updates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * „Dostępna wersja X — Pobierz" (PLAN.md Phase 10, task 3).
 *
 * The whole of the update check lives here, and all of it happens **while the user is looking
 * at the app** (STATE.md decision 108): [checkIfDue] is called when Listy is shown and does
 * nothing if GitHub was asked less than a day ago. There is no worker, no alarm and no
 * listener — a phone that does not open the app never checks, which is also a phone that
 * would never see the banner.
 */
class AppUpdates(
    private val source: ReleaseSource,
    private val prefs: Store,
    private val installedVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * What has to be remembered between checks. [UpdatePreferences] is the phone's
     * implementation; the JVM tests hand over one held in memory, which is why this is an
     * interface and not the DataStore class itself.
     */
    interface Store {
        suspend fun isCheckDue(now: Long, intervalMs: Long): Boolean

        suspend fun checked(now: Long)

        suspend fun dismissed(): String?

        suspend fun dismiss(tag: String)
    }

    private val available = MutableStateFlow<Updates.Release?>(null)

    /** The release to offer, or null: nothing newer, not checked yet, or waved away. */
    val update: StateFlow<Updates.Release?> = available.asStateFlow()

    private val lock = Mutex()

    /** What a check found. „Sprawdź aktualizacje" says each of the three in its own words. */
    sealed interface Outcome {
        data class Newer(val release: Updates.Release) : Outcome

        data object UpToDate : Outcome

        /** No network, or GitHub did not answer. Nothing is remembered as checked. */
        data object Unreachable : Outcome
    }

    /** Listy was shown. Asks GitHub only if a day has passed since the last time (decision 108). */
    suspend fun checkIfDue() {
        if (!prefs.isCheckDue(now(), Updates.CHECK_INTERVAL_MS)) return
        check()
    }

    /** „Sprawdź aktualizacje" in „O aplikacji": ask now, whatever the clock says. */
    suspend fun check(): Outcome = lock.withLock {
        // Only a successful read counts as a check: a phone that was offline this morning
        // should try again this afternoon, not tomorrow.
        val document = source.latest() ?: return@withLock Outcome.Unreachable
        prefs.checked(now())
        val offer = Updates.offer(document, installedVersion)
        available.value = offer?.takeUnless { it.tag == prefs.dismissed() }
        if (offer == null) Outcome.UpToDate else Outcome.Newer(offer)
    }

    /** „×" on the banner: this version is not mentioned again. A newer one still is. */
    suspend fun dismiss() {
        val tag = available.value?.tag ?: return
        prefs.dismiss(tag)
        available.value = null
    }
}
