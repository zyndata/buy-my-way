package dev.gorny.buymyway.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.model.ListSummary
import dev.gorny.buymyway.core.model.Ordering
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.prefs.ListOrderPreferences
import dev.gorny.buymyway.ui.common.HeldDeletes
import dev.gorny.buymyway.ui.list.ListViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Null [lists] until Room has answered, so the empty state is not flashed on every start.
 * [roles] is this user's role per list; [watching] the names of the others looking at each
 * shared list now (PLAN.md Phase 5, task 3).
 */
data class ListsUiState(
    val lists: List<ListSummary>?,
    val roles: Map<String, Role> = emptyMap(),
    val watching: Map<String, List<String>> = emptyMap(),
)

/** What the home screen needs from sign-in and sync (Phases 4 and 5); absent where it is tested alone. */
interface ListsSync {
    val account: Flow<AccountState>

    /** Pull-to-refresh. False when RTDB could not be reached. */
    suspend fun refresh(): Boolean

    /** Who is looking at each of [listIds] (decision 65). */
    fun presence(listIds: Set<String>): Flow<Map<String, Set<String>>> = flowOf(emptyMap())

    /** `/userLists/{uid}` while the home screen is shown. */
    fun userLists(): Flow<Set<String>> = emptyFlow()

    /** A catch-up now: a list was shared with this user, or taken away. */
    fun requestCatchUp() = Unit

    /** Names of shared lists taken away from this user, not yet said. */
    val lostLists: Flow<List<String>> get() = flowOf(emptyList())

    fun lostShown(name: String) = Unit

    /** „Opuść listę". Throws a `Sharing.SharingFailure` when it could not be done. */
    suspend fun leave(listId: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModel(
    private val repo: ListRepository,
    private val order: ListOrderPreferences,
    commitScope: CoroutineScope,
    private val sync: ListsSync? = null,
) : ViewModel() {

    val held = HeldDeletes(commitScope) { repo.deleteList(it) }

    /** The order while a card is being dragged; saved to DataStore when it is dropped. */
    private val dragOrder = MutableStateFlow<List<String>?>(null)

    val account: StateFlow<AccountState> = (sync?.account ?: flowOf(AccountState.SignedOut))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountState.Loading)

    private val myUid: Flow<String?> = account.map {
        when (it) {
            is AccountState.SignedIn -> it.uid
            is AccountState.SessionLost -> it.uid
            else -> null
        }
    }.distinctUntilChanged()

    private val lists: Flow<List<ListSummary>> = combine(repo.observeLists(), order.order, held.hidden, dragOrder) { lists, saved, hidden, dragging ->
        Ordering.byIds(lists.filterNot { it.list.id in hidden }, dragging ?: saved) { it.list.id }
    }

    private val members = repo.observeAllMembers()

    private val roles: Flow<Map<String, Role>> = combine(lists, members, myUid) { lists, members, me ->
        val mine = members.filter { it.uid == me }.associate { it.listId to it.role }
        lists.associate { summary ->
            val list = summary.list
            list.id to if (list.ownerUid == null || list.ownerUid == me) Role.OWNER else mine[list.id] ?: Role.VIEWER
        }
    }

    /** Presence of the shared lists, as names; also keeps `/userLists` watched while shown. */
    private val watching: Flow<Map<String, List<String>>> = merge(
        combine(
            lists.map { all -> all.filter { it.list.shared }.map { it.list.id }.toSet() }.distinctUntilChanged()
                .flatMapLatest { ids -> sync?.presence(ids) ?: flowOf(emptyMap()) },
            members,
            myUid,
        ) { present, members, me ->
            val names = members.groupBy { it.listId }
            present.mapValues { (listId, uids) ->
                val known = names[listId].orEmpty().associateBy { it.uid }
                uids.filter { it != me }.map { ListViewModel.displayName(known[it]) }.sorted()
            }.filterValues { it.isNotEmpty() }
        },
        (sync?.userLists() ?: emptyFlow()).transform { remoteIds ->
            // A list shared a moment ago, or one taken away: catch up at once (decision 65).
            val local = repo.observeLists().first().map { it.list.id }.toSet()
            if (remoteIds.any { it !in local }) sync?.requestCatchUp()
        },
    )

    // Presence may never answer (signed out, offline): the cards do not wait for it.
    val state: StateFlow<ListsUiState> = combine(lists, roles, watching.onStart { emit(emptyMap()) }) { lists, roles, watching ->
        ListsUiState(lists, roles, watching)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListsUiState(null))

    val lostLists: Flow<List<String>> = sync?.lostLists ?: flowOf(emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun lostShown(name: String) {
        sync?.lostShown(name)
    }

    /** Pull-to-refresh; [onFailed] runs when RTDB could not be reached. */
    fun refresh(onFailed: () -> Unit) {
        val sync = sync ?: return
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                if (!sync.refresh()) onFailed()
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Creates the list and hands its id to [then] (the screen opens it). */
    fun create(name: String, then: (String) -> Unit) {
        viewModelScope.launch { then(repo.createList(name)) }
    }

    fun rename(listId: String, name: String) {
        viewModelScope.launch { runCatching { repo.renameList(listId, name) } }
    }

    fun delete(listId: String, message: String, undoLabel: String) = held.hold(viewModelScope, listId, message, undoLabel)

    /** „Opuść listę"; [onFailed] gets the failure to say. */
    fun leave(listId: String, onFailed: (Exception) -> Unit) {
        val sync = sync ?: return
        viewModelScope.launch {
            try {
                sync.leave(listId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed(e)
            }
        }
    }

    fun move(fromId: String, toId: String) {
        val ids = current()
        dragOrder.value = Ordering.move(ids, ids.indexOf(fromId), ids.indexOf(toId))
    }

    /** TalkBack's „Przesuń wyżej / niżej": a move and a drop in one. */
    fun moveBy(listId: String, delta: Int) {
        val ids = current()
        val from = ids.indexOf(listId)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        dragOrder.value = Ordering.move(ids, from, to)
        drop()
    }

    fun drop() {
        val ids = dragOrder.value ?: return
        viewModelScope.launch {
            order.setOrder(ids)
            // Keep showing the new order until DataStore has it, so nothing jumps back.
            withTimeoutOrNull(SETTLE_MS) { order.order.first { it == ids } }
            dragOrder.compareAndSet(ids, null)
        }
    }

    private fun current(): List<String> = dragOrder.value ?: state.value.lists.orEmpty().map { it.list.id }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_000L
    }
}
