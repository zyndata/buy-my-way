package dev.gorny.buymyway.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dev.gorny.buymyway.data.prefs.AccountRecord
import dev.gorny.buymyway.data.remote.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

sealed interface AccountState {
    data object Loading : AccountState

    data object SignedOut : AccountState

    data class SignedIn(val uid: String, val email: String?, val name: String?) : AccountState

    /**
     * The phone's lists belong to [uid], but its Firebase session is gone without a sign-out
     * (STATE.md decision 23: e.g. the app was removed in Google Account → Connections). The
     * lists stay, new ops are still made under [uid], and the app asks to sign in again.
     */
    data class SessionLost(val uid: String, val email: String?) : AccountState
}

sealed interface SignInResult {
    data object Done : SignInResult

    data object Cancelled : SignInResult

    /** No Google account on the phone. */
    data object NoAccount : SignInResult

    /** The phone holds [expectedEmail]'s lists; another account was picked (decision 57). */
    data class OtherAccount(val expectedEmail: String?) : SignInResult

    data object Failed : SignInResult
}

/**
 * Sign in with Google → Firebase Auth (PLAN.md *Google identity*, STATE.md decision 57).
 *
 * The Google ID token goes from Credential Manager straight into Firebase and is dropped; the
 * app never stores, logs or passes one on. What the app keeps is [AccountRecord]: which
 * account the lists on this phone belong to.
 */
class AccountRepository(
    private val auth: FirebaseAuth,
    private val record: AccountRecord,
    private val serverClientId: String,
    scope: CoroutineScope,
) {
    private val firebaseUser: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val state: StateFlow<AccountState> = combine(firebaseUser, record.account) { user, stored ->
        when {
            stored == null -> AccountState.SignedOut
            user != null && user.uid == stored.uid -> AccountState.SignedIn(stored.uid, stored.email, stored.name)
            else -> AccountState.SessionLost(stored.uid, stored.email)
        }
    }.stateIn(scope, SharingStarted.Eagerly, AccountState.Loading)

    private suspend fun settled(): AccountState = state.first { it != AccountState.Loading }

    /** The uid a change is made under: the lists' account, even while its session is lost. */
    suspend fun actorUid(): String? = when (val s = settled()) {
        is AccountState.SignedIn -> s.uid
        is AccountState.SessionLost -> s.uid
        else -> null
    }

    /** The uid sync may use: signed in, with a session. */
    suspend fun syncUid(): String? = (settled() as? AccountState.SignedIn)?.uid

    /** The signed-in Firebase user's profile, for `/users/{uid}`. */
    fun profile(): Profile? = auth.currentUser?.let { Profile(it.uid, it.displayName, it.email, it.photoUrl?.toString()) }

    data class Profile(val uid: String, val name: String?, val email: String?, val photoUrl: String?)

    /** [activity] must be an Activity: Credential Manager shows its sheet over it. */
    suspend fun signIn(activity: Context): SignInResult {
        val credential = try {
            val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return SignInResult.Cancelled
        } catch (_: NoCredentialException) {
            return SignInResult.NoAccount
        } catch (_: GetCredentialException) {
            return SignInResult.Failed
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return SignInResult.Failed
        }
        val user = try {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
        } catch (_: GoogleIdTokenParsingException) {
            null
        } catch (_: FirebaseException) {
            null
        } ?: return SignInResult.Failed

        val stored = record.account.first()
        if (stored != null && stored.uid != user.uid) {
            // Two accounts' lists on one phone are not supported (PLAN.md *Google identity*).
            auth.signOut()
            return SignInResult.OtherAccount(stored.email)
        }
        record.set(AccountRecord.Account(user.uid, user.email, user.displayName))
        return SignInResult.Done
    }

    /**
     * Whether the session still works. A refresh the server refuses for the user
     * (`FirebaseAuthInvalidUserException`) ends it, which [state] reports as [AccountState.SessionLost].
     * Anything else (no network above all) is no verdict, and counts as working.
     */
    suspend fun checkSession(): Boolean {
        val user = auth.currentUser ?: return false
        return try {
            user.getIdToken(false).await()
            true
        } catch (_: FirebaseAuthInvalidUserException) {
            auth.signOut()
            false
        } catch (_: FirebaseException) {
            true
        }
    }

    /** The Firebase session and Credential Manager's memory of the choice. The caller clears the rest. */
    suspend fun endSession(context: Context) {
        auth.signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // Nothing to clear, or no provider: the Firebase session is what mattered.
        }
    }
}
