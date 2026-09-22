package dev.gorny.buymyway.data.photo

import dev.gorny.buymyway.core.photo.CachePolicy
import java.io.File

/**
 * Photos on disk, in `cacheDir/photos` (PLAN.md Phase 6, task 3; STATE.md decision 70): one
 * file per version of an item's photo, capped at 50 MB, the least recently used removed first.
 * A photo found here is never read from RTDB again. Android may empty `cacheDir` when storage
 * runs low; a photo is then fetched again the next time its row is shown.
 */
class PhotoCache(
    private val dir: File,
    private val maxBytes: Long = CachePolicy.MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun get(itemId: String, photoAt: Long): ByteArray? {
        val file = File(dir, CachePolicy.fileName(itemId, photoAt))
        if (!file.isFile) return null
        file.setLastModified(clock()) // "used": what the cap keeps longest
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun contains(itemId: String, photoAt: Long): Boolean = File(dir, CachePolicy.fileName(itemId, photoAt)).isFile

    /** Stores a version of an item's photo; the item's older versions go. */
    @Synchronized
    fun put(itemId: String, photoAt: Long, bytes: ByteArray) {
        dir.mkdirs()
        val name = CachePolicy.fileName(itemId, photoAt)
        dir.listFiles().orEmpty().filter { it.name != name && CachePolicy.itemOf(it.name) == itemId }.forEach { it.delete() }
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        val file = File(dir, name)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return
        }
        file.setLastModified(clock())
        trim()
    }

    /** Everything the cache holds, in bytes. */
    @Synchronized
    fun size(): Long = dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    /** Sign-out. */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    private fun trim() {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val entries = files.map { CachePolicy.Entry(it.name, it.length(), it.lastModified()) }
        val evicted = CachePolicy.evict(entries, maxBytes).map { it.name }.toSet()
        files.filter { it.name in evicted }.forEach { it.delete() }
    }
}
