package dev.gorny.buymyway.data.photo

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.photo.PhotoSizing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * A photo as the camera makes it becomes what `/photos` stores (PLAN.md Phase 6, task 1 and the
 * acceptance criteria; STATE.md decision 71): 12 megapixels in, at most 800 px and 80 kB out,
 * the right way up, and no EXIF (no location) left in it. Also: the app declares no camera,
 * storage or media permission.
 */
@RunWith(AndroidJUnit4::class)
class PhotoProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "processor-test").apply { mkdirs() }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun aTwelveMegapixelPhotoBecomesAtMostEightyKilobytesAndEightHundredPixels() {
        val jpeg = cameraJpeg(4000, 3000, orientation = ExifInterface.ORIENTATION_NORMAL)
        val photo = PhotoProcessor.process { jpeg.inputStream() }
        assertTrue("${photo.webp.size} bytes", photo.webp.size <= PhotoSizing.MAX_BYTES)
        assertEquals(800 to 600, photo.width to photo.height)
        val decoded = BitmapFactory.decodeByteArray(photo.webp, 0, photo.webp.size)
        assertEquals(800 to 600, decoded.width to decoded.height)
        assertTrue(isWebp(photo.webp))
    }

    @Test
    fun theExifOrientationIsAppliedAndNoExifIsLeft() {
        // Held upright, the camera stores the image sideways and says "turn 90° clockwise".
        val jpeg = cameraJpeg(4000, 3000, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val photo = PhotoProcessor.process { jpeg.inputStream() }
        assertEquals(600 to 800, photo.width to photo.height)
        val decoded = BitmapFactory.decodeByteArray(photo.webp, 0, photo.webp.size)
        // The red corner was top left in the stored image; turned 90° clockwise it is top right.
        assertTrue(isRed(decoded.getPixel(decoded.width - 20, 20)))
        assertFalse(isRed(decoded.getPixel(20, 20)))
        // WebP keeps EXIF in its own chunk; there is none, and nothing of the GPS tag either.
        assertFalse(containsAscii(photo.webp, "EXIF"))
        assertFalse(containsAscii(photo.webp, "Exif"))
        assertFalse(containsAscii(photo.webp, "TestCam"))
        assertFalse(containsAscii(photo.webp, "GPS"))
    }

    @Test
    fun evenPureNoiseFits() {
        // The worst case for a compressor: every pixel random. Quality alone is not enough, so
        // the image shrinks until it fits.
        val jpeg = jpeg(noise(4000, 3000))
        val photo = PhotoProcessor.process { jpeg.inputStream() }
        assertTrue("${photo.webp.size} bytes", photo.webp.size <= PhotoSizing.MAX_BYTES)
        assertTrue(maxOf(photo.width, photo.height) <= 800)
    }

    @Test
    fun aSmallPngWithoutExifIsKeptAtItsSize() {
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val photo = PhotoProcessor.process { png.inputStream() }
        assertEquals(320 to 200, photo.width to photo.height)
    }

    @Test
    fun somethingThatIsNotAnImageIsRefused() {
        try {
            PhotoProcessor.process { "lista zakupów".toByteArray().inputStream() }
            fail("text is not a photo")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun noCameraStorageOrMediaPermissionIsDeclared() {
        val packageName = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        val declared = info.requestedPermissions.orEmpty().toSet()
        val forbidden = declared.filter { permission ->
            permission.endsWith("_EXTERNAL_STORAGE") || permission.contains("READ_MEDIA_") || permission == "android.permission.CAMERA"
        }
        assertEquals(emptyList<String>(), forbidden)
    }

    // --- Images ---------------------------------------------------------------------------

    /**
     * A photo-like JPEG as a camera writes it: gradients and shapes with a little sensor noise,
     * a red block in the top-left corner, and EXIF with a camera name, GPS and [orientation].
     */
    private fun cameraJpeg(width: Int, height: Int, orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(40, 90, 160), Color.rgb(230, 210, 150), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val random = Random(7)
        repeat(60) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextInt(width).toFloat(), random.nextInt(height).toFloat(), 40f + random.nextInt(400), paint)
        }
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, width * 0.2f, height * 0.2f, paint)
        // Sensor noise, a row at a time so the test stays light on memory.
        val row = IntArray(width)
        for (y in 0 until height step 3) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                val n = random.nextInt(-12, 13)
                val c = row[x]
                row[x] = Color.rgb((Color.red(c) + n).coerceIn(0, 255), (Color.green(c) + n).coerceIn(0, 255), (Color.blue(c) + n).coerceIn(0, 255))
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val file = File(dir, "camera.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,13/1,4/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "21/1,0/1,42/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        val bytes = file.readBytes()
        assertTrue("the input carries GPS", ExifInterface(file.absolutePath).latLong != null)
        return bytes
    }

    private fun noise(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(11)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in row.indices) row[x] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun isWebp(bytes: ByteArray) = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun isRed(pixel: Int) = Color.red(pixel) > 180 && Color.green(pixel) < 80 && Color.blue(pixel) < 80

    private fun containsAscii(bytes: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        return (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
    }
}
