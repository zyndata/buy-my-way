package dev.gorny.buymyway.data.remote

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import dev.gorny.buymyway.core.sync.RemoteWrites
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** [LiveSource] over the Firebase SDK's listeners. */
class FirebaseLiveSource(private val database: FirebaseDatabase) : LiveSource {

    override fun children(path: String, changedSince: Long?): Flow<ChildEvent> = callbackFlow {
        val ref = database.getReference(path)
        val query = if (changedSince == null) ref else ref.orderByChild(RemoteWrites.CHANGED_AT).startAt(changedSince.toDouble())
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                trySend(ChildEvent(snapshot.key.orEmpty(), snapshot.value))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                trySend(ChildEvent(snapshot.key.orEmpty(), snapshot.value))
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                trySend(ChildEvent(snapshot.key.orEmpty(), null))
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                close(error.toFailure(path))
            }
        }
        query.addChildEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    override fun value(path: String): Flow<Any?> = callbackFlow {
        val ref = database.getReference(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.value)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toFailure(path))
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    override fun present(path: String): Flow<Unit> = callbackFlow {
        val ref = database.getReference(path)
        // The server removes it if this phone vanishes; registered before the write, so a
        // drop in between leaves nothing behind.
        ref.onDisconnect().removeValue()
        ref.setValue(ServerValue.TIMESTAMP)
        trySend(Unit)
        awaitClose {
            ref.removeValue()
            ref.onDisconnect().cancel()
        }
    }

    /** Paths name nodes, never content, so the message is safe to log. */
    private fun DatabaseError.toFailure(path: String): Exception = if (code == DatabaseError.PERMISSION_DENIED) {
        RemoteDenied("permission denied at $path")
    } else {
        RemoteFailure("database error $code at $path", toException())
    }
}
