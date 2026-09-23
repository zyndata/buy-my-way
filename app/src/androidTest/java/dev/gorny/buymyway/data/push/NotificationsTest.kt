package dev.gorny.buymyway.data.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gorny.buymyway.core.push.PushSignal
import dev.gorny.buymyway.data.prefs.NotificationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a phone says when a push arrives (PLAN.md Phase 9, task 3; STATE.md decisions 94 and
 * 95), posted to the real notification manager and read back from it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var scope: CoroutineScope
    private lateinit var files: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: NotificationPreferences
    private lateinit var notifications: Notifications

    @Before
    fun open() {
        // An install for a test run grants nothing, and the sheet refuses to post without it
        // (the same lesson as Phase 7's microphone).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        files = File(context.cacheDir, "notify-test-${System.nanoTime()}").apply { mkdirs() }
        store = PreferenceDataStoreFactory.create(scope = scope) { File(files, "prefs.preferences_pb") }
        prefs = NotificationPreferences(store)
        notifications = Notifications(context, prefs)
        notifications.ensureChannels()
        manager.cancelAll()
    }

    @After
    fun close() {
        manager.cancelAll()
        scope.cancel()
        files.deleteRecursively()
    }

    private fun posted(listId: String = LIST): Notification? = manager.activeNotifications
        .firstOrNull { it.id == Notifications.idOf(listId) }
        ?.notification

    /**
     * The shade is another process: a notification just posted takes a moment to appear there,
     * so a test that reads it straight back races it.
     */
    private fun shown(listId: String = LIST): Notification? {
        val until = System.currentTimeMillis() + APPEARS_WITHIN_MS
        while (System.currentTimeMillis() < until) {
            posted(listId)?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    private fun title(listId: String = LIST) = shown(listId)?.extras?.getString(Notification.EXTRA_TITLE)

    /**
     * A notification updated in place lands in the shade a moment later too, so this waits for
     * the text it should end up with rather than reading whatever is there first.
     */
    private fun assertText(expected: String, listId: String = LIST) {
        val until = System.currentTimeMillis() + APPEARS_WITHIN_MS
        var last: String? = null
        while (System.currentTimeMillis() < until) {
            last = posted(listId)?.extras?.getString(Notification.EXTRA_TEXT)
            if (last == expected) return
            Thread.sleep(25)
        }
        assertEquals(expected, last)
    }

    /** The opposite: nothing should appear, so this waits and then insists there is nothing. */
    private fun nothingShown(listId: String = LIST): Notification? {
        Thread.sleep(APPEARS_WITHIN_MS / 4)
        return posted(listId)
    }

    private suspend fun arrive(
        counts: PushSignal.Counts,
        actor: String? = "Ania",
        kind: String = PushSignal.KIND_CHANGES,
        onScreen: Boolean = false,
        listName: String? = "Biedronka",
    ) = notifications.onMessage(LIST, listName, kind, counts, actor, onScreen)

    @Test
    fun nothingIsShownUntilTheUserTurnsNotificationsOn() = runBlocking {
        assertFalse(arrive(PushSignal.Counts(added = 3)))
        assertNull("the switch is off by default", nothingShown())
    }

    @Test
    fun aChangeReadsAsTheNameTheCountsAndTheList() = runBlocking {
        prefs.setEnabled(true)

        assertTrue(arrive(PushSignal.Counts(added = 3, checked = 2)))

        assertEquals("Biedronka", title())
        assertText("Ania: +3, ✓ 2")
        assertEquals(Notifications.CHANNEL_CHANGES, shown()?.channelId)
    }

    @Test
    fun aSecondMessageAddsToTheFirstRatherThanReplacingIt() = runBlocking {
        prefs.setEnabled(true)

        arrive(PushSignal.Counts(added = 2))
        arrive(PushSignal.Counts(added = 3, checked = 1))

        assertText("Ania: +5, ✓ 1")
        // One notification for the list, not three.
        assertEquals(1, manager.activeNotifications.count { it.id == Notifications.idOf(LIST) })
    }

    @Test
    fun twoPeopleChangingOneListLeaveTheNotificationWithNoName() = runBlocking {
        prefs.setEnabled(true)

        arrive(PushSignal.Counts(added = 1), actor = "Ania")
        arrive(PushSignal.Counts(checked = 1), actor = "Bartek")

        assertText("+1, ✓ 1")
    }

    @Test
    fun theListOnScreenIsNeitherShownNorCounted() = runBlocking {
        prefs.setEnabled(true)

        assertFalse(arrive(PushSignal.Counts(added = 4), onScreen = true))
        assertNull(nothingShown())

        // And the next push, once the user has left the list, starts from its own numbers.
        assertTrue(arrive(PushSignal.Counts(added = 1)))
        assertText("Ania: +1")
    }

    @Test
    fun aSwitchHidesItsOwnNumbersAndLeavesTheOthers() = runBlocking {
        prefs.setEnabled(true)
        prefs.setChecked(false)

        assertTrue(arrive(PushSignal.Counts(added = 2, checked = 5)))
        assertText("Ania: +2")

        // With nothing left to say, nothing is posted at all.
        notifications.clear(LIST)
        prefs.setAdded(false)
        assertFalse(arrive(PushSignal.Counts(added = 1, checked = 1)))
        assertNull(nothingShown())
    }

    @Test
    fun aSharedListHasItsOwnChannelAndSentence() = runBlocking {
        prefs.setEnabled(true)

        assertTrue(arrive(PushSignal.Counts(), kind = PushSignal.KIND_SHARED))

        assertEquals("Biedronka", title())
        assertText("Ania udostępnia Ci tę listę")
        assertEquals(Notifications.CHANNEL_SHARED, shown()?.channelId)

        prefs.setShared(false)
        notifications.clear(LIST)
        assertFalse(arrive(PushSignal.Counts(), kind = PushSignal.KIND_SHARED))
    }

    @Test
    fun aListThisPhoneDoesNotKnowYetStillGetsASentence() = runBlocking {
        prefs.setEnabled(true)

        assertTrue(arrive(PushSignal.Counts(added = 1), listName = null))

        assertEquals("Lista zakupów", title())
    }

    @Test
    fun openingTheListTakesTheNotificationAndItsTally() = runBlocking {
        prefs.setEnabled(true)
        arrive(PushSignal.Counts(added = 3))
        assertNotNull(shown())

        notifications.clear(LIST)

        assertNull(nothingShown())
        assertEquals(emptyList<String>(), prefs.talliedLists())
        arrive(PushSignal.Counts(added = 1))
        assertText("Ania: +1")
    }

    @Test
    fun bothChannelsExistAndAreNamedInPolish() {
        val changes = manager.getNotificationChannel(Notifications.CHANNEL_CHANGES)
        val shared = manager.getNotificationChannel(Notifications.CHANNEL_SHARED)

        assertEquals("Zmiany na wspólnej liście", changes.name)
        assertEquals("Nowe udostępnione listy", shared.name)
    }

    @Test
    fun theDeepLinkNamesOneListAndNothingElse() {
        assertEquals(LIST, Notifications.listIdOf(Notifications.listUri(LIST).toString()))
        assertNull(Notifications.listIdOf("https://eatmyway.gorny.dev/bmw/i/abc"))
        assertNull(Notifications.listIdOf("buymyway://i/abc"))
        assertNull(Notifications.listIdOf("buymyway://list/"))
        assertNull(Notifications.listIdOf("buymyway://list/a/b"))
        assertEquals(Notifications.idOf(LIST), Notifications.idOf(LIST))
    }

    @Test
    fun postNotificationsIsTheOnlyPermissionThisPhaseAdds() {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val declared = info.requestedPermissions.orEmpty().toSet()

        assertTrue(declared.contains("android.permission.POST_NOTIFICATIONS"))
        // The only two the app ever asks a person for, before and after this phase. (WorkManager
        // and Firebase Messaging bring WAKE_LOCK, RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE
        // into the merged manifest on their own; the app declares and uses none of them.)
        assertEquals(
            setOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"),
            declared.intersect(RUNTIME_PERMISSIONS),
        )
        // What a background feature could have asked for and did not (PLAN.md *Battery policy*).
        assertFalse(declared.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(declared.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(declared.contains("android.permission.USE_EXACT_ALARM"))
    }

    private companion object {
        const val LIST = "list-notify-1"

        /** How long the shade may take to show a notification just posted. */
        const val APPEARS_WITHIN_MS = 4_000L

        /** The permissions Android asks a person about, that an app of this kind might want. */
        val RUNTIME_PERMISSIONS = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_CONTACTS",
            "android.permission.GET_ACCOUNTS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )
    }
}
