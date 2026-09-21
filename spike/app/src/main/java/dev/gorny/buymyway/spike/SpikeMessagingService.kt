package dev.gorny.buymyway.spike

import android.app.NotificationManager
import android.app.Notification
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the Apps Script's data message. `sentAt` is the wall clock of the phone that asked
 * for the push; when it is this same phone, `now - sentAt` is the end-to-end latency with no
 * clock skew in it.
 */
class SpikeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val sentAt = message.data["sentAt"]?.toLongOrNull()
        val latency = sentAt?.let { System.currentTimeMillis() - it }
        val scriptMs = message.data["scriptMs"]
        SpikeLog.i("PUSH received: end-to-end=${latency} ms (script ran ${scriptMs} ms) priority=${message.priority} data=${message.data}")
        latency?.let { Push.received(it) }
        getSystemService(NotificationManager::class.java).notify(
            (System.currentTimeMillis() % 100000).toInt(),
            Notification.Builder(this, SpikeApp.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Spike push")
                .setContentText("end-to-end $latency ms")
                .build(),
        )
    }

    override fun onNewToken(token: String) {
        SpikeLog.i("FCM token refreshed")
    }
}
