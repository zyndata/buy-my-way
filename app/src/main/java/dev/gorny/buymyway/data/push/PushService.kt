package dev.gorny.buymyway.data.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.gorny.buymyway.BuyMyWayApp
import dev.gorny.buymyway.core.push.PushSignal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A push has arrived (PLAN.md Phase 9, task 1). The message is data only and carries a list id,
 * three numbers and a name — never what changed (STATE.md decision 95).
 *
 * The **notification is posted here**, not in the worker: it needs Room and DataStore and
 * nothing else, and it must not wait for WorkManager to find a moment. The **catch-up** —
 * the part that needs the network — goes to a [CatchUpWorker], so a slow or absent network
 * delays the fresh data and never the notification.
 */
class PushService : FirebaseMessagingService() {

    /**
     * A new registration, or one FCM has rotated: the next catch-up writes it to RTDB.
     *
     * Deprecated in favour of `onRegistered`, which belongs to the installation-id registration
     * this app does not use (STATE.md decision 100). The token is what Phase 0 measured a push
     * arriving on, and what the script's FCM v1 `token` field takes.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        CatchUpWorker.enqueueOnce(applicationContext, listId = null)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val listId = data[PushSignal.LIST_ID]?.takeIf { it.isNotBlank() } ?: return
        val kind = data[PushSignal.KIND]?.takeIf { it.isNotBlank() } ?: PushSignal.KIND_CHANGES
        val counts = PushSignal.countsOf(data)
        val actor = data[PushSignal.ACTOR]?.takeIf { it.isNotBlank() }
        val container = (applicationContext as BuyMyWayApp).container

        // FCM allows about ten seconds here; a Room read and a DataStore edit take a few
        // milliseconds, and the budget makes sure a stuck one never costs the catch-up.
        runBlocking {
            withTimeoutOrNull(NOTIFY_BUDGET_MS) {
                container.notifyOfPush(listId, kind, counts, actor)
            }
        }
        CatchUpWorker.enqueueOnce(applicationContext, listId)
    }

    private companion object {
        const val NOTIFY_BUDGET_MS = 5_000L
    }
}
