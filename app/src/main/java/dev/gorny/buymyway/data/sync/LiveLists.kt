package dev.gorny.buymyway.data.sync

import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.NodeCodec
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.remote.LiveSource
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.asNode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * The real-time half (PLAN.md Phase 5, tasks 2 and 3; STATE.md decision 65). While a screen
 * collects one of these flows, the listeners are attached and the connection is held; when it
 * stops, both go. What arrives is merged into Room through the same [ListRepository.applyRemote]
 * as a catch-up, so a live change and a caught-up one are the same thing.
 */
class LiveLists(
    private val repo: ListRepository,
    private val engine: SyncEngine,
    private val source: LiveSource,
    private val connection: Connection,
    /** The signed-in uid with a session, or null: then nothing is watched. */
    private val uid: suspend () -> String?,
) {
    /**
     * Watches the list on screen: its meta, members, categories and the items changed since
     * `seenUpTo`, and marks this user present in it. Emits the uids present, this user's own
     * included. A list not in RTDB yet is watched from the moment it is.
     */
    fun watch(listId: String): Flow<Set<String>> = channelFlow {
        val me = uid() ?: return@channelFlow
        repo.observeSynced(listId).first { it }
        connection.hold {
            coroutineScope {
                launch {
                    source.value(RemoteWrites.meta(listId)).collect { node ->
                        if (node != null) {
                            val list = NodeCodec.listFromNode(listId, node.asNode(), shared = false)
                            repo.applyRemote(listId, ListState(list = list), serverTime = null)
                        }
                    }
                }
                launch {
                    source.value(RemoteWrites.members(listId)).collect { node ->
                        val members = node.asNode().map { (memberUid, m) -> NodeCodec.memberFromNode(listId, memberUid, m.asNode()) }
                        engine.applyMembers(listId, members)
                    }
                }
                launch {
                    source.children(RemoteWrites.categories(listId)).collect { event ->
                        val node = event.node
                        if (node == null) {
                            repo.purgeLocal(listId, emptyList(), listOf(event.key))
                        } else {
                            val category = NodeCodec.categoryFromNode(listId, event.key, node.asNode())
                            repo.applyRemote(listId, ListState(categories = mapOf(category.id to category)), serverTime = null)
                        }
                    }
                }
                launch {
                    val since = repo.seenUpTo(listId)
                    source.children(RemoteWrites.items(listId), changedSince = since).collect { event ->
                        val node = event.node
                        if (node == null) {
                            // Removed by the owner after 30 days, or with the whole list.
                            repo.purgeLocal(listId, listOf(event.key), emptyList())
                        } else {
                            val item = NodeCodec.itemFromNode(listId, event.key, node.asNode())
                            repo.applyRemote(listId, ListState(items = mapOf(item.id to item)), NodeCodec.changedAt(node.asNode()))
                        }
                    }
                }
                launch { source.present("${RemoteWrites.presence(listId)}/$me").collect {} }
                source.value(RemoteWrites.presence(listId)).collect { send(it.asNode().keys) }
            }
        }
    }.catch { e ->
        when (e) {
            // Removed from the list, or it was made private: it leaves the phone with a sentence.
            is RemoteDenied -> uid()?.let { engine.lost(it, listId) }
            is RemoteFailure -> Unit // the catch-up on the next start fills the gap
            else -> throw e
        }
    }.distinctUntilChanged()

    /**
     * For the home screen: who is looking at each of [listIds] (the shared ones), by list.
     * Only presence, never the lists' content (decision 65).
     */
    fun presence(listIds: Set<String>): Flow<Map<String, Set<String>>> {
        if (listIds.isEmpty()) return flowOf(emptyMap())
        return channelFlow {
            uid() ?: return@channelFlow
            connection.hold {
                val flows = listIds.sorted().map { listId ->
                    source.value(RemoteWrites.presence(listId))
                        .map { listId to it.asNode().keys }
                        .catch { emit(listId to emptySet()) }
                        .onStart { emit(listId to emptySet()) }
                }
                combine(flows) { pairs -> pairs.toMap() }.collect { send(it) }
            }
        }.distinctUntilChanged()
    }

    /**
     * `/userLists/{uid}` for the home screen (PLAN.md *Battery policy*): emits the list ids
     * this user has in RTDB, again whenever one is added or taken away, so a list shared a
     * moment ago shows up without a pull.
     */
    fun userLists(): Flow<Set<String>> = channelFlow {
        val me = uid() ?: return@channelFlow
        connection.hold {
            source.value(RemoteWrites.userLists(me)).collectLatest { send(it.asNode().keys) }
        }
    }.catch { /* no list shared a moment ago this time; the catch-up still runs */ }.distinctUntilChanged()
}
