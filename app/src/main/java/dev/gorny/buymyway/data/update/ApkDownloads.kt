package dev.gorny.buymyway.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.gorny.buymyway.BuildConfig
import dev.gorny.buymyway.core.update.Updates
import kotlinx.coroutines.delay
import java.io.File

/**
 * Fetching a release APK and handing it to the package installer (PLAN.md Phase 10, task 3).
 *
 * `DownloadManager` does the transfer, so the app needs no storage permission and no download
 * code of its own: the file lands in this app's own external files directory, which nothing
 * else on the phone can read. What the app does need is `REQUEST_INSTALL_PACKAGES` and the
 * user's permission to install unknown apps — a Settings screen, never a runtime dialog
 * (STATE.md decision 110).
 */
class ApkDownloads(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

    /** Where a download got to. [Done] carries the file the installer is to be pointed at. */
    sealed interface Progress {
        data object Running : Progress

        data class Done(val apk: Uri) : Progress

        data object Failed : Progress
    }

    /** Starts the transfer and returns its id, or null when this phone has no DownloadManager. */
    fun enqueue(release: Updates.Release): Long? {
        val manager = manager ?: return null
        // The name is ours, not the server's: a release asset called `../something` is then
        // simply a file called that in our own directory.
        val name = safeName(release)
        destination(release).delete()
        val request = DownloadManager.Request(release.apkUrl.toUri())
            .setTitle(name)
            .setMimeType(APK_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, name)
        return runCatching { manager.enqueue(request) }.getOrNull()
    }

    /**
     * Watches [id] until it finishes. Polling, not a broadcast receiver, because this only
     * runs while the user is watching the banner it belongs to — the screen goes, the
     * coroutine goes, and `DownloadManager`'s own notification carries on without us.
     */
    suspend fun awaitFinish(id: Long): Progress {
        val manager = manager ?: return Progress.Failed
        while (true) {
            val status = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return Progress.Failed
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return finished(id)
                DownloadManager.STATUS_FAILED -> return Progress.Failed
                else -> delay(POLL_MS)
            }
        }
    }

    private fun finished(id: Long): Progress {
        val manager = manager ?: return Progress.Failed
        val local = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return Progress.Failed
            cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        } ?: return Progress.Failed
        val file = local.toUri().path?.let(::File) ?: return Progress.Failed
        return shareable(file)?.let(Progress::Done) ?: Progress.Failed
    }

    /**
     * The content URI the package installer is handed for [file], or null when this app may not
     * lend it out. The installer is another app, so it gets a content URI it may read for one
     * call — never a file path, which Android has refused to pass between apps since API 24.
     *
     * Null is the whole of the „Nie udało się pobrać aktualizacji" path once the bytes are
     * here, so it is what the test drives (STATE.md decision 119).
     */
    internal fun shareable(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
    }.getOrNull()

    /** Where [enqueue] puts the APK, and therefore the only file [shareable] is ever asked for. */
    internal fun destination(release: Updates.Release): File =
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), safeName(release))

    /**
     * Deletes the APKs [installed] has made redundant and returns how many went. Nothing tells
     * the app that the installer finished — the process is replaced — so the first start of the
     * new version is where the file it came from is cleared up; without this every update left
     * its APK behind in `Android/data`, where only „Wyczyść dane" or an uninstall reached it.
     */
    fun removeStale(installed: String = BuildConfig.VERSION_NAME): Int {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return 0
        return directory.listFiles().orEmpty()
            .filter { it.isFile && Updates.isStaleApk(it.name, installed) }
            .count { it.delete() }
    }

    /** Whether Android will let this app install an APK at all. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /** The Settings screen where the user allows it, when [canInstall] says they have not. */
    fun unknownSourcesSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri())

    /** Hands [apk] to the package installer; the user does the rest. */
    fun installIntent(apk: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apk, APK_TYPE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val POLL_MS = 400L

        fun safeName(release: Updates.Release) = Updates.apkName(release.version)
    }
}
