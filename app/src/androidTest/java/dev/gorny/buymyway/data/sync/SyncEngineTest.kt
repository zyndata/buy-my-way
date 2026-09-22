package dev.gorny.buymyway.data.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.prefs.CategoryOrderPreferences
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.data.prefs.SyncMarks
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.asNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Room ↔ RTDB through [SyncEngine], with a [FakeServer] behind `RemoteLists` (PLAN.md Phase 4,
 * task 7): two phones signed in as one account, one of them offline, and a delayed writer
 * whose acknowledgement never arrives. The fakes are the evidence here; the real rules are
 * tested against the emulator in `firebase/test`.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()
    private val phones = mutableListOf<Phone>()

    /** One phone: its own Room, clock, preferences and view of the server. */
    inner class Phone(start: Long, var signedIn: String? = UID) {
        var now = start
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        private val store = PreferenceDataStoreFactory.create { File(context.cacheDir, "sync-${UUID.randomUUID()}.preferences_pb") }
        val defaultOrder = CategoryOrderPreferences(store) { now }
        val listOrder = ListOrderPreferences(store) { now }
        val remote = server.client(UID)
        val repo = ListRepository(
            db = db,
            categoryOrder = defaultOrder,
            categorize = { name -> if (name.contains("mleko")) "nabial" else "inne" },
            clock = { now },
            actor = { signedIn },
        )
        val engine = SyncEngine(
            db = db,
            repo = repo,
            remote = remote,
            prefs = PrefsSync(db, remote, defaultOrder.stored, listOrder.stored, SyncMarks(store), timeoutMs = TIMEOUT_MS),
            clock = { now },
            timeoutMs = TIMEOUT_MS,
        )

        suspend fun sync() {
            engine.flush(UID)
            engine.catchUp(UID)
        }

        suspend fun state(listId: String) = repo.loadState(listId)

        suspend fun visible(listId: String): Map<String, Pair<String, Boolean>> {
            val state = state(listId)
            return state.items.values.filter { Merge.isVisible(it, state.list) }.associate { it.name to (it.categoryId to it.checked) }
        }

        suspend fun itemId(listId: String, name: String) = state(listId).items.values.first { it.name == name }.id

        suspend fun outbox() = db.outbox().count()

        init {
            phones += this
        }
    }

    @After
    fun close() = phones.forEach { it.db.close() }

    @Test
    fun twoPhonesOnOneAccountConvergeAfterEditsOnBothWhileOneWasOffline() = runBlocking {
        val a = Phone(start = 1_000_000)
        val b = Phone(start = 1_000_000)
        val listId = a.repo.createList("Sobota")
        listOf("mleko", "chleb", "masło").forEach { a.now++; a.repo.addItem(listId, it) }
        a.sync()
        b.sync()
        assertEquals(a.visible(listId), b.visible(listId))

        // B loses the network and keeps shopping.
        b.remote.online = false
        b.now = 2_000_000
        b.repo.setChecked(b.itemId(listId, "mleko"), true)
        b.now++
        b.repo.updateItem(b.itemId(listId, "chleb"), b.state(listId).items.getValue(b.itemId(listId, "chleb")).content.copy(name = "chleb żytni"))
        b.now++
        b.repo.addItem(listId, "jajka")
        try {
            b.engine.flush(UID)
            fail("an offline flush must not claim success")
        } catch (_: RemoteFailure) {
            // expected: nothing answered
        }
        assertEquals(3, b.outbox())

        // Later, A edits the same item and more.
        a.now = 3_000_000
        a.repo.updateItem(a.itemId(listId, "chleb"), a.state(listId).items.getValue(a.itemId(listId, "chleb")).content.copy(name = "chleb razowy"))
        a.now++
        a.repo.deleteItem(a.itemId(listId, "masło"))
        a.now++
        a.repo.addItem(listId, "ser")
        a.sync()

        // B comes back: its older rename is refused, its tick and its new item are not.
        b.remote.online = true
        b.sync()
        a.sync()

        assertEquals(0, a.outbox())
        assertEquals(0, b.outbox())
        assertEquals(1, server.refusals)
        val expected = mapOf(
            "mleko" to ("nabial" to true),
            "chleb razowy" to ("inne" to false),
            "jajka" to ("inne" to false),
            "ser" to ("inne" to false),
        )
        assertEquals(expected, a.visible(listId))
        assertEquals(a.state(listId), b.state(listId))
    }

    @Test
    fun aWriteOlderThanTheStoredOneIsRefusedAndTheSenderAdoptsTheNewerState() = runBlocking {
        val a = Phone(start = 1_000_000)
        val b = Phone(start = 1_000_000)
        val listId = a.repo.createList("Sobota")
        a.now++
        val itemId = a.repo.addItem(listId, "mleko").itemId
        a.sync()
        b.sync()

        b.remote.online = false
        b.now = 2_000_000
        b.repo.updateItem(itemId, b.state(listId).items.getValue(itemId).content.copy(note = "B, starsza"))
        a.now = 3_000_000
        a.repo.updateItem(itemId, a.state(listId).items.getValue(itemId).content.copy(note = "A, nowsza"))
        a.engine.flush(UID)

        b.remote.online = true
        b.engine.flush(UID)

        assertEquals(1, server.refusals)
        assertEquals(0, b.outbox())
        assertEquals("A, nowsza", b.state(listId).items.getValue(itemId).note)
        assertEquals(3_000_000L, server.node("lists/$listId/items/$itemId").asNode()["updatedAt"])
        assertEquals("A, nowsza", server.node("lists/$listId/items/$itemId").asNode()["note"])
    }

    @Test
    fun aWriteWhoseAcknowledgementIsLostStaysQueuedAndIsHarmlessToSendAgain() = runBlocking {
        val a = Phone(start = 1_000_000)
        val listId = a.repo.createList("Sobota")
        a.sync()

        a.remote.dropAcks = true
        a.now++
        val itemId = a.repo.addItem(listId, "mleko").itemId
        a.now++
        a.repo.setChecked(itemId, true)
        try {
            a.engine.flush(UID)
            fail("an unacknowledged write must not leave the outbox")
        } catch (_: RemoteFailure) {
            // expected
        }
        assertEquals(2, a.outbox())
        assertNotNull(server.node("lists/$listId/items/$itemId")) // it did arrive

        a.remote.dropAcks = false
        a.engine.flush(UID)
        assertEquals(0, a.outbox())
        assertEquals(0, server.refusals)
        assertEquals(true, server.node("lists/$listId/items/$itemId").asNode()["checked"])
    }

    @Test
    fun signingInAdoptsTheListsMadeWhileSignedOut() = runBlocking {
        val a = Phone(start = 1_000_000, signedIn = null)
        val listId = a.repo.createList("Prywatna")
        a.now++
        val itemId = a.repo.addItem(listId, "mleko").itemId
        a.now++
        a.repo.setChecked(itemId, true)
        assertNull(a.state(listId).list!!.ownerUid)
        assertEquals(12, a.outbox()) // list, nine departments, the item, the tick

        a.signedIn = UID
        a.engine.flush(UID)

        assertEquals(0, a.outbox())
        assertTrue(a.db.listSync().get(listId)!!.synced)
        val meta = server.node("lists/$listId/meta").asNode()
        assertEquals(UID, meta["ownerUid"])
        assertEquals("Prywatna", meta["name"])
        val item = server.node("lists/$listId/items/$itemId").asNode()
        assertEquals(UID, item["createdBy"])
        assertEquals(UID, item["checkedBy"])
        assertEquals(true, item["checked"])
        assertEquals(BuiltinCategories.IDS.toSet(), server.node("lists/$listId/categories").asNode().keys)
        assertEquals("owner", server.node("userLists/$UID").asNode()[listId])

        // And another phone signed in as the same account finds it.
        val b = Phone(start = 1_000_000)
        b.sync()
        assertEquals(a.visible(listId), b.visible(listId))
        assertEquals(UID, b.state(listId).list!!.ownerUid)
    }

    @Test
    fun aListDeletedOnOnePhoneIsGoneOnTheOtherAndIsRemovedFromRtdbAfterThirtyDays() = runBlocking {
        val a = Phone(start = 1_000_000)
        val b = Phone(start = 1_000_000)
        val listId = a.repo.createList("Sobota")
        a.now++
        a.repo.addItem(listId, "mleko")
        a.sync()
        b.sync()

        a.now++
        a.repo.deleteList(listId)
        a.sync()
        assertNull(server.node("lists/$listId/items"))
        assertNotNull(server.node("lists/$listId/meta/deletedAt"))
        b.sync()
        assertTrue(b.repo.observeLists().first().none { it.list.id == listId })

        b.now = a.now + Merge.TOMBSTONE_KEEP_MS + 1
        b.sync()
        assertNull(server.node("lists/$listId"))
        assertNull(server.node("userLists/$UID"))
        assertNull(b.db.lists().get(listId))
    }

    @Test
    fun preferencesFollowTheAccountToTheOtherPhone() = runBlocking {
        val a = Phone(start = 1_000_000)
        val b = Phone(start = 1_000_000)
        val order = listOf("napoje", "pieczywo") + BuiltinCategories.IDS.filterNot { it in setOf("napoje", "pieczywo") }
        a.defaultOrder.setOrder(order)
        val listId = a.repo.createList("Sobota")
        a.now++
        a.repo.addItem(listId, "kefir", categoryId = "nabial") // the user filed it; the dictionary would not
        a.listOrder.setOrder(listOf(listId))
        a.sync()

        b.sync()
        assertEquals(order, b.defaultOrder.order.first())
        assertEquals(listOf(listId), b.listOrder.order.first())
        assertEquals("nabial", b.repo.proposeCategory(listId, "kefir"))

        // A newer choice on B wins on A, and an older one does not come back.
        b.now = 5_000_000
        b.defaultOrder.setOrder(BuiltinCategories.IDS)
        b.sync()
        a.sync()
        assertEquals(BuiltinCategories.IDS, a.defaultOrder.order.first())
        assertFalse(a.defaultOrder.order.first() == order)
    }

    private companion object {
        /** A made-up uid: the repository is public (CLAUDE.md). */
        const val UID = "test-uid-1"
        const val TIMEOUT_MS = 500L
    }
}
