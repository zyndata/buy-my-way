package dev.gorny.buymyway.data.photo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A photo change this phone made and RTDB does not have yet (STATE.md decision 71): a photo to
 * put, or a removal. One per item: a newer change replaces the one before it.
 *
 * For a removal, [at] is when it was made: the node is removed only if it is not newer.
 */
@Serializable
data class PendingPhoto(
    val listId: String,
    val itemId: String,
    val at: Long,
    val remove: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Where [PendingPhoto]s wait for `PhotoWorker`, in `filesDir`, one directory per item: the WebP
 * and a small JSON file written after it, so a change is there whole or not at all. Files, not
 * Room: the bytes would not belong in the database anyway, and no schema change is needed.
 */
class PhotoOutbox(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _pending = MutableStateFlow<Map<String, PendingPhoto>>(emptyMap())
    private var loaded = false

    /** Every waiting change, by item id. */
    val pending: StateFlow<Map<String, PendingPhoto>>
        get() {
            load()
            return _pending.asStateFlow()
        }

    @Synchronized
    fun all(): List<PendingPhoto> {
        load()
        return _pending.value.values.sortedWith(compareBy({ it.at }, { it.itemId }))
    }

    @Synchronized
    fun get(itemId: String): PendingPhoto? {
        load()
        return _pending.value[itemId]
    }

    /** The WebP of a photo waiting to be put, or null. */
    @Synchronized
    fun file(itemId: String): File? {
        val photo = get(itemId)?.takeIf { !it.remove } ?: return null
        return File(itemDir(photo.itemId), PHOTO).takeIf { it.isFile }
    }

    @Synchronized
    fun put(listId: String, itemId: String, at: Long, photo: ProcessedPhoto) {
        load()
        val change = PendingPhoto(listId, itemId, at, remove = false, width = photo.width, height = photo.height)
        val target = itemDir(itemId)
        target.deleteRecursively()
        target.mkdirs()
        writeAtomically(File(target, PHOTO), photo.webp)
        writeAtomically(File(target, META), json.encodeToString(PendingPhoto.serializer(), change).toByteArray())
        _pending.value += itemId to change
    }

    @Synchronized
    fun markRemoved(listId: String, itemId: String, at: Long) {
        load()
        val change = PendingPhoto(listId, itemId, at, remove = true)
        val target = itemDir(itemId)
        target.deleteRecursively()
        target.mkdirs()
        writeAtomically(File(target, META), json.encodeToString(PendingPhoto.serializer(), change).toByteArray())
        _pending.value += itemId to change
    }

    /** Forgets whatever waits for [itemId]: the item is gone, or its photo never left the phone. */
    @Synchronized
    fun discard(itemId: String) {
        load()
        itemDir(itemId).deleteRecursively()
        _pending.value -= itemId
    }

    /**
     * Done with [change]: forgotten, if it is still the one waiting. False when a newer change
     * replaced it while it was being sent, which then goes next.
     */
    @Synchronized
    fun complete(change: PendingPhoto): Boolean {
        if (get(change.itemId) != change) return false
        discard(change.itemId)
        return true
    }

    /** Sign-out. */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        _pending.value = emptyMap()
        loaded = true
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val found = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { item ->
            val meta = File(item, META)
            val change = runCatching { json.decodeFromString(PendingPhoto.serializer(), meta.readText()) }.getOrNull()
            if (change == null || change.itemId != item.name || (!change.remove && !File(item, PHOTO).isFile)) {
                item.deleteRecursively() // half written when the app died: as if never made
                null
            } else {
                change
            }
        }
        _pending.value = found.associateBy { it.itemId }
    }

    private fun itemDir(itemId: String): File {
        require(itemId.isNotEmpty() && itemId.none { it == '/' || it == '\\' } && itemId != "." && itemId != "..") { "not an item id" }
        return File(dir, itemId)
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("could not write ${file.name}")
        }
    }

    private companion object {
        const val PHOTO = "photo.webp"
        const val META = "photo.json"
    }
}
