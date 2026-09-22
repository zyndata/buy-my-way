package dev.gorny.buymyway.data.sync

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.gorny.buymyway.data.auth.AccountRepository
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.remote.RemoteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * When sync runs while the app is in use (PLAN.md *Battery policy*, STATE.md decision 58).
 * Observes the whole process's lifecycle: while the app is in the foreground and signed in,
 * it holds the [Connection], catches every list up (at most once per [CATCH_UP_EVERY_MS]),
 * and sends the outbox whenever something new lands in it. [GRACE_MS] after the app leaves
 * the foreground it lets go, and the connection closes; changes still unsent by then are left
 * to one [OutboxWorker], enqueued the moment the app leaves.
 */
@OptIn(FlowPreview::class)
class SyncController(
    private val scope: CoroutineScope,
    private val account: AccountRepository,
    private val engine: SyncEngine,
    private val connection: Connection,
    private val pendingOps: Flow<Int>,
    private val scheduleOutbox: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {

    enum class Status { IDLE, SYNCING, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var foreground: Job? = null
    private var leaving: Job? = null
    private val catchUps = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastCatchUp = 0L

    @Volatile
    private var profileSentFor: String? = null

    override fun onStart(owner: LifecycleOwner) {
        leaving?.cancel()
        leaving = null
        if (clock() - lastCatchUp >= CATCH_UP_EVERY_MS) catchUps.trySend(Unit)
        if (foreground?.isActive != true) foreground = scope.launch { whileInForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            if (pendingOps.first() > 0 && account.syncUid() != null) scheduleOutbox()
        }
        leaving = scope.launch {
            delay(GRACE_MS)
            foreground?.cancel()
            foreground = null
        }
    }

    /** Pull-to-refresh: send, then read every list. False when it could not reach RTDB. */
    suspend fun refresh(): Boolean {
        val uid = account.syncUid() ?: return false
        return connection.hold { sync(uid, catchUp = true) }
    }

    private suspend fun whileInForeground() {
        account.state.map { (it as? AccountState.SignedIn)?.uid }.distinctUntilChanged().collectLatest { uid ->
            if (uid == null) return@collectLatest
            connection.hold {
                if (!account.checkSession()) return@hold
                coroutineScope {
                    launch { catchUps.receiveAsFlow().collect { sync(uid, catchUp = true) } }
                    launch {
                        pendingOps.debounce(SEND_DEBOUNCE_MS).filter { it > 0 }.collectLatest {
                            // Unacknowledged (offline, most likely): try again while the app is open.
                            while (!sync(uid, catchUp = false)) delay(RETRY_MS)
                        }
                    }
                    catchUps.trySend(Unit)
                }
            }
        }
    }

    private suspend fun sync(uid: String, catchUp: Boolean): Boolean {
        _status.value = Status.SYNCING
        return try {
            if (profileSentFor != uid) {
                account.profile()?.takeIf { it.uid == uid }?.let { engine.writeProfile(it.uid, it.name, it.email, it.photoUrl) }
                profileSentFor = uid
            }
            engine.flush(uid)
            if (catchUp) {
                engine.catchUp(uid)
                lastCatchUp = clock()
            }
            _status.value = Status.IDLE
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // RemoteFailure: no answer. SessionLost: AccountRepository has signed the session
            // out and its state says so. Anything else is a bug, but never a crash of the app.
            // Only the exception's class is logged: messages can hold paths with ids.
            if (e !is RemoteFailure) Log.w(TAG, "sync failed: ${e.javaClass.simpleName}")
            _status.value = Status.FAILED
            false
        }
    }

    companion object {
        /** How long the connection stays open after the app leaves the foreground. */
        const val GRACE_MS = 30_000L
        const val CATCH_UP_EVERY_MS = 30_000L
        private const val SEND_DEBOUNCE_MS = 300L
        private const val RETRY_MS = 30_000L
        private const val TAG = "BuyMyWaySync"
    }
}
