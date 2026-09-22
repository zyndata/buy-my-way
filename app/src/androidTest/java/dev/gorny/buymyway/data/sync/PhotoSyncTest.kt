package dev.gorny.buymyway.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.photo.PhotoSizing
import dev.gorny.buymyway.core.share.InviteLinks
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.sync.NodeCodec
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.photo.PhotoCache
import dev.gorny.buymyway.data.photo.PhotoLoader
import dev.gorny.buymyway.data.photo.PhotoOutbox
import dev.gorny.buymyway.data.photo.PhotoRef
import dev.gorny.buymyway.data.photo.Photos
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.RemoteLists
import dev.gorny.buymyway.data.remote.asNode
import dev.gorny.buymyway.data.share.Sharing
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * Photos between two phones (PLAN.md Phase 6, tasks 2 and 4, and the acceptance criteria;
 * STATE.md decisions 71 and 72), over the [FakeServer], which mirrors the `/photos` rules. What
 * `PhotoWorker` runs is [Photos.send]; here the test runs it.
 */
@RunWith(AndroidJUnit4::class)
class PhotoSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()
    private val phones = mutableListOf<Phone>()

    inner class Phone(val uid: String, val name: String) {
        var now = 1_000_000L
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val remote = server.client(uid)
        val dir = File(context.cacheDir, "photo-sync-${UUID.randomUUID()}")
        val repo = ListRepository(db, { BuiltinCategories.IDS }, { "inne" }, clock = { now }, actor = { uid })
        val engine = SyncEngine(db, repo, remote, clock = { now }, timeoutMs = TIMEOUT_MS)
        private val random = Random(uid.hashCode())
        val sharing = Sharing(remote, engine, repo, Connection {}, { Sharing.Me(uid, name) }, { random.nextBytes(it) }, { now }, TIMEOUT_MS)
        val outbox = PhotoOutbox(File(dir, "outbox"))
        val cache = PhotoCache(File(dir, "cache")) { now }
        val loader = PhotoLoader(cache, outbox) { listId, itemId ->
            try {
                Photos.decode(remote.read(RemoteWrites.photo(listId, itemId)))
            } catch (_: RemoteDenied) {
                null
            }
        }
        var scheduled = 0
        val photos = Photos(repo, remote, outbox, cache, loader, { scheduled++ }, { now }, TIMEOUT_MS)

        suspend fun sync() {
            engine.flush(uid)
            engine.catchUp(uid)
        }

        /** What `PhotoWorker` does: the photos, then the `photoAt` they lead to. */
        suspend fun sendPhotos() {
            photos.send(uid)
            engine.flush(uid)
        }

        suspend fun itemId(listId: String, name: String) =
            repo.loadState(listId).items.values.first { it.name == name && it.deletedAt == null }.id

        suspend fun item(listId: String, name: String) = repo.loadState(listId).items.getValue(itemId(listId, name))

        suspend fun rowPhoto(listId: String, name: String): PhotoRef? {
            val item = item(listId, name)
            return PhotoRef.of(listId, item.id, item.photoAt, photos.pending.first()[item.id])
        }

        init {
            phones += this
        }
    }

    @After
    fun close() = phones.forEach {
        it.db.close()
        it.dir.deleteRecursively()
    }

    /** Alice's list, shared with Bob as an editor and Carol as a viewer. */
    private suspend fun shared(alice: Phone, bob: Phone, carol: Phone? = null): String {
        alice.engine.writeProfile(alice.uid, alice.name, null, null)
        val listId = alice.repo.createList("Sobota")
        listOf("mleko", "chleb").forEach { alice.now++; alice.repo.addItem(listId, it) }
        alice.sync()
        for ((phone, role) in listOfNotNull(bob to Role.EDITOR, carol?.let { it to Role.VIEWER })) {
            val token = InviteLinks.tokenOf(alice.sharing.inviteLink(listId, role))!!
            phone.sharing.accept(token)
        }
        return listId
    }

    /** A camera-like JPEG, small enough to be quick. */
    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun photoNode(listId: String, itemId: String) = server.node(RemoteWrites.photo(listId, itemId)).asNode()

    @Test
    fun aPhotoTakenOnOnePhoneReachesTheOtherAndRemovingItRemovesTheNode() = runBlocking {
        val alice = Phone(ALICE, "Alice")
        val bob = Phone(BOB, "Bob")
        val listId = shared(alice, bob)
        val milk = alice.itemId(listId, "mleko")

        // Taken: shown on Alice's phone at once, from the outbox; nothing in RTDB yet.
        alice.now = 2_000_000
        val camera = jpeg(Color.BLUE)
        alice.photos.set(listId, milk, camera::inputStream)
        assertEquals(1, alice.scheduled)
        assertEquals(PhotoRef.Local(milk, 2_000_000), alice.rowPhoto(listId, "mleko"))
        assertNull(alice.item(listId, "mleko").photoAt)

        // Offline: the photo is not written, so photoAt is not either.
        alice.remote.online = false
        try {
            alice.photos.send(ALICE)
            fail("no answer while offline")
        } catch (_: RemoteFailure) {
            // expected: PhotoWorker retries
        }
        alice.remote.online = true
        assertTrue(photoNode(listId, milk).isEmpty())
        assertNull(alice.item(listId, "mleko").photoAt)

        // Online: the photo first, then photoAt on the item.
        alice.sendPhotos()
        val node = photoNode(listId, milk)
        assertEquals(ALICE, node["by"])
        assertEquals(2_000_000L, node["at"])
        assertTrue((node["webp"] as String).length <= 110_000)
        assertEquals(2_000_000L, alice.item(listId, "mleko").photoAt)
        assertEquals(2_000_000L, server.node("lists/$listId/items/$milk").asNode()["photoAt"])
        assertEquals(PhotoRef.Remote(listId, milk, 2_000_000), alice.rowPhoto(listId, "mleko"))

        // Bob's row: one read from RTDB, then from his cache. Alice never downloads her own.
        bob.sync()
        val ref = bob.rowPhoto(listId, "mleko")!!
        assertEquals(PhotoRef.Remote(listId, milk, 2_000_000), ref)
        val bytes = bob.loader.bytes(ref)!!
        assertTrue(bytes.size <= PhotoSizing.MAX_BYTES)
        assertArrayEquals(NodeCodec.photoFromNode(node)!!.webp, bytes)
        bob.loader.bytes(ref)
        assertEquals(1, bob.loader.fetches)
        assertArrayEquals(bytes, alice.loader.bytes(PhotoRef.Remote(listId, milk, 2_000_000)))
        assertEquals(0, alice.loader.fetches)

        // Bob removes it: gone from his row at once, then from RTDB, then from Alice's row.
        bob.now = 3_000_000
        bob.photos.remove(listId, milk)
        assertNull(bob.rowPhoto(listId, "mleko"))
        bob.sendPhotos()
        assertNull(server.node(RemoteWrites.photo(listId, milk)))
        alice.sync()
        assertNull(alice.rowPhoto(listId, "mleko"))
    }

    @Test
    fun aReplacementWinsAndARemovalNeverTakesANewerPhoto() = runBlocking {
        val alice = Phone(ALICE, "Alice")
        val bob = Phone(BOB, "Bob")
        val listId = shared(alice, bob)
        val bread = alice.itemId(listId, "chleb")
        alice.now = 2_000_000
        alice.photos.set(listId, bread, jpeg(Color.RED)::inputStream)
        alice.sendPhotos()
        bob.sync()

        // Bob removes the photo at 3 000 000, but sends it only after Alice put a new one at
        // 4 000 000: hers stays.
        bob.now = 3_000_000
        bob.photos.remove(listId, bread)
        alice.now = 4_000_000
        alice.photos.set(listId, bread, jpeg(Color.GREEN)::inputStream)
        alice.sendPhotos()
        bob.sendPhotos()
        assertEquals(4_000_000L, photoNode(listId, bread)["at"])
        alice.sync()
        bob.sync()
        // Alice's photoAt came later than Bob's removal, so it wins on both phones.
        assertEquals(4_000_000L, alice.item(listId, "chleb").photoAt)
        assertEquals(4_000_000L, bob.item(listId, "chleb").photoAt)

        // An older photo never replaces a newer one on the server.
        val older = NodeCodec.Photo(byteArrayOf(1, 2, 3), 1, 1, BOB, 3_500_000)
        val refused = runCatching { bob.remote.update(RemoteWrites.putPhoto(listId, bread, older)).await() }
        assertTrue(refused.exceptionOrNull() is RemoteDenied)
    }

    @Test
    fun aPhotoRemovedWhileItWasBeingSentDoesNotComeBack() = runBlocking {
        val alice = Phone(ALICE, "Alice")
        val bob = Phone(BOB, "Bob")
        val listId = shared(alice, bob)
        val milk = alice.itemId(listId, "mleko")
        alice.now = 2_000_000
        alice.photos.set(listId, milk, jpeg(Color.BLUE)::inputStream)

        // The server has the photo and has said so, and just then, before the phone names it on
        // the item, „Usuń zdjęcie" is tapped.
        var removedMidway = false
        val hooked = object : RemoteLists by alice.remote {
            override fun update(paths: Map<String, Any?>): Deferred<Unit> {
                val result = alice.remote.update(paths)
                if (!removedMidway && paths.keys.any { it.startsWith("photos/") } && paths.values.none { it == null }) {
                    removedMidway = true
                    alice.now = 2_500_000
                    runBlocking { alice.photos.remove(listId, milk) }
                }
                return result
            }
        }
        val sender = Photos(alice.repo, hooked, alice.outbox, alice.cache, alice.loader, {}, { alice.now }, TIMEOUT_MS)
        sender.send(ALICE)
        alice.engine.flush(ALICE)

        assertTrue(removedMidway)
        assertNull(server.node(RemoteWrites.photo(listId, milk)))
        assertNull(alice.item(listId, "mleko").photoAt)
        bob.sync()
        assertNull(bob.item(listId, "mleko").photoAt)
    }

    @Test
    fun aViewerSeesPhotosAndCannotSetOrRemoveThem() = runBlocking {
        val alice = Phone(ALICE, "Alice")
        val bob = Phone(BOB, "Bob")
        val carol = Phone(CAROL, "Carol")
        val listId = shared(alice, bob, carol)
        val milk = alice.itemId(listId, "mleko")
        alice.now = 2_000_000
        alice.photos.set(listId, milk, jpeg(Color.BLUE)::inputStream)
        alice.sendPhotos()

        carol.sync()
        assertNotNull(carol.loader.bytes(carol.rowPhoto(listId, "mleko")!!))
        carol.photos.set(listId, milk, jpeg(Color.RED)::inputStream)
        carol.photos.remove(listId, milk)
        assertEquals(0, carol.scheduled)
        assertEquals(2_000_000L, carol.item(listId, "mleko").photoAt)
        // The server refuses her too, whatever her phone does.
        val photo = NodeCodec.Photo(byteArrayOf(1), 1, 1, CAROL, 3_000_000)
        assertTrue(runCatching { carol.remote.update(RemoteWrites.putPhoto(listId, milk, photo)).await() }.exceptionOrNull() is RemoteDenied)
        assertTrue(runCatching { carol.remote.update(RemoteWrites.removePhoto(listId, milk)).await() }.exceptionOrNull() is RemoteDenied)
    }

    @Test
    fun aPhotoOnAListNotInRtdbWaitsForItsUpload() = runBlocking {
        val alice = Phone(ALICE, "Alice")
        val listId = alice.repo.createList("Prywatna")
        alice.repo.addItem(listId, "mleko")
        val milk = alice.itemId(listId, "mleko")
        alice.now = 2_000_000
        alice.photos.set(listId, milk, jpeg(Color.BLUE)::inputStream)
        // Not in RTDB yet: shown from the phone, not scheduled, not sent.
        assertEquals(0, alice.scheduled)
        assertEquals(PhotoRef.Local(milk, 2_000_000), alice.rowPhoto(listId, "mleko"))
        alice.photos.send(ALICE)
        assertNull(server.node("photos/$listId"))

        // Once the list is uploaded, the photo can go.
        alice.sync()
        assertTrue(alice.photos.ready())
        alice.sendPhotos()
        assertEquals(2_000_000L, photoNode(listId, milk)["at"])
        assertEquals(2_000_000L, alice.item(listId, "mleko").photoAt)
    }

    @Test
    fun deletingAListRemovesItsPhotosAndOldTombstonesTakeTheirs() = runBlocking {
        val alice = Phone(ALICE, "Alice")
        val bob = Phone(BOB, "Bob")
        val listId = shared(alice, bob)
        val milk = alice.itemId(listId, "mleko")
        val bread = alice.itemId(listId, "chleb")
        alice.now = 2_000_000
        alice.photos.set(listId, milk, jpeg(Color.BLUE)::inputStream)
        alice.photos.set(listId, bread, jpeg(Color.RED)::inputStream)
        alice.sendPhotos()

        // A deleted item keeps its photo 30 days, then the owner's cleanup takes both.
        alice.repo.deleteItem(bread)
        alice.sync()
        assertTrue(photoNode(listId, bread).isNotEmpty())
        alice.now += Merge.TOMBSTONE_KEEP_MS + 1_000
        alice.sync()
        assertNull(server.node(RemoteWrites.photo(listId, bread)))
        assertTrue(photoNode(listId, milk).isNotEmpty())

        // Deleting the list takes every photo at once.
        alice.repo.deleteList(listId)
        alice.sync()
        assertNull(server.node("photos/$listId"))
    }

    private companion object {
        /** Made-up uids: the repository is public (CLAUDE.md). */
        const val ALICE = "test-alice"
        const val BOB = "test-bob"
        const val CAROL = "test-carol"
        const val TIMEOUT_MS = 500L
    }
}
