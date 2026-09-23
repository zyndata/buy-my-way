package dev.gorny.buymyway.data.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The background work Phase 9 adds, and the constraints PLAN.md's *Battery policy* puts on it
 * (STATE.md decision 96). The worker's own work is covered by [PushTest] and the sync tests;
 * what is checked here is what WorkManager was actually asked for.
 */
@RunWith(AndroidJUnit4::class)
class CatchUpWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val work = WorkManager.getInstance(context)

    @After
    fun cancel() {
        CatchUpWorker.cancel(context)
    }

    private suspend fun infos(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWorkFlow(name).first()

    @Test
    fun theOnlyPeriodicWorkIsAThreeHourlyCatchUp() = runBlocking {
        CatchUpWorker.schedulePeriodic(context)

        val info = infos("catch-up-periodic").single()
        assertEquals(
            "PLAN.md: one periodic CatchUpWorker every 3 hours",
            TimeUnit.HOURS.toMillis(CatchUpWorker.PERIOD_HOURS),
            info.periodicityInfo?.repeatIntervalMillis,
        )
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue("it waits for a battery that is not low", info.constraints.requiresBatteryNotLow())
        // Nothing this app schedules may hold the device awake or run while it is idle.
        assertTrue(!info.constraints.requiresDeviceIdle())
        assertTrue(!info.constraints.requiresCharging())
    }

    @Test
    fun aPushStartsOneRunPerListAndWaitsForANetwork(): Unit = runBlocking {
        CatchUpWorker.enqueueOnce(context, "list-a")
        CatchUpWorker.enqueueOnce(context, "list-b")

        val a = infos("catch-up:list-a").single()
        val b = infos("catch-up:list-b").single()
        assertEquals(NetworkType.CONNECTED, a.constraints.requiredNetworkType)
        // Two lists are two pieces of work, so one does not queue behind the other.
        assertTrue(a.id != b.id)

        work.cancelUniqueWork("catch-up:list-a")
        work.cancelUniqueWork("catch-up:list-b")
    }

    @Test
    fun schedulingTwiceLeavesTheRunningPeriodAlone() = runBlocking {
        CatchUpWorker.schedulePeriodic(context)
        val first = infos("catch-up-periodic").single().id

        CatchUpWorker.schedulePeriodic(context)

        assertEquals("KEEP: the 3-hour clock is not restarted at every sign-in", first, infos("catch-up-periodic").single().id)
    }

    @Test
    fun signOutLeavesNoPeriodicWorkBehind() = runBlocking {
        CatchUpWorker.schedulePeriodic(context)

        CatchUpWorker.cancel(context)

        assertTrue(infos("catch-up-periodic").all { it.state == WorkInfo.State.CANCELLED })
    }
}
