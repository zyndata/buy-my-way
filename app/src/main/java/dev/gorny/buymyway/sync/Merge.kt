package dev.gorny.buymyway.sync

/**
 * Merges a remote state into the local one.
 *
 * A stub: Phase 2 replaces it with the per-field last-writer-wins merge (content by
 * `updatedAt`, checked state by `checkedAt`, tombstones win) and its property tests.
 * Until then the one law that already holds is idempotence: merging a state with itself
 * changes nothing.
 */
object Merge {
    fun <T> merge(local: T, remote: T): T = if (local == remote) local else remote
}
