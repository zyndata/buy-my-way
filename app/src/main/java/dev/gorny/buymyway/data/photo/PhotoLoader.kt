package dev.gorny.buymyway.data.photo

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.gorny.buymyway.core.sync.NodeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Which photo a row shows: one still on the phone, or one in RTDB. */
sealed interface PhotoRef {
    val itemId: String

    /** Taken here and not sent yet (decision 71): read from the photo outbox. */
    data class Local(override val itemId: String, val at: Long) : PhotoRef

    /** The item's `photoAt`: read from the cache, or from `/photos` once. */
    data class Remote(val listId: String, override val itemId: String, val photoAt: Long) : PhotoRef

    companion object {
        /** A photo waiting to be sent wins: it is the newer one. */
        fun of(listId: String, itemId: String, photoAt: Long?, pendingAt: Long?): PhotoRef? = when {
            pendingAt != null -> Local(itemId, pendingAt)
            photoAt != null -> Remote(listId, itemId, photoAt)
            else -> null
        }
    }
}

/**
 * Turns a [PhotoRef] into a bitmap (PLAN.md Phase 6, task 3; STATE.md decision 70): from memory,
 * else from the disk cache, else from RTDB through [fetch], which is stored in the cache before
 * it is decoded. Two rows asking for the same photo at once cause one read. [fetch] returns null
 * when it cannot read (signed out, offline, no such node); nothing is cached then, and the row
 * asks again the next time it is shown.
 */
class PhotoLoader(
    private val cache: PhotoCache,
    private val outbox: PhotoOutbox,
    private val fetch: suspend (listId: String, itemId: String) -> NodeCodec.Photo?,
) {
    /** Decoded bitmaps by byte size: an eighth of the heap, as Android's guides suggest. */
    private val memory = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val locks = HashMap<String, Mutex>()

    /** Reads from RTDB since the process started: what the "never downloaded twice" test counts. */
    @Volatile
    var fetches = 0
        private set

    /** The photo scaled down so its longer side is at least [maxPx] (or its own size), or null. */
    suspend fun load(ref: PhotoRef, maxPx: Int): ImageBitmap? {
        val key = "${keyOf(ref)}@$maxPx"
        memory.get(key)?.let { return it }
        val bytes = bytes(ref) ?: return null
        val bitmap = withContext(Dispatchers.Default) { decode(bytes, maxPx) } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /** The WebP bytes of [ref], from the outbox, the cache or RTDB. */
    suspend fun bytes(ref: PhotoRef): ByteArray? = when (ref) {
        is PhotoRef.Local -> withContext(Dispatchers.IO) { outbox.file(ref.itemId)?.let { runCatching { it.readBytes() }.getOrNull() } }
        is PhotoRef.Remote -> lockFor(keyOf(ref)).withLock {
            withContext(Dispatchers.IO) { cache.get(ref.itemId, ref.photoAt) } ?: fetchInto(ref)
        }
    }

    /** Sign-out. */
    fun clear() {
        memory.evictAll()
    }

    private suspend fun fetchInto(ref: PhotoRef.Remote): ByteArray? {
        fetches++
        val photo = fetch(ref.listId, ref.itemId) ?: return null
        withContext(Dispatchers.IO) { cache.put(ref.itemId, ref.photoAt, photo.webp) }
        return photo.webp
    }

    private fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private fun keyOf(ref: PhotoRef): String = when (ref) {
        is PhotoRef.Local -> "local:${ref.itemId}:${ref.at}"
        is PhotoRef.Remote -> "remote:${ref.itemId}:${ref.photoAt}"
    }

    @Synchronized
    private fun lockFor(key: String): Mutex = locks.getOrPut(key) { Mutex() }
}
