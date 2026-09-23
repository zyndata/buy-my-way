package dev.gorny.buymyway

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import dev.gorny.buymyway.core.categorize.Categorizer
import dev.gorny.buymyway.core.categorize.NameIndex
import dev.gorny.buymyway.core.voice.Dictation
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.auth.AccountRepository
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.auth.SignInResult
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.photo.PhotoCache
import dev.gorny.buymyway.data.photo.PhotoLoader
import dev.gorny.buymyway.data.photo.PhotoOutbox
import dev.gorny.buymyway.data.photo.PhotoWorker
import dev.gorny.buymyway.data.photo.Photos
import dev.gorny.buymyway.data.prefs.AccountRecord
import dev.gorny.buymyway.data.prefs.CategoryOrderPreferences
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.data.prefs.ListSortPreferences
import dev.gorny.buymyway.data.prefs.SyncMarks
import dev.gorny.buymyway.data.prefs.settingsDataStore
import dev.gorny.buymyway.data.remote.FirebaseLiveSource
import dev.gorny.buymyway.data.remote.FirebaseRemoteLists
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.share.Sharing
import dev.gorny.buymyway.data.sync.Connection
import dev.gorny.buymyway.data.sync.LiveLists
import dev.gorny.buymyway.data.sync.LostLists
import dev.gorny.buymyway.data.sync.OutboxWorker
import dev.gorny.buymyway.data.sync.PrefsSync
import dev.gorny.buymyway.data.sync.SyncController
import dev.gorny.buymyway.data.sync.SyncEngine
import dev.gorny.buymyway.ui.list.ListLive
import dev.gorny.buymyway.ui.lists.ListsSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom

class BuyMyWayApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.syncController)
    }
}

