package dev.gorny.buymyway.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import dev.gorny.buymyway.core.photo.PhotoSizing
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A photo ready to store: WebP bytes with no EXIF, and its size. */
class ProcessedPhoto(val webp: ByteArray, val width: Int, val height: Int)

/**
 * A camera or gallery image turned into what `/photos` stores (PLAN.md Phase 6, task 1;
 * STATE.md decision 71): decoded at a power-of-two sample size, scaled to at most 800 px, turned
 * by its EXIF orientation, and encoded as WebP at the highest quality that fits in 80 kB. The
 * bitmap is encoded afresh, so none of the original's EXIF (location, camera, time) survives.
 *
 * [open] is called several times, once per pass over the image; each call returns a new stream.
 */
object PhotoProcessor {
    fun process(open: () -> InputStream): ProcessedPhoto {
        val orientation = try {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL // no EXIF to read: a PNG, say
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")

        val options = BitmapFactory.Options().apply { inSampleSize = PhotoSizing.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("the image did not decode")
        val upright = orient(decoded, orientation)
        if (upright !== decoded) decoded.recycle()

        var scaled: Bitmap? = null
        try {
            return PhotoSizing.search(
                upright.width,
                upright.height,
                encode = { w, h, quality ->
                    val current = scaled?.takeIf { it.width == w && it.height == h }
                        ?: scaleTo(upright, w, h).also { next ->
                            scaled?.takeIf { it !== upright }?.recycle()
                            scaled = next
                        }
                    ProcessedPhoto(encode(current, quality), w, h)
                },
                sizeOf = { it.webp.size },
            ) ?: throw IOException("the image does not fit in ${PhotoSizing.MAX_BYTES} bytes")
        } finally {
            scaled?.takeIf { it !== upright }?.recycle()
            upright.recycle()
        }
    }

    private fun scaleTo(source: Bitmap, width: Int, height: Int): Bitmap =
        if (source.width == width && source.height == height) source else source.scale(width, height)

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream(PhotoSizing.MAX_BYTES)
        check(bitmap.compress(format, quality, out)) { "WebP encoding failed" }
        return out.toByteArray()
    }

    /** The image as it should be seen, whatever way the camera was held (EXIF 1–8). */
    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
