package dev.gorny.buymyway.data.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.gorny.buymyway.BuyMyWayApp
import kotlinx.coroutines.launch

/**
 * The user swiped a list's notification away, so the next one starts counting from zero
 * (STATE.md decision 95). Not exported: only the app's own delete intent reaches it.
 */
class NotificationDismissed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val listId = intent.dataString?.let(Notifications::listIdOf) ?: return
        val container = (context.applicationContext as? BuyMyWayApp)?.container ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.notifications.clear(listId)
            } finally {
                pending.finish()
            }
        }
    }
}
