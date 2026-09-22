package dev.gorny.buymyway.data.remote

import com.google.android.gms.tasks.Task
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import dev.gorny.buymyway.core.sync.RemoteWrites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [RemoteLists] over the Firebase SDK. No listener stays attached: every read is one
 * single-value read (PLAN.md *Battery policy*; the live listener is Phase 5), and whether the
 * connection is open at all is [dev.gorny.buymyway.data.sync.Connection]'s business.
 */
class FirebaseRemoteLists(private val database: FirebaseDatabase) : RemoteLists {

    override fun update(paths: Map<String, Any?>): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        database.reference.updateChildren(paths) { error, _ ->
            if (error == null) result.complete(Unit) else result.completeExceptionally(error.toException(paths.keys))
        }
        return result
    }

    override suspend fun read(path: String): Any? = readOnce(database.getReference(path), path).value

    override suspend fun readChangedSince(path: String, since: Long): Map<String, Any?> {
        val query = database.getReference(path).orderByChild(RemoteWrites.CHANGED_AT).startAt(since.toDouble())
        return readOnce(query, path).children.associate { it.key.orEmpty() to it.value }
    }

    private suspend fun readOnce(query: Query, path: String): DataSnapshot = suspendCancellableCoroutine { cont ->
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                cont.resume(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException(setOf(path)))
            }
        }
        query.addListenerForSingleValueEvent(listener)
        cont.invokeOnCancellation { query.removeEventListener(listener) }
    }

    /** The paths name nodes, never their content, so the message is safe to log. */
    private fun DatabaseError.toException(paths: Collection<String>): Exception {
        val where = paths.take(3).joinToString()
        return if (code == DatabaseError.PERMISSION_DENIED) {
            RemoteDenied("permission denied at $where")
        } else {
            RemoteFailure("database error $code at $where", toException())
        }
    }
}

/** A read or write that failed for a reason other than the rules: try again later. */
class RemoteFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** `Task.await()` without `kotlinx-coroutines-play-services` (STATE.md decision 53). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
