package dev.gorny.buymyway.data.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.push.PushSignal
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.prefs.NotificationPreferences
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.sync.Connection
import dev.gorny.buymyway.data.sync.FakeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * The sending half of Phase 9 against the fake server: what a device registers so it can be
 * pushed to (PLAN.md task 1, STATE.md decision 98), and when the app asks for a push at all
 * (task 2, decision 93). What a phone does with a message that arrives is [NotificationsTest].
 */
@RunWith(AndroidJUnit4::class)
class PushTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var files: File
    private lateinit var store: DataStore<Preferences>
    private val server = FakeServer()
    private val alice = server.client(ALICE)

    @Volatile
    private var online = 0

    @Before
    fun open() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        files = File(context.cacheDir, "push-test-${System.nanoTime()}").apply { mkdirs() }
        store = PreferenceDataStoreFactory.create(scope = scope) { File(files, "prefs.preferences_pb") }
    }

    @After
    fun close() {
        scope.cancel()
        files.deleteRecursively()
    }

    private fun connection() = Connection { on -> if (on) online++ else online-- }

    private fun tokens(token: String?) = PushTokens(remote = alice, dataStore = store, currentToken = { token })

    // --- The registration (task 1, decision 98) ---------------------------------------------

    @Test
    fun theDeviceRegistersItsTokenOnceAndKeepsItThere() = runBlocking {
        val push = tokens(TOKEN)

        assertTrue(push.ensureRegistered(ALICE))
        val node = server.node(RemoteWrites.fcmToken(ALICE, TOKEN))
        assertNotNull("the token should be in RTDB", node)
        assertEquals(setOf("at"), (node as Map<*, *>).keys)

        // A second call writes nothing: the phone knows what it already registered.
        val before = server.node(RemoteWrites.fcmTokens(ALICE))
        assertTrue(push.ensureRegistered(ALICE))
        assertEquals(before, server.node(RemoteWrites.fcmTokens(ALICE)))
    }

    @Test
    fun aRotatedTokenReplacesTheOldOneRatherThanPilingUp() = runBlocking {
        tokens(TOKEN).ensureRegistered(ALICE)
        tokens(OTHER_TOKEN).ensureRegistered(ALICE)

        assertNull(server.node(RemoteWrites.fcmToken(ALICE, TOKEN)))
        assertNotNull(server.node(RemoteWrites.fcmToken(ALICE, OTHER_TOKEN)))
    }

    @Test
    fun signOutTakesTheRegistrationWithIt() = runBlocking {
        val push = tokens(TOKEN)
        push.ensureRegistered(ALICE)

        push.unregister(ALICE)

        assertNull(server.node(RemoteWrites.fcmToken(ALICE, TOKEN)))
        // And the phone has forgotten it, so the next sign-in registers again.
        assertTrue(push.ensureRegistered(ALICE))
        assertNotNull(server.node(RemoteWrites.fcmToken(ALICE, TOKEN)))
    }

    @Test
    fun aPhoneWithoutPlayServicesSimplyDoesNotRegister() = runBlocking {
        assertFalse(tokens(null).ensureRegistered(ALICE))
        assertNull(server.node(RemoteWrites.fcmTokens(ALICE)))
    }

    @Test
    fun nobodyReadsAnotherUsersRegistration() = runBlocking {
        tokens(TOKEN).ensureRegistered(ALICE)
        var denied = false
        try {
            server.client(BOB).read(RemoteWrites.fcmTokens(ALICE))
        } catch (_: RemoteDenied) {
            denied = true
        }
        assertTrue("another user's tokens must not be readable", denied)
    }

    // --- When a push is asked for at all (task 2, decision 93) -------------------------------

    /** The script, as far as the app is concerned. */
    private class Recorder : PushEndpoint {
        private val lock = Any()

        @Volatile
        var calls = listOf<Triple<String, String, PushSignal.Counts>>()
            private set

        override suspend fun send(idToken: String, listId: String, kind: String, counts: PushSignal.Counts): Boolean {
            synchronized(lock) { calls = calls + Triple(listId, kind, counts) }
            return true
        }
    }

    /** Waits for [n] requests, or gives up; either way the count is what is asserted. */
    private suspend fun settle(script: Recorder, n: Int) {
        withTimeoutOrNull(WAIT_MS) {
            while (script.calls.size < n) delay(10)
        }
        // A moment more, so „exactly one" can fail when a second one is on its way.
        delay(DEBOUNCE * 3)
    }

    private fun sender(endpoint: PushEndpoint?, members: List<Member>, debounceMs: Long = DEBOUNCE) = PushSender(
        endpoint = endpoint,
        remote = alice,
        members = { members },
        me = { PushSender.Me(ALICE, "Ania") },
        idToken = { "an id token" },
        connection = connection(),
        scope = scope,
        debounceMs = debounceMs,
    )

    private fun member(uid: String) = Member(LIST, uid, Role.EDITOR, 0, null, null, null)

    /** Alice's shared list on the server, so presence can be written for a real member. */
    private suspend fun seedSharedList() {
        alice.update(
            mapOf(
                RemoteWrites.meta(LIST) to mapOf(
                    "name" to "Zakupy",
                    "ownerUid" to ALICE,
                    "categoryOrder" to listOf("inne"),
                    "createdAt" to 1L,
                    "updatedAt" to 1L,
                    "updatedBy" to ALICE,
                ),
            ),
        ).await()
        alice.update(RemoteWrites.share(LIST, ALICE)).await()
        alice.update(RemoteWrites.setMember(LIST, BOB, Role.EDITOR, isNew = true)).await()
        alice.update(RemoteWrites.setMember(LIST, CAROL, Role.EDITOR, isNew = true)).await()
    }

    @Test
    fun aBurstOfChangesIsOnePushWithTheirSum() = runBlocking {
        val script = Recorder()
        val push = sender(script, listOf(member(ALICE), member(BOB)))

        push.changed(mapOf(LIST to PushSignal.Counts(added = 2)))
        push.changed(mapOf(LIST to PushSignal.Counts(added = 1, checked = 3)))
        assertEquals("nothing goes out before the debounce", 0, script.calls.size)

        settle(script, 1)
        assertEquals(1, script.calls.size)
        assertEquals(
            Triple(LIST, PushSignal.KIND_CHANGES, PushSignal.Counts(added = 3, checked = 3)),
            script.calls.single(),
        )
    }

    @Test
    fun aPrivateListAndAListOfOnesOwnWakeNobody() = runBlocking {
        val script = Recorder()

        sender(script, emptyList()).changed(mapOf(LIST to PushSignal.Counts(added = 1)))
        sender(script, listOf(member(ALICE))).changed(mapOf(LIST to PushSignal.Counts(added = 1)))
        settle(script, 1)

        assertEquals("a list with nobody else on it is never pushed about", 0, script.calls.size)
    }

    @Test
    fun aMemberLookingAtTheListIsNotWoken() = runBlocking {
        seedSharedList()
        val script = Recorder()
        // Bob has the list on screen, so his own listener has the change already.
        server.client(BOB).update(mapOf("${RemoteWrites.presence(LIST)}/$BOB" to 1L)).await()

        sender(script, listOf(member(ALICE), member(BOB))).changed(mapOf(LIST to PushSignal.Counts(checked = 1)))
        settle(script, 1)
        assertEquals(0, script.calls.size)

        // Carol is on the list too and is not looking, so the push goes after all.
        sender(script, listOf(member(ALICE), member(BOB), member(CAROL)))
            .changed(mapOf(LIST to PushSignal.Counts(checked = 1)))
        settle(script, 1)
        assertEquals(1, script.calls.size)
    }

    @Test
    fun sharingAListAsksForItsOwnKindOfMessage() = runBlocking {
        val script = Recorder()

        sender(script, listOf(member(ALICE), member(BOB))).shared(LIST)

        assertEquals(1, script.calls.size)
        assertEquals(PushSignal.KIND_SHARED, script.calls.single().second)
        assertEquals(PushSignal.Counts(), script.calls.single().third)
    }

    @Test
    fun aBackgroundFlushSendsWhatIsWaitingWithoutTheTimer() = runBlocking {
        val script = Recorder()
        val push = sender(script, listOf(member(ALICE), member(BOB)), debounceMs = 60_000)

        push.changed(mapOf(LIST to PushSignal.Counts(added = 1)))
        push.flushNow()

        assertEquals(1, script.calls.size)
    }

    @Test
    fun withoutAScriptUrlTheAppNeverAsks() = runBlocking {
        val push = sender(null, listOf(member(ALICE), member(BOB)))

        assertFalse(push.configured)
        push.changed(mapOf(LIST to PushSignal.Counts(added = 1)))
        push.shared(LIST)
        push.flushNow()
        delay(DEBOUNCE * 4)

        assertEquals("no connection is ever opened for a push that is not sent", 0, online)
    }

    // --- The switches and the tally this phone keeps (decisions 94 and 95) -------------------

    @Test
    fun theTallyAddsUpUntilTheListIsOpened() = runBlocking {
        val prefs = NotificationPreferences(store)

        prefs.addToTally(LIST, PushSignal.Counts(added = 2), "Ania")
        val tally = prefs.addToTally(LIST, PushSignal.Counts(added = 1, checked = 3), "Ania")

        assertEquals(PushSignal.Counts(added = 3, checked = 3), tally.counts)
        assertEquals("Ania", tally.singleActor)
        assertEquals(listOf(LIST), prefs.talliedLists())

        prefs.clearTally(LIST)
        assertEquals(emptyList<String>(), prefs.talliedLists())
        assertEquals(PushSignal.Counts(added = 1), prefs.addToTally(LIST, PushSignal.Counts(added = 1), null).counts)
    }

    @Test
    fun theSwitchesStartSilentAndAreTurnedOnOneAtATime() = runBlocking {
        val prefs = NotificationPreferences(store)

        assertFalse("nothing is shown until the user asks", prefs.current().enabled)
        prefs.setEnabled(true)
        assertTrue(prefs.current().enabled)
        assertTrue(prefs.current().added)

        prefs.setAdded(false)
        assertFalse(prefs.current().added)
        assertTrue(prefs.current().checked)
        prefs.setEnabled(false)
        assertFalse(prefs.current().allows(PushSignal.KIND_CHANGES))
    }

    private companion object {
        const val ALICE = "alice-uid"
        const val BOB = "bob-uid"
        const val CAROL = "carol-uid"
        const val LIST = "list-1"
        const val TOKEN = "token-a"
        const val OTHER_TOKEN = "token-b"

        /** Short, so the tests wait milliseconds where the app waits five seconds. */
        const val DEBOUNCE = 30L
        const val WAIT_MS = 5_000L
    }
}
