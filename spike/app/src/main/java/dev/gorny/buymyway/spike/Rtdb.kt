package dev.gorny.buymyway.spike

import android.os.SystemClock
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * RTDB op latency, measured as an echo so the two phones' clocks never meet: phone A writes a
 * ping under /spike/ping, phone B (responder on) answers each one under /spike/pong, and A
 * times the round trip on its own monotonic clock. One way ≈ RTT / 2. B also logs its own
 * estimate from the ping's server timestamp and /.info/serverTimeOffset.
 */
object Rtdb {
    private val db get() = FirebaseDatabase.getInstance()
    @Volatile private var offset = 0L
    private var responder: ChildEventListener? = null

    fun trackServerOffset() {
        db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { offset = s.getValue(Long::class.java) ?: 0L }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun serverNow() = System.currentTimeMillis() + offset

    fun toggleResponder(uid: String): Boolean {
        val q = db.getReference("spike/ping").orderByChild("at").startAt(serverNow().toDouble())
        responder?.let { q.ref.removeEventListener(it); responder = null; SpikeLog.i("RTDB responder off"); return false }
        responder = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                if (s.child("from").getValue(String::class.java) == uid) return
                val at = s.child("at").getValue(Long::class.java) ?: return
                db.getReference("spike/pong/${s.key}").setValue(mapOf("by" to uid, "at" to ServerValue.TIMESTAMP))
                SpikeLog.i("RTDB ping #${s.child("seq").value} arrived, server→here ≈ ${serverNow() - at} ms (clock-offset estimate)")
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { SpikeLog.i("RTDB responder cancelled: ${e.message}") }
        }
        q.addChildEventListener(responder!!)
        SpikeLog.i("RTDB responder on — answering pings from the other phone")
        return true
    }

    suspend fun pingRun(uid: String, n: Int = 20) {
        val waiting = ConcurrentHashMap<String, CompletableDeferred<Long>>()
        val pongs = db.getReference("spike/pong").orderByChild("at").startAt(serverNow().toDouble() - 5_000)
        val listener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                waiting.remove(s.key)?.complete(SystemClock.elapsedRealtime())
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { SpikeLog.i("RTDB pong listener cancelled: ${e.message}") }
        }
        pongs.addChildEventListener(listener)
        val rtts = mutableListOf<Long>()
        try {
            // Sample 0 is a warm-up (connection, listener sync) and is reported but not counted:
            // in the app the listener is already attached while a list is on screen.
            for (seq in 0..n) {
                val ref = db.getReference("spike/ping").push()
                val done = CompletableDeferred<Long>()
                waiting[ref.key!!] = done
                val t0 = SystemClock.elapsedRealtime()
                ref.setValue(mapOf("from" to uid, "seq" to seq, "at" to ServerValue.TIMESTAMP)).await()
                val t1 = withTimeoutOrNull(10_000) { done.await() }
                if (t1 == null) SpikeLog.i("RTDB ping #$seq: no pong in 10 s (is the responder on?)")
                else {
                    SpikeLog.i("RTDB ping #$seq RTT ${t1 - t0} ms" + if (seq == 0) " (warm-up, not counted)" else "")
                    if (seq > 0) rtts += t1 - t0
                }
                delay(1_000)
            }
        } finally {
            pongs.ref.removeEventListener(listener)
        }
        SpikeLog.i(stats("RTDB RTT", rtts))
        SpikeLog.i(stats("RTDB one-way (RTT/2)", rtts.map { it / 2 }))
    }

    suspend fun clear() {
        db.getReference("spike/ping").removeValue().await()
        db.getReference("spike/pong").removeValue().await()
        SpikeLog.i("RTDB /spike/ping and /spike/pong cleared")
    }

    suspend fun putHandoff(folderId: String, fileId: String, by: String) =
        db.getReference("spike/handoff").setValue(mapOf("folderId" to folderId, "fileId" to fileId, "by" to by)).await()

    suspend fun getHandoff(): Pair<String, String>? {
        val s = db.getReference("spike/handoff").get().await()
        val folder = s.child("folderId").getValue(String::class.java) ?: return null
        val file = s.child("fileId").getValue(String::class.java) ?: return null
        return folder to file
    }
}
