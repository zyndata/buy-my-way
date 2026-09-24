package dev.gorny.buymyway.ui

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.data.update.AppUpdates
import dev.gorny.buymyway.data.update.ReleaseSource
import dev.gorny.buymyway.ui.lists.ListsScreen
import dev.gorny.buymyway.ui.lists.ListsViewModel
import dev.gorny.buymyway.ui.settings.AboutScreen
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import dev.gorny.buymyway.ui.update.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * „Dostępna wersja X — Pobierz" on the real screens (PLAN.md Phase 10, task 3), and the two
 * facts about the phone that only a device can tell us: what the merged manifest declares, and
 * that the provider handing an APK to the installer exists and lends nothing else.
 *
 * Nothing here reaches GitHub: the release document is handed over, exactly as `ReleaseSource`
 * would have fetched it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private lateinit var scope: CoroutineScope
    private val viewModels = ViewModelStore()

    private val newer = """
        {
          "tag_name": "v9.9.9",
          "assets": [{
            "name": "buy-my-way-v9.9.9.apk",
            "browser_download_url": "https://github.com/zyndata/buy-my-way/releases/download/v9.9.9/buy-my-way-v9.9.9.apk"
          }]
        }
    """.trimIndent()

    /** What the phone remembers between checks, in memory: a test never touches DataStore. */
    private class Memory(var dismissedTag: String? = null, var lastCheck: Long = 0L) : AppUpdates.Store {
        override suspend fun isCheckDue(now: Long, intervalMs: Long) = lastCheck > now || now - lastCheck >= intervalMs

        override suspend fun checked(now: Long) {
            lastCheck = now
        }

        override suspend fun dismissed(): String? = dismissedTag

        override suspend fun dismiss(tag: String) {
            dismissedTag = tag
        }
    }

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ListRepository(db = db, categoryOrder = { BuiltinCategories.IDS }, categorize = { "inne" })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun close() {
        viewModels.clear()
        scope.cancel()
        db.close()
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    /**
     * Listy with an update view model behind it. [installed] is what this build calls itself,
     * so a test can be the older or the newer side without rebuilding anything.
     */
    private fun showLists(
        document: String? = newer,
        installed: String = "1.0.0",
        store: Memory = Memory(),
    ): UpdateViewModel {
        val updates = UpdateViewModel(
            AppUpdates(ReleaseSource { document }, store, installed) { 1_800_000_000_000L },
        )
        viewModels.put("updates", updates)
        val prefs = ListOrderPreferences(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb") },
        )
        val lists = ListsViewModel(repo, prefs, scope)
        viewModels.put("lists", lists)
        compose.setContent {
            BuyMyWayTheme {
                ListsScreen(
                    vm = lists,
                    onOpenList = {},
                    onOpenSettings = {},
                    onOpenShare = {},
                    updates = updates,
                )
            }
        }
        return updates
    }

    @Test
    fun aNewerReleaseIsOfferedOnListy() {
        showLists()
        waitFor { exists(hasTestTag("update-banner")) }
        compose.onNodeWithText(text(R.string.update_available, "9.9.9")).assertExists()
        compose.onNodeWithText(text(R.string.action_download)).assertExists()
    }

    @Test
    fun nothingIsOfferedWhenThisIsAlreadyTheNewest() {
        showLists(installed = "9.9.9")
        // Nothing to wait for, so the check is given the time the other tests take to find one.
        Thread.sleep(2_000)
        assertFalse(exists(hasTestTag("update-banner")))
    }

    @Test
    fun aBuildFromAWorkingTreeIsOfferedNothing() {
        // `0.0.0-dev` is what a build with no tag behind it calls itself.
        showLists(installed = "0.0.0-dev")
        Thread.sleep(2_000)
        assertFalse(exists(hasTestTag("update-banner")))
    }

    @Test
    fun aPhoneWithNoNetworkSimplyShowsNoBanner() {
        showLists(document = null)
        Thread.sleep(2_000)
        assertFalse(exists(hasTestTag("update-banner")))
    }

    @Test
    fun hidingTheBannerRemembersThatVersion() {
        val store = Memory()
        showLists(store = store)
        waitFor { exists(hasTestTag("update-banner")) }

        compose.onNodeWithText(text(R.string.action_hide)).performClick()
        waitFor { !exists(hasTestTag("update-banner")) }
        waitFor { store.dismissedTag == "v9.9.9" }
        assertEquals("v9.9.9", store.dismissedTag)
    }

    @Test
    fun aVersionAlreadyHiddenIsNotOfferedAgain() {
        showLists(store = Memory(dismissedTag = "v9.9.9"))
        Thread.sleep(2_000)
        assertFalse(exists(hasTestTag("update-banner")))
    }

    @Test
    fun checkingFromOAplikacjiSaysWhenThereIsNothingNewer() {
        val updates = UpdateViewModel(
            AppUpdates(ReleaseSource { newer }, Memory(), "9.9.9") { 1_800_000_000_000L },
        )
        viewModels.put("updates", updates)
        compose.setContent { BuyMyWayTheme { AboutScreen(onBack = {}, updates = updates) } }

        compose.onNodeWithTag("check-updates").performClick()
        waitFor { exists(hasText(text(R.string.about_up_to_date))) }
    }

    /**
     * The permission that makes the whole feature possible, and the one thing about it worth
     * asserting on a device: it is declared, and it is **not** a permission Android ever asks a
     * person about in a dialog (STATE.md decision 110).
     */
    @Test
    fun requestInstallPackagesIsDeclaredAndIsNeverARuntimeDialog() {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val declared = info.requestedPermissions.orEmpty().toSet()
        assertTrue(declared.contains("android.permission.REQUEST_INSTALL_PACKAGES"))

        val permission = context.packageManager.getPermissionInfo("android.permission.REQUEST_INSTALL_PACKAGES", 0)
        @Suppress("DEPRECATION")
        val protectionLevel = permission.protectionLevel
        @Suppress("DEPRECATION")
        val protection = protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        assertFalse(
            "REQUEST_INSTALL_PACKAGES would be a runtime dialog, which decision 110 says it is not",
            protection == PermissionInfo.PROTECTION_DANGEROUS,
        )
        // How Android actually gates it: an app op, which only the user can turn on, on the
        // Settings screen `ApkDownloads.unknownSourcesSettings()` opens.
        assertTrue(
            "it should be gated by an app op, not granted at install",
            protectionLevel and PermissionInfo.PROTECTION_FLAG_APPOP != 0,
        )
        // And until they do, the app cannot install anything: declaring it grants nothing.
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.REQUEST_INSTALL_PACKAGES"),
        )
    }

    /** The provider that lends the installer one directory, and the installer's own intent. */
    @Test
    fun theUpdateProviderExistsAndLendsOnlyItsOwnDirectory() {
        val authority = "${context.packageName}.updates"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull("the update FileProvider is not in the merged manifest", provider)
        assertEquals("dev.gorny.buymyway.data.update.UpdateFileProvider", provider?.name)
        assertFalse("it must not be exported", provider?.exported ?: true)
        assertTrue("it must grant URI permissions", provider?.grantUriPermissions ?: false)

        // A file outside `Download/` is not something this provider will lend out.
        val outside = File(context.getExternalFilesDir(null), "not-an-update.apk")
        val refused = runCatching {
            FileProvider.getUriForFile(context, authority, outside)
        }.isFailure
        assertTrue("the provider lent a file outside its one directory", refused)
    }
}
