package dev.gorny.buymyway

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import dev.gorny.buymyway.core.categorize.Categorizer
import dev.gorny.buymyway.core.push.PushSignal
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
import dev.gorny.buymyway.data.prefs.NotificationPreferences
import dev.gorny.buymyway.data.prefs.SyncMarks
import dev.gorny.buymyway.data.prefs.settingsDataStore
import dev.gorny.buymyway.data.push.AppsScriptPush
import dev.gorny.buymyway.data.push.CatchUpWorker
import dev.gorny.buymyway.data.push.Notifications
import dev.gorny.buymyway.data.push.PushSender
import dev.gorny.buymyway.data.push.PushTokens
import dev.gorny.buymyway.data.remote.FirebaseLiveSource
import dev.gorny.buymyway.data.remote.FirebaseRemoteLists
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.await
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
import kotlinx.coroutines.launch
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
        // Cheap, idempotent, and needed before a push can arrive in this process (Phase 9).
        container.notifications.ensureChannels()
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
            onSent = { byList -> pushSender.changed(byList) },
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
            onShared = { listId -> pushSender.shared(listId) },
        )
    }

    // --- Push, notifications and the background (Phase 9) ----------------------------------

    val notificationPrefs: NotificationPreferences by lazy {
        NotificationPreferences(appContext.settingsDataStore)
    }

    val notifications: Notifications by lazy { Notifications(appContext, notificationPrefs) }

    private val pushTokens: PushTokens by lazy {
        PushTokens(
            remote = remote,
            dataStore = appContext.settingsDataStore,
            // The registration token, not the installation id of the newer API (decision 100).
            currentToken = {
                @Suppress("DEPRECATION")
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            },
        )
    }

    /**
     * Asks the Apps Script to push (decision 93). Without a URL — every test build, and any
     * fork with no script of its own — it exists and does nothing.
     */
    private val pushSender: PushSender by lazy {
        PushSender(
            endpoint = BuildConfig.PUSH_URL.takeIf { it.isNotBlank() }?.let { AppsScriptPush(it) },
            remote = remote,
            members = { listId -> lists.membersOf(listId) },
            me = { account.syncUid()?.let { uid -> PushSender.Me(uid, account.profile()?.takeIf { it.uid == uid }?.name) } },
            idToken = { runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token }.getOrNull() },
            connection = connection,
            scope = appScope,
        )
    }

    /** Which list is on screen right now, so a push about it is silent (decision 95). */
    @Volatile
    private var listOnScreen: String? = null

    /** A push has arrived ([dev.gorny.buymyway.data.push.PushService]). True when it was shown. */
    suspend fun notifyOfPush(
        listId: String,
        kind: String,
        counts: PushSignal.Counts,
        actor: String?,
    ): Boolean {
        notifications.ensureChannels()
        return notifications.onMessage(
            listId = listId,
            listName = database.lists().get(listId)?.name,
            kind = kind,
            counts = counts,
            actor = actor,
            onScreen = listOnScreen == listId,
        )
    }

    /** [dev.gorny.buymyway.data.push.CatchUpWorker]'s work. True when nothing is left to retry. */
    suspend fun catchUpInBackground(listId: String?): Boolean {
        val uid = account.syncUid() ?: return true // signed out, or waiting to sign in again
        return try {
            // What already counted as bought before this read, so the pull can be told apart
            // from what the phone knew all along (decision 106).
            val boughtBefore = listId?.let { checkedIds(it) }
            connection.hold {
                pushTokens.ensureRegistered(uid)
                if (listId != null) sync.pull(uid, listId) else sync.catchUp(uid)
            }
            if (listId != null && boughtBefore != null) nameWhatWasBought(listId, boughtBefore, uid)
            true
        } catch (_: RemoteFailure) {
            false
        } catch (_: SyncEngine.SessionLost) {
            true
        }
    }

    /** The items of [listId] that count as bought, as Room has them at this moment. */
    private suspend fun checkedIds(listId: String): Set<String> = database.items()
        .getAllForList(listId)
        .filter { it.checked && it.deletedAt == null }
        .mapTo(mutableSetOf()) { it.id }

    /**
     * The push could only say „✓ 3"; the pull has since brought the names. Whatever somebody
     * else turned into „kupione" goes on the notification this list already has (decision 106).
     * This user's own ticks are left out: they are what this phone did.
     */
    private suspend fun nameWhatWasBought(listId: String, before: Set<String>, uid: String) {
        val names = database.items().getAllForList(listId)
            .filter { it.checked && it.deletedAt == null && it.id !in before && it.checkedBy != uid }
            .sortedBy { it.checkedAt ?: 0L }
            .map { it.name }
        notifications.addBought(
            listId = listId,
            listName = database.lists().get(listId)?.name,
            names = names,
            onScreen = listOnScreen == listId,
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

        /**
         * The list screen is in front of the user (Phase 9): a push about this list says
         * nothing, and whatever it was counting is forgotten, because the user is looking at it.
         */
        override fun onScreen(listId: String, open: Boolean) {
            listOnScreen = if (open) listId else listOnScreen.takeIf { it != listId }
            if (open) appScope.launch { notifications.clear(listId) }
        }
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
            afterSignIn = { uid ->
                pushTokens.ensureRegistered(uid)
                CatchUpWorker.schedulePeriodic(appContext)
            },
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
        // The push registration goes while the session that may write it still exists (Phase 9).
        account.syncUid()?.let { uid -> connection.hold { pushTokens.unregister(uid) } }
        account.endSession(appContext)
        OutboxWorker.cancel(appContext)
        PhotoWorker.cancel(appContext)
        CatchUpWorker.cancel(appContext)
        notifications.clearAll()
        sync.exclusive {
            lists.clearAll()
            photos.clear()
            appContext.settingsDataStore.edit { it.clear() }
        }
    }

    /**
     * „Usuń moje dane" (STATE.md decision 97, PLAN.md open question 8). Removes from RTDB every
     * list this user owns, their membership of everybody else's, and the account's own nodes;
     * then the ordinary [signOut] empties the phone. False when it could not be finished, and
     * then nothing was signed out either, so it can simply be tried again.
     */
    suspend fun deleteAllMyData(): Boolean {
        val uid = account.syncUid() ?: return false
        val ok = try {
            connection.hold {
                pushTokens.unregister(uid)
                val mine = database.lists().getAll().filter { database.listSync().get(it.id)?.synced == true }
                for (entity in mine.filter { it.ownerUid == uid }) {
                    val members = lists.membersOf(entity.id).map { it.uid }
                    remote.update(RemoteWrites.removeList(entity.id, uid, members)).await()
                }
                for (entity in mine.filter { it.ownerUid != uid }) {
                    // Their list stays theirs; this user simply stops being on it.
                    remote.update(RemoteWrites.removeMember(entity.id, uid)).await()
                }
                remote.update(RemoteWrites.forgetUser(uid, account.profile()?.email, mine.map { it.id })).await()
            }
            true
        } catch (_: Exception) {
            // RemoteFailure, RemoteDenied or a timeout: nothing local is touched, so the user
            // can try again once they have a network.
            false
        }
        if (ok) signOut()
        return ok
    }

    /** [OutboxWorker]'s work. True when nothing is left to retry. */
    suspend fun sendOutboxInBackground(): Boolean {
        val uid = account.syncUid() ?: return true // signed out, or waiting to sign in again
        return try {
            connection.hold {
                val left = sync.flush(uid)
                // The process may not live long enough for the 5 s debounce (decision 93).
                pushSender.flushNow()
                left
            } == 0
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
