package dev.gorny.buymyway.core.photo

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The numbers behind a photo (PLAN.md Phase 6, task 1; STATE.md decision 71), pure so they are
 * tested on the JVM. The Android side (`data/photo/PhotoProcessor`) decodes, scales, turns and
 * encodes; everything it decides by is here.
 */
object PhotoSizing {
    /** The longer side of a stored photo. */
    const val MAX_SIDE = 800

    /** A stored photo's WebP bytes at most: 80 kB. The rules cap its base64 at 110 000 chars. */
    const val MAX_BYTES = 80 * 1024

    /** WebP qualities tried in turn, until one fits in [MAX_BYTES]. */
    val QUALITIES: List<Int> = listOf(80, 70, 60, 50, 40, 30)

    /** When even the lowest quality is too big, the image shrinks by this much and tries again. */
    const val SHRINK = 0.85

    /** The size a photo is stored at: the longer side at most [maxSide], the aspect kept. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "an image has a size" }
        val longer = max(width, height)
        if (longer <= maxSide) return width to height
        val scale = maxSide.toDouble() / longer
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * The largest power-of-two `inSampleSize` that still decodes at least [target] px on the
     * longer side, so a 12-megapixel photo is never held in memory at full size.
     */
    fun sampleSize(width: Int, height: Int, target: Int = MAX_SIDE): Int {
        val longer = max(width, height)
        var sample = 1
        while (longer / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Width and height after an EXIF orientation: 5–8 turn the image by a quarter. */
    fun oriented(width: Int, height: Int, exifOrientation: Int): Pair<Int, Int> =
        if (exifOrientation in 5..8) height to width else width to height

    /**
     * The whole search: [encode] is asked for (width, height, quality) and returns the size in
     * bytes. The first combination at most [maxBytes] wins; [QUALITIES] are tried at each size,
     * then the size shrinks by [SHRINK]. Returns what to encode, or null if nothing fits even
     * at [minSide] (which a photo never reaches: 80 kB holds a 200 px image many times over).
     */
    fun <T> search(
        width: Int,
        height: Int,
        maxBytes: Int = MAX_BYTES,
        minSide: Int = 64,
        encode: (w: Int, h: Int, quality: Int) -> T,
        sizeOf: (T) -> Int,
    ): T? {
        var (w, h) = fit(width, height)
        while (max(w, h) >= minSide) {
            for (quality in QUALITIES) {
                val encoded = encode(w, h, quality)
                if (sizeOf(encoded) <= maxBytes) return encoded
            }
            w = (w * SHRINK).roundToInt().coerceAtLeast(1)
            h = (h * SHRINK).roundToInt().coerceAtLeast(1)
        }
        return null
    }
}
