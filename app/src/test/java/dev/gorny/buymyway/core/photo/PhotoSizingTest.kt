package dev.gorny.buymyway.core.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers behind a stored photo (PLAN.md Phase 6, task 1; STATE.md decision 71). */
class PhotoSizingTest {
    @Test
    fun aTwelveMegapixelPhotoIsStoredAtEightHundredOnItsLongerSide() {
        assertEquals(800 to 600, PhotoSizing.fit(4000, 3000))
        assertEquals(600 to 800, PhotoSizing.fit(3000, 4000))
        assertEquals(800 to 450, PhotoSizing.fit(4032, 2268))
    }

    @Test
    fun aSmallImageIsNotEnlarged() {
        assertEquals(640 to 480, PhotoSizing.fit(640, 480))
        assertEquals(800 to 1, PhotoSizing.fit(8000, 3))
    }

    @Test
    fun theSampleSizeDecodesAtLeastTheTarget() {
        // 4000 / 4 = 1000 ≥ 800, 4000 / 8 = 500 < 800.
        assertEquals(4, PhotoSizing.sampleSize(4000, 3000))
        assertEquals(1, PhotoSizing.sampleSize(800, 600))
        assertEquals(1, PhotoSizing.sampleSize(1500, 1000))
        assertEquals(2, PhotoSizing.sampleSize(1000, 1600))
        for (side in listOf(801, 1599, 1600, 3199, 3200, 9000)) {
            val sample = PhotoSizing.sampleSize(side, side / 2)
            assertTrue("side $side", side / sample >= 800 && side / (sample * 2) < 800)
        }
    }

    @Test
    fun exifOrientationsFiveToEightSwapTheSides() {
        assertEquals(600 to 800, PhotoSizing.oriented(800, 600, 6))
        assertEquals(600 to 800, PhotoSizing.oriented(800, 600, 8))
        assertEquals(800 to 600, PhotoSizing.oriented(800, 600, 3))
        assertEquals(800 to 600, PhotoSizing.oriented(800, 600, 1))
        assertEquals(800 to 600, PhotoSizing.oriented(800, 600, 0))
    }

    @Test
    fun theSearchStepsTheQualityDownAtFullSizeFirst() {
        val tried = mutableListOf<Triple<Int, Int, Int>>()
        // Pretend a quality-50 image of 800 × 600 fits.
        val chosen = PhotoSizing.search(
            4000, 3000,
            encode = { w, h, q -> tried += Triple(w, h, q); Triple(w, h, q) },
            sizeOf = { (w, _, q) -> if (w == 800 && q <= 50) 70_000 else 100_000 },
        )
        assertEquals(Triple(800, 600, 50), chosen)
        assertEquals(listOf(80, 70, 60, 50), tried.map { it.third })
    }

    @Test
    fun thenTheImageShrinksUntilItFits() {
        val tried = mutableListOf<Pair<Int, Int>>()
        val chosen = PhotoSizing.search(
            4000, 3000,
            encode = { w, h, q -> tried += w to h; Triple(w, h, q) },
            // Bytes grow with the area: only a small enough image fits at quality 30.
            sizeOf = { (w, h, q) -> w * h * q / 100 },
        )!!
        assertTrue(chosen.first * chosen.second * chosen.third / 100 <= PhotoSizing.MAX_BYTES)
        assertTrue("it shrank", chosen.first < 800)
        assertEquals(4f / 3f, chosen.first.toFloat() / chosen.second, 0.02f)
        assertEquals(800 to 600, tried.first())
    }

    @Test
    fun nothingFitsGivesNull() {
        assertNull(PhotoSizing.search(4000, 3000, encode = { _, _, _ -> Unit }, sizeOf = { Int.MAX_VALUE }))
    }

    @Test
    fun theRulesCapHoldsTheLargestPhotoInBase64() {
        // firebase/database.rules.json caps `webp` at 110 000 characters (decision 72).
        val base64 = (PhotoSizing.MAX_BYTES + 2) / 3 * 4
        assertTrue("$base64", base64 <= 110_000)
    }
}
