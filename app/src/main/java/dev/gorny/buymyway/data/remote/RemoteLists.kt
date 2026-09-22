package dev.gorny.buymyway.data.remote

import kotlinx.coroutines.Deferred

/**
 * The only way the sync layer reaches RTDB (PLAN.md Phase 4, task 2), small enough that the
 * tests can put an in-memory server behind it. Paths are relative to the database root and
 * values are what `NodeCodec` makes: maps of primitives.
 */
interface RemoteLists {
    /**
     * One atomic multi-path update. The write is queued the moment this returns, in call order;
     * the result completes when the server has acknowledged it, or fails with [RemoteDenied]
     * when the rules refused it. Anything else (offline, a timeout) leaves it pending.
     */
    fun update(paths: Map<String, Any?>): Deferred<Unit>

    /** The node at [path], or null when there is none. Throws [RemoteDenied] if not readable. */
    suspend fun read(path: String): Any?

    /** The children of [path] whose `changedAt` is at least [since], by key. */
    suspend fun readChangedSince(path: String, since: Long): Map<String, Any?>
}

/** The rules refused a read or a write (`PERMISSION_DENIED`). */
class RemoteDenied(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A map node's children with string keys, or an empty map for anything else. */
@Suppress("UNCHECKED_CAST")
fun Any?.asNode(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
