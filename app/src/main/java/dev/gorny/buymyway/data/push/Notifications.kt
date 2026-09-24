package dev.gorny.buymyway.data.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.gorny.buymyway.MainActivity
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.push.PushSignal
import dev.gorny.buymyway.data.prefs.NotificationPreferences

/**
 * What a phone says when a shared list changes while nobody is looking at it (PLAN.md Phase 9,
 * task 3; STATE.md decisions 94 and 95).
 *
 * Two channels, one notification per list, and its text is built from the tally this phone
 * keeps — so a second message reads „+5, ✓ 3" rather than starting again at „+2, ✓ 1". Tapping
 * it opens that list; dismissing it forgets the tally.
 *
 * Collapsed, it says how many. Pulled open, it names what was bought — but only once the
 * catch-up has read the list and called [addBought], because no item name travels through FCM
 * (decision 106).
 */
class Notifications(
    private val context: Context,
    private val prefs: NotificationPreferences,
) {
    /** Cheap and idempotent; called before anything is posted, and at start. */
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHANGES,
                context.getString(R.string.channel_changes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_changes_hint) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SHARED,
                context.getString(R.string.channel_shared),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_shared_hint) },
        )
    }

    /** Whether Android would let this app post at all (API 33+ asks the user first). */
    fun permitted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * One arrived message. Returns false when nothing was shown, which is the ordinary case for
     * a phone whose switches are off — the list is still caught up, it is only silent.
     */
    suspend fun onMessage(
        listId: String,
        listName: String?,
        kind: String,
        counts: PushSignal.Counts,
        actor: String?,
        onScreen: Boolean,
    ): Boolean {
        val switches = prefs.current()
        if (!switches.allows(kind) || !permitted()) return false
        // The user is looking at this list: the change is on their screen through the live
        // listener, so it is neither shown nor counted.
        if (onScreen) return false
        if (kind == PushSignal.KIND_SHARED) {
            val who = actor ?: context.getString(R.string.notify_somebody)
            return post(listId, listName, CHANNEL_SHARED, context.getString(R.string.notify_shared_body, who))
        }

        // The tally is kept whole even where a switch hides part of it, so turning a switch
        // back on does not invent numbers that were never counted.
        val tally = prefs.addToTally(listId, counts, actor)
        if (switches.filter(counts).isEmpty) return false
        val shown = switches.filter(tally.counts)
        if (shown.isEmpty) return false
        return post(listId, listName, CHANNEL_CHANGES, body(shown, tally.singleActor))
    }

    /** „Ania: +3, ✓ 2", or „+3, ✓ 2" when several people or nobody named are behind it. */
    private fun body(counts: PushSignal.Counts, actor: String?): String {
        val parts = PushSignal.parts(counts).joinToString(", ") { part ->
            when (part.kind) {
                PushSignal.Kind.ADDED -> context.getString(R.string.notify_added, part.count)
                PushSignal.Kind.CHECKED -> context.getString(R.string.notify_checked, part.count)
                PushSignal.Kind.CHANGED -> context.resources
                    .getQuantityString(R.plurals.notify_changed, part.count, part.count)
            }
        }
        return actor?.let { context.getString(R.string.notify_by, it, parts) } ?: parts
    }

    /**
     * The catch-up has read the list a push could only count (decision 106): the notification
     * that is already in the shade is posted again, now naming what was bought. Nothing is shown
     * where nothing was showing — no tally means no notification of this list is up.
     */
    suspend fun addBought(listId: String, listName: String?, names: List<String>, onScreen: Boolean): Boolean {
        if (names.isEmpty() || onScreen) return false
        val switches = prefs.current()
        // Names ride with „✓ 3", so the switch that hides the tick count hides them too.
        if (!switches.enabled || !switches.checked || !permitted()) return false
        val tally = prefs.addBoughtNames(listId, names) ?: return false
        val shown = switches.filter(tally.counts)
        if (shown.isEmpty) return false
        return post(listId, listName, CHANNEL_CHANGES, body(shown, tally.singleActor), tally.bought)
    }

    /**
     * [bought] names what the expanded notification lists under its counts; the collapsed one
     * reads the same [body] either way. [bought] also means this is the catch-up filling in a
     * notification the user has already been alerted to, so the phone stays quiet.
     */
    private fun post(
        listId: String,
        listName: String?,
        channel: String,
        body: String,
        bought: List<String> = emptyList(),
    ): Boolean {
        val title = listName ?: context.getString(R.string.notify_a_list)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(openList(listId))
            .setDeleteIntent(dismissed(listId))
            .setOnlyAlertOnce(bought.isNotEmpty())
        if (bought.isNotEmpty()) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(title)
                .setSummaryText(body)
            bought.take(BOUGHT_LINES).forEach {
                style.addLine(context.getString(R.string.notify_bought_line, it))
            }
            if (bought.size > BOUGHT_LINES) {
                style.addLine(context.getString(R.string.notify_bought_more, bought.size - BOUGHT_LINES))
            }
            builder.setStyle(style)
        }
        val notification = builder.build()
        return try {
            NotificationManagerCompat.from(context).notify(idOf(listId), notification)
            true
        } catch (_: SecurityException) {
            false // the permission went away between the check and the post
        }
    }

    /** The list is on screen, or its notification was dismissed: start counting again. */
    suspend fun clear(listId: String) {
        NotificationManagerCompat.from(context).cancel(idOf(listId))
        prefs.clearTally(listId)
    }

    /** Sign-out and „Usuń moje dane": nothing of anyone's lists stays in the shade. */
    suspend fun clearAll() {
        val manager = NotificationManagerCompat.from(context)
        for (listId in prefs.talliedLists()) {
            manager.cancel(idOf(listId))
            prefs.clearTally(listId)
        }
    }

    private fun openList(listId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(listUri(listId))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, idOf(listId), intent, FLAGS)
    }

    private fun dismissed(listId: String): PendingIntent {
        val intent = Intent(context, NotificationDismissed::class.java).setData(listUri(listId))
        return PendingIntent.getBroadcast(context, idOf(listId), intent, FLAGS)
    }

    companion object {
        const val CHANNEL_CHANGES = "changes"
        const val CHANNEL_SHARED = "shared"

        /** The deep link a notification carries (decision 95); the app opens it itself. */
        fun listUri(listId: String): Uri = "buymyway://list/$listId".toUri()

        /** The list id in [listUri], or null for any other link. */
        fun listIdOf(uri: String): String? = uri.removePrefix("buymyway://list/")
            .takeIf { it != uri && it.isNotEmpty() && !it.contains('/') }

        /** One notification per list, and the same one every time. */
        fun idOf(listId: String): Int = listId.hashCode()

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** How many names an expanded notification lists before „i jeszcze 4". */
        private const val BOUGHT_LINES = 6
    }
}