/** The app's single instances, created on first use. No DI framework: there are a handful. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.open(appContext) }

    val categoryOrder: CategoryOrderPreferences by lazy { CategoryOrderPreferences(appContext.settingsDataStore) }

    val listOrder: ListOrderPreferences by lazy { ListOrderPreferences(appContext.settingsDataStore) }

    val listSort: ListSortPreferences by lazy { ListSortPreferences(appContext.settingsDataStore) }

    /**
     * Work that must finish even when the screen that started it is gone: a delete committed
     * as its „Cofnij" snackbar closes (STATE.md decision 51), sync. Lives as long as the process.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val categorizerLock = Mutex()
    private var categorizer: Categorizer? = null

    /** The dictionary is ~20 kB of JSON, read from assets once, off the main thread. */
    suspend fun categorizer(): Categorizer = categorizerLock.withLock {
        categorizer ?: withContext(Dispatchers.IO) {
            appContext.assets.open(PRODUCTS_ASSET).bufferedReader().use { Categorizer.fromJson(it.readText()) }
        }.also { categorizer = it }
    }

    /** Dictionary names for the add bar's autocomplete. */
    suspend fun suggestNames(typed: String, limit: Int): List<String> = categorizer().suggest(typed, limit)

    /**
     * Where one dictated thing ends and the next begins, when nothing was said between them:
     * the bundled dictionary, the names this phone has already seen (decision 80), and the
     * words the user curated in „Moje produkty" (Phase 8b). The last is the only one of the
     * three the user can edit, and it is what makes „chleb wiejski" a thing of its own.
     */
    suspend fun knownNames(): Dictation.KnownNames {
        val categorizer = categorizer()
        val seen = NameIndex.ofFolded(database.nameHistory().keys(OWN_NAMES))
        val curated = NameIndex.ofFolded(lists.ownProductKeys())
        return Dictation.KnownNames { words, from ->
            maxOf(
                categorizer.knownNameLength(words, from),
                maxOf(seen.lengthAt(words, from), curated.lengthAt(words, from)),
            )
        }
    }

    // --- Account and sync (Phase 4) --------------------------------------------------------

    val account: AccountRepository by lazy {
        AccountRepository(
            auth = FirebaseAuth.getInstance(),
            record = AccountRecord(appContext.settingsDataStore),
            // Generated by the google-services plugin from google-services.json (decision 53).
            serverClientId = appContext.getString(R.string.default_web_client_id),
            scope = appScope,
        )
    }

    val lists: ListRepository by lazy {
        ListRepository(
            db = database,
            categoryOrder = categoryOrder,
            categorize = { name -> categorizer().categorize(name) },
            actor = { account.actorUid() },
        )
    }

    /** Offline from the moment it exists: only a [Connection] holder opens it (decision 58). */
    private val firebaseDatabase: FirebaseDatabase by lazy { FirebaseDatabase.getInstance().also { it.goOffline() } }

    private val connection: Connection by lazy {
        Connection { online -> if (online) firebaseDatabase.goOnline() else firebaseDatabase.goOffline() }
    }

    private val remote: FirebaseRemoteLists by lazy { FirebaseRemoteLists(firebaseDatabase) }

    /** Shared lists taken away from this user, until the home screen has said so (Phase 5). */
    private val lostLists = LostLists()

    private val sync: SyncEngine by lazy {
        SyncEngine(
            db = database,
            repo = lists,
            remote = remote,
            prefs = PrefsSync(
                database,
                remote,
                categoryOrder.stored,
                listOrder.stored,
                SyncMarks(appContext.settingsDataStore),
                listSort,
            ),
            sessionValid = { account.checkSession() },
            onListLost = lostLists::add,
        )
    }

    /** The listeners of the screens on show (Phase 5, decision 65). */
    private val live: LiveLists by lazy {
        LiveLists(lists, sync, FirebaseLiveSource(firebaseDatabase), connection) { account.syncUid() }
    }

    /** Invites, members and roles (Phase 5, decision 64). */
    val sharing: Sharing by lazy {
        val random = SecureRandom()
        Sharing(
            remote = remote,
            engine = sync,
            repo = lists,
            connection = connection,
            me = { account.syncUid()?.let { uid -> Sharing.Me(uid, account.profile()?.takeIf { it.uid == uid }?.name) } },
            random = { bytes -> random.nextBytes(bytes) },
        )
    }

    /** Items' photos (Phase 6, decisions 70–72). */
    val photos: Photos by lazy {
        val outbox = PhotoOutbox(File(appContext.filesDir, "photo-outbox"))
        val cache = PhotoCache(File(appContext.cacheDir, "photos"))
        Photos(
            repo = lists,
            remote = remote,
            outbox = outbox,
            cache = cache,
            loader = PhotoLoader(cache, outbox, ::fetchPhoto),
            schedule = { PhotoWorker.enqueue(appContext) },
            sessionValid = { account.checkSession() },
        )
    }

    /** One photo node, read for a row on screen; null signed out, offline or when it is not there. */
    private suspend fun fetchPhoto(listId: String, itemId: String) = account.syncUid()?.let {
        try {
            connection.hold {
                withTimeoutOrNull(SyncEngine.DEFAULT_TIMEOUT_MS) { Photos.decode(remote.read(RemoteWrites.photo(listId, itemId))) }
            }
        } catch (_: RemoteDenied) {
            null
        }
    }

    /** What the list screen needs besides the repository. */
    val listLive: ListLive = object : ListLive {
        override suspend fun myUid(): String? = account.actorUid()

        override fun watch(listId: String) = live.watch(listId)

        override fun sortView(listId: String) = listSort.view(listId)

        override suspend fun setSortView(listId: String, view: SortView) = listSort.set(listId, view)
    }

    val syncController: SyncController by lazy {
        SyncController(
            scope = appScope,
            account = account,
            engine = sync,
            connection = connection,
            pendingOps = lists.observePendingOps(),
            scheduleOutbox = { OutboxWorker.enqueue(appContext) },
            // A list just uploaded may have photos waiting for it (decision 71).
            afterFlush = { if (photos.ready()) PhotoWorker.enqueue(appContext) },
        )
    }

    /** What the home screen needs: who is signed in, pull-to-refresh, presence, leaving. */
    val listsSync: ListsSync = object : ListsSync {
        override val account: Flow<AccountState> get() = this@AppContainer.account.state

        override suspend fun refresh(): Boolean = syncController.refresh()

        override fun presence(listIds: Set<String>) = live.presence(listIds)

        override fun userLists() = live.userLists()

        override fun requestCatchUp() = syncController.requestCatchUp()

        override val lostLists: Flow<List<String>> get() = this@AppContainer.lostLists.pending

        override fun lostShown(name: String) = this@AppContainer.lostLists.shown(name)

        override suspend fun leave(listId: String) = sharing.leave(listId)
    }

    val pendingOps: Flow<Int> get() = lists.observePendingOps()

    /** Sign-in from Ustawienia; sync starts by itself once the account state says so. */
    suspend fun signIn(activity: Context): SignInResult = account.signIn(activity)

    /**
     * Sign-out (PLAN.md *Google identity*): the Firebase session first, so nothing syncs any
     * more; then Room and DataStore, the account record with it. The lists stay in RTDB.
     */
    suspend fun signOut() {
        account.endSession(appContext)
        OutboxWorker.cancel(appContext)
        PhotoWorker.cancel(appContext)
        sync.exclusive {
            lists.clearAll()
            photos.clear()
            appContext.settingsDataStore.edit { it.clear() }
        }
    }

    /** [OutboxWorker]'s work. True when nothing is left to retry. */
    suspend fun sendOutboxInBackground(): Boolean {
        val uid = account.syncUid() ?: return true // signed out, or waiting to sign in again
        return try {
            connection.hold { sync.flush(uid) } == 0
        } catch (_: RemoteFailure) {
            false // no answer: WorkManager tries again later
        } catch (_: SyncEngine.SessionLost) {
            true // waits for the next sign-in
        }
    }

    /**
     * [PhotoWorker]'s work: the photos, then the `photoAt` changes they lead to. True when
     * nothing is left to retry.
     */
    suspend fun sendPhotosInBackground(): Boolean {
        val uid = account.syncUid() ?: return true // waits for the next sign-in
        return try {
            connection.hold {
                photos.send(uid)
                sync.flush(uid)
            } == 0
        } catch (_: RemoteFailure) {
            false
        } catch (_: SyncEngine.SessionLost) {
            true
        }
    }

    private companion object {
        const val PRODUCTS_ASSET = "products-pl.json"

        /** How many of this phone's own names dictation may cut at; the rest are rarer. */
        const val OWN_NAMES = 500
    }
}
