package dev.gorny.buymyway.data.remote

import kotlinx.coroutines.flow.Flow

/** One child of a watched node: its current value, or null once it was removed. */
data class ChildEvent(val key: String, val node: Any?)

/**
 * The listeners of the list on screen (PLAN.md Phase 5, task 2; STATE.md decision 65). Every
 * flow attaches its listener when collected and removes it when cancelled, so nothing stays
 * attached once the screen is gone. A flow fails with [RemoteDenied] when the rules refuse the
 * read, which for a shared list means the user was removed from it.
 */
interface LiveSource {
    /** The children of [path] as they are and as they change; with [changedSince], only those whose `changedAt` is at least it. */
    fun children(path: String, changedSince: Long? = null): Flow<ChildEvent>

    /** The node at [path], again after every change; null while there is none. */
    fun value(path: String): Flow<Any?>

    /**
     * While collected, [path] holds the server time, and the server removes it if the
     * connection drops (`onDisconnect`); cancelling removes it at once. Never fails: presence
     * is a courtesy, not data.
     */
    fun present(path: String): Flow<Unit>
}
