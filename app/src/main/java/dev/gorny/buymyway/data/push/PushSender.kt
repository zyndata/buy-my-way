package dev.gorny.buymyway.data.push

import android.util.Log
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.push.PushSignal
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.remote.RemoteLists
import dev.gorny.buymyway.data.remote.asNode
import dev.gorny.buymyway.data.sync.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks the Apps Script to push about a list that has just changed (PLAN.md *Push sender*,
 * STATE.md decision 93).
 *
 * What it will not do, and why:
 * - **Nothing is sent before RTDB has acknowledged the change**, so a push can never announce
 *   something the rules refused.
 * - **One message per list, after [debounceMs] of quiet**, so ticking ten items is one push.
 * - **Nothing at all** for a private list, for a list whose only member is this user, or when
 *   every other member's presence is live: their screens already have the change, which is
 *   what PLAN.md keeps presence for. Every skip here is a phone that is not woken.
 * - **A failure is not retried.** A missed push costs the other phone its 3-hourly catch-up,
 *   and a retry loop costs both phones their battery.
 */
class PushSender(
    private val endpoint: PushEndpoint?,
    private val remote: RemoteLists,
    private val members: suspend (String) -> List<Member>,
    private val me: suspend () -> Me?,
    private val idToken: suspend () -> String?,
    private val connection: Connection,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    data class Me(val uid: String, val name: String?)

    private val mutex = Mutex()
    private val pending = mutableMapOf<String, PushSignal.Counts>()
    private val timers = mutableMapOf<String, Job>()

    /** Whether pushing is possible at all: without a URL the app simply never asks (decision 93). */
    val configured: Boolean get() = endpoint != null

    /** Changes RTDB has acknowledged, by list. Sent when the list has been quiet for 5 s. */
    suspend fun changed(byList: Map<String, PushSignal.Counts>) {
        if (endpoint == null) return
        mutex.withLock {
            for ((listId, counts) in byList) {
                if (counts.isEmpty) continue
                pending[listId] = (pending[listId] ?: PushSignal.Counts()) + counts
                timers.remove(listId)?.cancel()
                timers[listId] = scope.launch {
                    delay(debounceMs)
                    send(listId, PushSignal.KIND_CHANGES)
                }
            }
        }
    }

    /** A list has just been shared with somebody: one message, on its own channel. */
    suspend fun shared(listId: String) {
        if (endpoint == null) return
        send(listId, PushSignal.KIND_SHARED)
    }

    /**
     * Sends whatever is waiting, now. A background flush ([dev.gorny.buymyway.data.sync.OutboxWorker])
     * calls it before it finishes, because the process may not live long enough for a timer.
     */
    suspend fun flushNow() {
        if (endpoint == null) return
        val waiting = mutex.withLock {
            timers.values.forEach { it.cancel() }
            timers.clear()
            pending.keys.toList()
        }
        waiting.forEach { send(it, PushSignal.KIND_CHANGES) }
    }

    private suspend fun send(listId: String, kind: String) {
        val counts = mutex.withLock {
            timers.remove(listId)
            pending.remove(listId)
        } ?: PushSignal.Counts()
        if (kind == PushSignal.KIND_CHANGES && counts.isEmpty) return

        val self = me() ?: return
        val others = members(listId).map { it.uid }.filter { it != self.uid }
        if (others.isEmpty()) return // private, or nobody else on it: nothing to wake

        if (kind == PushSignal.KIND_CHANGES && others.all { it in watching(listId) }) return

        val token = idToken() ?: return
        val sent = endpoint?.send(token, listId, kind, counts) ?: false
        // Only the outcome is logged, never the list id or the token.
        if (!sent) Log.i(TAG, "push not sent")
    }

    /** Who has [listId] on screen right now (`/lists/{listId}/presence`, decision 65). */
    private suspend fun watching(listId: String): Set<String> = try {
        connection.hold {
            withTimeoutOrNull(READ_TIMEOUT_MS) { remote.read(RemoteWrites.presence(listId)).asNode().keys }
        }.orEmpty()
    } catch (_: Exception) {
        // Unreadable or no answer: assume nobody is looking and let the push go.
        emptySet()
    }

    companion object {
        /** PLAN.md *Push sender*: „debounced 5 s per list". */
        const val DEBOUNCE_MS = 5_000L
        private const val READ_TIMEOUT_MS = 10_000L
        private const val TAG = "BuyMyWayPush"
    }
}
