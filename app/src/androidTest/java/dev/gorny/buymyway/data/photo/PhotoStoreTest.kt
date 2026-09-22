package dev.gorny.buymyway.data.photo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.photo.CachePolicy
import dev.gorny.buymyway.core.sync.NodeCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * The photo disk cache and the photo outbox on a real file system (PLAN.md Phase 6, task 3 and
 * the acceptance criteria; STATE.md decisions 70 and 71): the cap holds, viewing does not grow
 * storage, a cached photo is not fetched again, and a waiting photo survives the process.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "store-test-${UUID.randomUUID()}")
    private var now = 1_000_000_000_000L

    @After
    fun clean() {
        root.deleteRecursively()
    }

    private fun photo(seed: Int, size: Int = 80 * 1024) = Random(seed).nextBytes(size)

    @Test
    fun theDiskCacheStaysUnderItsCapAndKeepsWhatWasUsedLast() {
        val cap = 800L * 1024 // ten 80 kB photos; the real cap is 50 MB (CachePolicy.MAX_BYTES)
        val cache = PhotoCache(File(root, "cache"), maxBytes = cap) { now }
        for (i in 1..10) {
            now += 1_000
            cache.put("item-$i", 1, photo(i))
        }
        // Looking at the first one again makes it the most recently used.
        now += 1_000
        assertNotNull(cache.get("item-1", 1))
        // Viewing forty more photos: storage does not grow past the cap.
        for (i in 11..50) {
            now += 1_000
            cache.put("item-$i", 1, photo(i))
            assertTrue("after $i: ${cache.size()}", cache.size() <= cap)
        }
        assertNotNull("used last, kept", cache.get("item-50", 1))
        assertNull("used longest ago, gone", cache.get("item-2", 1))
    }

    @Test
    fun aNewVersionReplacesTheOldOneAndTheCapIsFiftyMegabytes() {
        val cache = PhotoCache(File(root, "cache")) { now }
        cache.put("item-1", 1, photo(1))
        cache.put("item-1", 2, photo(2))
        assertNull(cache.get("item-1", 1))
        assertArrayEquals(photo(2), cache.get("item-1", 2))
        assertEquals(80 * 1024L, cache.size())
        assertEquals(50L * 1024 * 1024, CachePolicy.MAX_BYTES)
    }

    @Test
    fun aCachedPhotoIsNotFetchedAgainAndTwoRowsAtOnceFetchOnce() = runBlocking {
        val cache = PhotoCache(File(root, "cache")) { now }
        val outbox = PhotoOutbox(File(root, "outbox"))
        var reads = 0
        val loader = PhotoLoader(cache, outbox) { _, _ ->
            reads++
            delay(50) // the network
            NodeCodec.Photo(photo(3, 1_000), 10, 10, "uid", 7)
        }
        val ref = PhotoRef.Remote("list-1", "item-1", 7)
        val first = async { loader.bytes(ref) }
        val second = async { loader.bytes(ref) }
        assertArrayEquals(first.await(), second.await())
        assertEquals(1, reads)
        // A new loader (a new process): still from disk.
        val again = PhotoLoader(cache, outbox) { _, _ -> error("must not be fetched") }
        assertArrayEquals(photo(3, 1_000), again.bytes(ref))
        assertEquals(1, reads)
    }

    @Test
    fun nothingIsCachedWhenTheFetchFails() = runBlocking {
        val cache = PhotoCache(File(root, "cache")) { now }
        val loader = PhotoLoader(cache, PhotoOutbox(File(root, "outbox"))) { _, _ -> null }
        assertNull(loader.bytes(PhotoRef.Remote("list-1", "item-1", 7)))
        assertFalse(cache.contains("item-1", 7))
    }

    @Test
    fun aWaitingPhotoSurvivesTheProcessAndAHalfWrittenOneIsForgotten() {
        val dir = File(root, "outbox")
        val outbox = PhotoOutbox(dir)
        outbox.put("list-1", "item-1", 10, ProcessedPhoto(photo(1, 500), 20, 10))
        outbox.markRemoved("list-1", "item-2", 11)
        // The app died while writing a third one: its photo is there, its description is not.
        File(dir, "item-3").mkdirs()
        File(dir, "item-3/photo.webp").writeBytes(photo(3, 500))

        val reopened = PhotoOutbox(dir)
        assertEquals(
            listOf(PendingPhoto("list-1", "item-1", 10, width = 20, height = 10), PendingPhoto("list-1", "item-2", 11, remove = true)),
            reopened.all(),
        )
        assertArrayEquals(photo(1, 500), reopened.file("item-1")!!.readBytes())
        assertNull(reopened.file("item-2"))
        assertFalse(File(dir, "item-3").exists())
    }

    @Test
    fun aChangeIsCompletedOnlyIfNothingNewerReplacedIt() {
        val outbox = PhotoOutbox(File(root, "outbox"))
        outbox.put("list-1", "item-1", 10, ProcessedPhoto(photo(1, 500), 20, 10))
        val sending = outbox.get("item-1")!!
        outbox.put("list-1", "item-1", 12, ProcessedPhoto(photo(2, 500), 20, 10))
        assertFalse(outbox.complete(sending))
        assertEquals(12L, outbox.get("item-1")!!.at)
        assertTrue(outbox.complete(outbox.get("item-1")!!))
        assertEquals(emptyList<PendingPhoto>(), outbox.all())
    }
}
