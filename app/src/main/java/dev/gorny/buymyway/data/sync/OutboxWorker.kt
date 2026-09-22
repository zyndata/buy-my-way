package dev.gorny.buymyway.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.gorny.buymyway.BuyMyWayApp
import java.util.concurrent.TimeUnit

/**
 * Sends the changes that were still unsent when the app left the foreground (PLAN.md
 * *Battery policy*, STATE.md decision 58). One-shot and unique; it runs only with a network,
 * goes online for as long as it sends, and asks to be retried (with exponential backoff) only
 * while something is left. This worker is the only background work Phase 4 adds.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as BuyMyWayApp).container
        return if (container.sendOutboxInBackground()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "outbox"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
