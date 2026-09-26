package dev.gorny.buymyway.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.update.Updates
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

/**
 * The step between „the bytes are here" and „the installer can read them" (PLAN.md Phase 10,
 * task 3). It needs no network: `DownloadManager` does its part in another process and is not
 * what breaks — the hand-over is.
 *
 * It exists because that hand-over shipped broken in v0.9.1 (STATE.md decision 119): the
 * provider carried its paths only in `FileProvider(R.xml.update_paths)`, which the *static*
 * `getUriForFile` never reads, so every finished download was reported to the user as
 * „Nie udało się pobrać aktualizacji". Nothing else in the app noticed, because nothing else
 * calls it.
 */
@RunWith(AndroidJUnit4::class)
class ApkDownloadsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val downloads = ApkDownloads(context)
    private val release = Updates.Release(
        version = Updates.Version(9, 9, 9),
        tag = "v9.9.9",
        apkUrl = "https://github.com/zyndata/buy-my-way/releases/download/v9.9.9/buy-my-way-v9.9.9.apk",
        apkName = "buy-my-way-v9.9.9.apk",
    )
    private val outside = File(context.cacheDir, "not-an-update.apk")

    private val older = downloads.destination(release.copy(version = Updates.Version(1, 0, 0)))
    private val unfinished = File(older.parentFile, ".pending-1-buy-my-way-v1.0.0.apk")

    @After
    fun clean() {
        downloads.destination(release).delete()
        outside.delete()
        older.delete()
        unfinished.delete()
    }

    @Test
    fun theApkThisVersionWasInstalledFromIsDeletedAndANewerOneIsKept() {
        older.parentFile?.mkdirs()
        older.writeBytes(ByteArray(8))
        unfinished.writeBytes(ByteArray(8))
        val newer = downloads.destination(release)
        newer.writeBytes(ByteArray(8))

        assertEquals(1, downloads.removeStale(installed = "1.0.0"))
        assertFalse(older.exists())
        assertTrue("a transfer in progress is not ours to delete", unfinished.exists())
        assertTrue("an APK not installed yet stays", newer.exists())
    }

    @Test
    fun aDownloadedApkBecomesAContentUriTheInstallerCanRead() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val apk = downloads.destination(release)
        apk.parentFile?.mkdirs()
        apk.writeBytes(bytes)

        val uri = downloads.shareable(apk)
        assertNotNull("the provider would not lend out ${apk.absolutePath}", uri)
        assertEquals("${context.packageName}.updates", uri!!.authority)

        // What the package installer does with the intent: open it and read the APK.
        val read = context.contentResolver.openInputStream(uri).use { it!!.readBytes() }
        assertArrayEquals(bytes, read)
    }

    @Test
    fun theProviderLendsOutNothingButThatOneDirectory() {
        outside.writeBytes(ByteArray(8))
        assertNull(downloads.shareable(outside))
    }

    @Test
    fun theDestinationIsNamedAfterTheVersionAndNotAfterTheDocument() {
        val hostile = release.copy(apkName = "../../../data/data/dev.gorny.buymyway/evil.apk")
        assertEquals("buy-my-way-v9.9.9.apk", downloads.destination(hostile).name)
    }
}
