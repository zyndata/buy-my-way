package dev.gorny.buymyway.core.photo

/**
 * What the photo disk cache removes (STATE.md decision 70), pure. The cache is one file per
 * photo version; when it holds more than its cap, the least recently used files go until it is
 * back under [trimTo] of the cap, so it does not trim again on the very next photo.
 */
object CachePolicy {
    /** The disk cache's cap (PLAN.md Phase 6, acceptance criteria). */
    const val MAX_BYTES = 50L * 1024 * 1024

    data class Entry(val name: String, val bytes: Long, val usedAt: Long)

    /** The entries to delete, oldest use first; empty while the total is within [maxBytes]. */
    fun evict(entries: Collection<Entry>, maxBytes: Long = MAX_BYTES, trimTo: Double = 0.9): List<Entry> {
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val target = (maxBytes * trimTo).toLong()
        val out = mutableListOf<Entry>()
        for (entry in entries.sortedWith(compareBy<Entry>({ it.usedAt }, { it.name }))) {
            if (total <= target) break
            out += entry
            total -= entry.bytes
        }
        return out
    }

    /** The file name of one version of an item's photo. */
    fun fileName(itemId: String, photoAt: Long): String = "$itemId-$photoAt.webp"

    /** The item a cache file belongs to, or null for a file that is not one. */
    fun itemOf(fileName: String): String? {
        if (!fileName.endsWith(".webp")) return null
        val stem = fileName.removeSuffix(".webp")
        val dash = stem.lastIndexOf('-')
        if (dash <= 0 || stem.substring(dash + 1).toLongOrNull() == null) return null
        return stem.substring(0, dash)
    }
}
