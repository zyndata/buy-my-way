package dev.gorny.buymyway.data.photo

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
 * Sends the photos set or removed on this phone to `/photos` (PLAN.md Phase 6, task 2; STATE.md
 * decision 71), then the `photoAt` changes that follow them. One-shot and unique, only with a
 * network, retried with backoff only while something could not be sent. It runs because a
 * photo was set or removed, or because sync just uploaded a list that has one waiting; never on
 * a timer.
 */
class PhotoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as BuyMyWayApp).container
        return if (container.sendPhotosInBackground()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "photos"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PhotoWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // A photo set while one is being sent is sent by the next run, not dropped.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
