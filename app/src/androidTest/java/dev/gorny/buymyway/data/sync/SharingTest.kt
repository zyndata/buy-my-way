package dev.gorny.buymyway.data.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.share.InviteLinks
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.prefs.CategoryOrderPreferences
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.data.prefs.ListSortPreferences
import dev.gorny.buymyway.data.prefs.SyncMarks
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.asNode
import dev.gorny.buymyway.data.share.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * Sharing between accounts (PLAN.md Phase 5), with a [FakeServer] that mirrors the rules for
 * members, roles and invites behind `RemoteLists` and `LiveSource`. Each phone here is a
 * different Google account. The real rules are tested against the emulator in `firebase/test`.
 */
@RunWith(AndroidJUnit4::class)
class SharingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()
    private val phones = mutableListOf<Phone>()

    inner class Phone(val uid: String, val name: String, val email: String, start: Long = 1_000_000) {
        var now = start
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        private val store = PreferenceDataStoreFactory.create { File(context.cacheDir, "share-${UUID.randomUUID()}.preferences_pb") }
        val listSort = ListSortPreferences(store) { now }
        val remote = server.client(uid)
        val lost = mutableListOf<String>()

        /** What `AccountRepository.checkSession` would say: false once the access gate refuses. */
        var sessionOk = true
        val repo = ListRepository(
            db = db,
            categoryOrder = CategoryOrderPreferences(store) { now },
            categorize = { "inne" },
            clock = { now },
            actor = { uid },
        )
        val engine = SyncEngine(
            db = db,
            repo = repo,
            remote = remote,
            prefs = PrefsSync(
                db,
                remote,
                CategoryOrderPreferences(store) { now }.stored,
                ListOrderPreferences(store) { now }.stored,
                SyncMarks(store),
                listSort,
                timeoutMs = TIMEOUT_MS,
            ),
            sessionValid = { sessionOk },
            clock = { now },
            timeoutMs = TIMEOUT_MS,
            onListLost = { lost += it },
        )
        private val connection = Connection {}
        private val random = Random(uid.hashCode())
        val sharing = Sharing(
            remote = remote,
            engine = engine,
            repo = repo,
            connection = connection,
            me = { Sharing.Me(uid, name) },
            random = { random.nextBytes(it) },
            clock = { now },
            timeoutMs = TIMEOUT_MS,
        )
        val live = LiveLists(repo, engine, remote, connection) { uid }

        suspend fun signIn() = engine.writeProfile(uid, name, email, null)

        suspend fun sync() {
            engine.flush(uid)
            engine.catchUp(uid)
        }

        suspend fun state(listId: String) = repo.loadState(listId)

        suspend fun visible(listId: String): Map<String, Boolean> {
            val state = state(listId)
            return state.items.values.filter { Merge.isVisible(it, state.list) }.associate { it.name to it.checked }
        }

        suspend fun itemId(listId: String, name: String) = state(listId).items.values.first { it.name == name && it.deletedAt == null }.id

        suspend fun members(listId: String) = repo.observeMembers(listId).first().associate { it.uid to (it.name to it.role) }

        init {
            phones += this
        }
    }

    @After
    fun close() = phones.forEach { it.db.close() }

    /** Alice's list with three items, one of them bought, in RTDB. */
    private suspend fun Phone.aList(): String {
        signIn()
        val listId = repo.createList("Sobota")
        listOf("mleko", "chleb", "masło").forEach { now++; repo.addItem(listId, it) }
        now++
        repo.setChecked(itemId(listId, "masło"), true)
        sync()
        return listId
    }

    private suspend fun Phone.join(owner: Phone, listId: String, role: Role = Role.EDITOR): String {
        signIn()
        val token = InviteLinks.tokenOf(owner.sharing.inviteLink(listId, role))!!
        return sharing.accept(token)
    }

    @Test
    fun someoneInvitedByLinkSeesTheWholeListTheMomentTheyAccept() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()

        bob.signIn()
        val link = alice.sharing.inviteLink(listId, Role.EDITOR)
        val token = InviteLinks.tokenOf(link)!!
        val invite = bob.sharing.readInvite(token)
        assertEquals("Sobota", invite.listName)
        assertEquals("Alice", invite.byName)
        assertEquals(Role.EDITOR, invite.role)

        // Only Bob's phone acts: Alice's does nothing between the link and the list appearing.
        assertEquals(listId, bob.sharing.accept(token))
        assertEquals(alice.visible(listId), bob.visible(listId))
        assertTrue(bob.state(listId).list!!.shared)
        assertEquals(mapOf(ALICE to ("Alice" to Role.OWNER), BOB to ("Bob" to Role.EDITOR)), bob.members(listId))
        assertEquals("editor", server.node("userLists/$BOB").asNode()[listId])

        // A second accept changes nothing; the invite is used once per person.
        assertEquals(listId, bob.sharing.accept(token))

        // Bob's tick reaches Alice, signed with his uid.
        bob.now = 2_000_000
        bob.repo.setChecked(bob.itemId(listId, "mleko"), true)
        bob.sync()
        alice.sync()
        assertEquals(true, alice.visible(listId)["mleko"])
        assertEquals(BOB, alice.state(listId).items.getValue(alice.itemId(listId, "mleko")).checkedBy)
        assertEquals("Bob", alice.members(listId)[BOB]?.first)
    }

    @Test
    fun anExpiredInviteOrAMadeUpOneIsRefused() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()
        bob.signIn()
        val token = InviteLinks.tokenOf(alice.sharing.inviteLink(listId, Role.EDITOR))!!

        bob.now = alice.now + 8 * Merge.DAY_MS
        server.wallClock = bob.now
        try {
            bob.sharing.accept(token)
            fail("an expired invite must be refused")
        } catch (_: Sharing.SharingFailure.Expired) {
            // expected
        }
        // And the server refuses it too, whatever the phone thinks.
        val refused = runCatching { bob.remote.update(RemoteWrites.accept(token, listId, Role.EDITOR, BOB)).await() }
        assertTrue(refused.exceptionOrNull() is RemoteDenied)
        try {
            bob.sharing.readInvite("A".repeat(22))
            fail("a made-up token must not be found")
        } catch (_: Sharing.SharingFailure.NoSuchInvite) {
            // expected
        }
        assertNull(bob.repo.loadState(listId).list)
    }

    @Test
    fun byEMailAViewerSeesTheListAndCannotTouchIt() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val carol = Phone(CAROL, "Carol", "carol@example.com")
        val listId = alice.aList()
        carol.signIn()

        assertEquals(Sharing.EmailInvite.NotFound, alice.sharing.inviteByEmail(listId, "nobody@example.com", Role.VIEWER))
        assertEquals(Sharing.EmailInvite.InvalidAddress, alice.sharing.inviteByEmail(listId, "carol", Role.VIEWER))
        assertEquals(Sharing.EmailInvite.Added(CAROL), alice.sharing.inviteByEmail(listId, " Carol@Example.com ", Role.VIEWER))
        assertEquals(Sharing.EmailInvite.AlreadyMember, alice.sharing.inviteByEmail(listId, "carol@example.com", Role.VIEWER))

        carol.sync()
        assertEquals(alice.visible(listId), carol.visible(listId))
        assertEquals(Role.VIEWER, carol.repo.roleOf(listId))
        try {
            carol.repo.setChecked(carol.itemId(listId, "mleko"), true)
            fail("a viewer's tick must be refused on the phone")
        } catch (_: ListRepository.ReadOnlyList) {
            // expected
        }
        assertEquals(0, carol.db.outbox().count())
        // And by the server, should an op get out anyway.
        val write = mapOf("lists/$listId/items/${carol.itemId(listId, "mleko")}/checked" to true)
        assertTrue(runCatching { carol.remote.update(write).await() }.exceptionOrNull() is RemoteDenied)
    }

    @Test
    fun aRemovedMemberLosesTheListWithASentenceAndOneWhoLeavesIsGoneFromIt() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val carol = Phone(CAROL, "Carol", "carol@example.com")
        val listId = alice.aList()
        bob.join(alice, listId)
        carol.join(alice, listId, Role.VIEWER)
        alice.sync()
        assertEquals(setOf(ALICE, BOB, CAROL), alice.members(listId).keys)

        val bobAsMember = alice.repo.observeMembers(listId).first().first { it.uid == BOB }
        alice.sharing.remove(listId, bobAsMember)
        bob.sync()
        assertNull(bob.repo.loadState(listId).list)
        assertEquals(listOf("Sobota"), bob.lost)

        carol.sharing.leave(listId)
        assertNull(carol.repo.loadState(listId).list)
        assertTrue(carol.lost.isEmpty()) // she chose it; no sentence
        alice.sync()
        assertEquals(setOf(ALICE), alice.members(listId).keys)

        // „Uczyń prywatną": the members node goes, and the list is private again.
        alice.sharing.makePrivate(listId)
        assertFalse(alice.state(listId).list!!.shared)
        assertNull(server.node("lists/$listId/members"))
    }

    @Test
    fun whileTheAccessGateRefusesTheAccountNoListAndNoChangeLeavesThePhone() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()
        bob.join(alice, listId)
        alice.sync()
        bob.now = 2_000_000
        bob.repo.setChecked(bob.itemId(listId, "mleko"), true)

        // Every read and write of Bob's is refused from now on, and the session check says why
        // (decision 60, revised). Taking him off the list stands in for the refusal.
        val bobAsMember = alice.repo.observeMembers(listId).first().first { it.uid == BOB }
        alice.sharing.remove(listId, bobAsMember)
        bob.sessionOk = false
        assertTrue(runCatching { bob.engine.flush(BOB) }.exceptionOrNull() is SyncEngine.SessionLost)
        runCatching { bob.engine.catchUp(BOB) }
        assertEquals(1, bob.db.outbox().count())
        assertEquals(true, bob.visible(listId)["mleko"])
        assertTrue(bob.lost.isEmpty())

        // Let back in, the refusal is about the list again, and it goes as it always did.
        bob.sessionOk = true
        bob.sync()
        assertNull(bob.repo.loadState(listId).list)
        assertEquals(listOf("Sobota"), bob.lost)
    }

    @Test
    fun tenMinutesOfEditsWithOnePhoneInAirplaneModeConvergeWithoutDuplicatesOrLostTicks() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()
        bob.join(alice, listId)

        bob.remote.online = false
        val added = mutableSetOf("mleko", "chleb", "masło")
        for (minute in 1..10) {
            alice.now = 2_000_000L + minute * 60_000
            bob.now = alice.now + 7_000 // clocks a few seconds apart, as phones are
            alice.repo.addItem(listId, "a$minute").also { added += "a$minute" }
            bob.repo.addItem(listId, "b$minute").also { added += "b$minute" }
            if (minute % 3 == 0) {
                alice.now++
                alice.repo.setChecked(alice.itemId(listId, "a${minute - 1}"), true)
                bob.now++
                bob.repo.setChecked(bob.itemId(listId, "b${minute - 2}"), true)
            }
            alice.sync()
        }
        // The same item ticked on both sides, the offline one first.
        bob.now++
        bob.repo.setChecked(bob.itemId(listId, "chleb"), true)
        alice.now++
        alice.repo.setChecked(alice.itemId(listId, "chleb"), true)
        alice.sync()
        try {
            bob.engine.flush(BOB)
            fail("an offline flush must not claim success")
        } catch (_: RemoteFailure) {
            // expected
        }

        bob.remote.online = true
        bob.sync()
        alice.sync()
        bob.sync()

        val expectedTicks = setOf("masło", "chleb", "a2", "a5", "a8", "b1", "b4", "b7")
        for (phone in listOf(alice, bob)) {
            val visible = phone.visible(listId)
            assertEquals(added, visible.keys)
            assertEquals(added.size, phone.state(listId).items.values.count { Merge.isVisible(it, phone.state(listId).list) })
            assertEquals(expectedTicks, visible.filterValues { it }.keys)
            assertEquals(0, phone.db.outbox().count())
        }
        assertEquals(alice.visible(listId), bob.visible(listId))
    }

    @Test
    fun aTickArrivesLiveOnAWatchingPhoneAndARemovedWatcherLosesTheList() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()
        bob.join(alice, listId)
        alice.sync() // Alice's phone learns of Bob, to remove him later

        withContext(Dispatchers.Default) {
            val present = MutableStateFlow<Set<String>>(emptySet())
            val watching = launch { bob.live.watch(listId).collect { present.value = it } }
            withTimeout(WAIT_MS) { present.first { BOB in it } }
            assertEquals(setOf(BOB), server.node("lists/$listId/presence").asNode().keys)

            // Alice ticks; Bob's phone learns of it with no catch-up, through its listener.
            alice.now = 3_000_000
            alice.repo.setChecked(alice.itemId(listId, "chleb"), true)
            alice.engine.flush(ALICE)
            withTimeout(WAIT_MS) { bob.repo.observeItems(listId).first { items -> items.any { it.name == "chleb" && it.checked } } }
            assertEquals(ALICE, bob.state(listId).items.values.first { it.name == "chleb" }.checkedBy)

            // Removed while watching: the listener is refused, and the list leaves the phone.
            val bobAsMember = alice.repo.observeMembers(listId).first().first { it.uid == BOB }
            alice.sharing.remove(listId, bobAsMember)
            withTimeout(WAIT_MS) { bob.repo.observeSynced(listId).first { !it } }
            watching.cancel()
        }
        assertNull(bob.repo.loadState(listId).list)
        assertEquals(listOf("Sobota"), bob.lost)
        assertNull(server.node("lists/$listId/presence"))
    }

    @Test
    fun theManualOrderReachesTheOtherMemberButTheirViewStaysTheirs() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()
        bob.join(alice, listId)

        alice.now = 2_000_000
        alice.listSort.set(listId, SortView.MANUAL)
        alice.repo.placeAllManually(listId)
        alice.now++
        alice.repo.moveItemManually(alice.itemId(listId, "chleb"), 0.5) // before „mleko"
        alice.sync()
        bob.sync()

        suspend fun keys(phone: Phone) = phone.state(listId).items.values.associate { it.name to it.manualKey }
        assertEquals(keys(alice), keys(bob))
        assertEquals(0.5, keys(bob)["chleb"])
        assertEquals(SortView.MANUAL, alice.listSort.view(listId).first())
        assertEquals(SortView.DEPARTMENTS, bob.listSort.view(listId).first())
        assertEquals("manual", server.node("users/$ALICE/prefs/listSort/$listId").asNode()["value"])
        assertNull(server.node("users/$BOB/prefs/listSort"))
    }

    @Test
    fun theOwnerRemovesTombstonesOlderThanThirtyDaysFromRtdb() = runBlocking {
        val alice = Phone(ALICE, "Alice", "alice@example.com")
        val bob = Phone(BOB, "Bob", "bob@example.com")
        val listId = alice.aList()
        bob.join(alice, listId)
        val bread = alice.itemId(listId, "chleb")
        alice.now = 2_000_000
        alice.repo.deleteItem(bread)
        alice.sync()
        assertTrue(server.node("lists/$listId/items/$bread").asNode().containsKey("deletedAt"))

        // Bob's phone is not the owner's: it forgets the tombstone only in its own Room.
        bob.now = alice.now + Merge.TOMBSTONE_KEEP_MS + 1
        bob.sync()
        bob.repo.sweep(listId)
        assertTrue(server.node("lists/$listId/items/$bread").asNode().containsKey("deletedAt"))

        // The owner's sweep leaves it to sync, which removes it from RTDB and then from Room.
        alice.now = bob.now
        alice.repo.sweep(listId)
        assertTrue(alice.state(listId).items.containsKey(bread))
        alice.sync()
        assertNull(server.node("lists/$listId/items/$bread"))
        assertFalse(alice.state(listId).items.containsKey(bread))
    }

    private companion object {
        /** Made-up uids and addresses: the repository is public (CLAUDE.md). */
        const val ALICE = "test-alice"
        const val BOB = "test-bob"
        const val CAROL = "test-carol"
        const val TIMEOUT_MS = 500L
        const val WAIT_MS = 5_000L
    }
}
