package dev.gorny.buymyway.core.photo

import dev.gorny.buymyway.core.photo.CachePolicy.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo disk cache's cap (PLAN.md Phase 6, acceptance criteria; STATE.md decision 70). */
class CachePolicyTest {
    @Test
    fun nothingIsEvictedWithinTheCap() {
        val entries = (1..10).map { Entry("p$it", 1_000, it.toLong()) }
        assertEquals(emptyList<Entry>(), CachePolicy.evict(entries, maxBytes = 10_000))
    }

    @Test
    fun theLeastRecentlyUsedGoFirstUntilNinetyPercent() {
        val entries = (1..12).map { Entry("p$it", 1_000, (100 - it).toLong()) }
        val evicted = CachePolicy.evict(entries, maxBytes = 10_000)
        // 12 000 bytes, target 9 000: the three used longest ago go.
        assertEquals(listOf("p12", "p11", "p10"), evicted.map { it.name })
    }

    @Test
    fun fiftyMegabytesOfEightyKilobytePhotosStaysUnderTheCap() {
        val entries = (1..1_000).map { Entry("p$it", 80 * 1024L, it.toLong()) }
        val kept = entries - CachePolicy.evict(entries).toSet()
        assertTrue(kept.sumOf { it.bytes } <= CachePolicy.MAX_BYTES)
        assertTrue("the newest stay", kept.all { it.usedAt > 400 })
    }

    @Test
    fun fileNamesRoundTrip() {
        val itemId = "0b6f3d1e-7c7a-4a8e-9a55-2f0d6e1c9b12"
        val name = CachePolicy.fileName(itemId, 1_758_000_000_000)
        assertEquals("$itemId-1758000000000.webp", name)
        assertEquals(itemId, CachePolicy.itemOf(name))
        assertNull(CachePolicy.itemOf("$itemId.webp.tmp"))
        assertNull(CachePolicy.itemOf("notes.txt"))
    }
}
