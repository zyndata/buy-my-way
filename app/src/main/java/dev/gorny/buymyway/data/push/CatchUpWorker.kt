package dev.gorny.buymyway.data.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.gorny.buymyway.BuyMyWayApp
import java.util.concurrent.TimeUnit

/**
 * Reads what this phone has not seen yet, in the background (PLAN.md *Battery policy*, Phase 9
 * task 4; STATE.md decision 96). Two shapes, one worker:
 *
 * - **one-shot**, for one list, started by a push that has just arrived, or with no list at all
 *   when FCM has handed over a new token;
 * - **periodic, every 3 hours**, with `NetworkType.CONNECTED` and `requiresBatteryNotLow`, as
 *   insurance for a push that was dropped. It reads and posts nothing.
 *
 * It is **not** expedited work. Below Android 12 WorkManager runs an expedited worker as a
 * foreground service with a notification, and this project does not start foreground services
 * (CLAUDE.md, PLAN.md *Battery policy*). A high-priority data message puts the app in the
 * system's temporary allowlist, which is long enough for ordinary work to start, and the
 * notification the user actually waits for was posted by [PushService] before this ever ran.
 */
class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as BuyMyWayApp).container
        val listId = inputData.getString(KEY_LIST_ID)?.takeIf { it.isNotBlank() }
        return if (container.catchUpInBackground(listId)) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_LIST_ID = "listId"
        private const val ONCE = "catch-up"
        private const val PERIODIC = "catch-up-periodic"

        /** PLAN.md *Battery policy*: „one periodic `CatchUpWorker` every 3 hours". */
        const val PERIOD_HOURS = 3L

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** One list now, or the whole account when [listId] is null. */
        fun enqueueOnce(context: Context, listId: String?) {
            val request = OneTimeWorkRequestBuilder<CatchUpWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_LIST_ID to listId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Per list, so two pushes about two lists do not queue behind each other, and a
            // second push about the same list replaces a run that has not started yet.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$ONCE:${listId.orEmpty()}", ExistingWorkPolicy.REPLACE, request)
        }

        /** Enqueued while signed in; `KEEP` leaves a running period alone. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatchUpWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
