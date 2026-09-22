package dev.gorny.buymyway.data.sync

/**
 * Whether the RTDB connection may be open (PLAN.md *Battery policy*, STATE.md decision 58).
 * Whoever needs it holds it: the app while it is in the foreground (plus the grace period),
 * an [OutboxWorker] while it sends. It is open while anyone holds it and closed the moment
 * nobody does, so the two never switch it off under each other.
 */
class Connection(private val setOnline: (Boolean) -> Unit) {
    private var holders = 0

    @Synchronized
    fun acquire() {
        if (holders++ == 0) setOnline(true)
    }

    @Synchronized
    fun release() {
        check(holders > 0) { "released more often than acquired" }
        if (--holders == 0) setOnline(false)
    }

    suspend fun <T> hold(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
