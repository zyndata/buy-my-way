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
     *
     * [accessDenied]: the session was ended by the app because the database's access gate
     * refused the account (decision 60, revised). Same lists, same way back; another sentence.
     */
    data class SessionLost(val uid: String, val email: String?, val accessDenied: Boolean = false) : AccountState
}

sealed interface SignInResult {
    data object Done : SignInResult

    data object Cancelled : SignInResult

    /** No Google account on the phone. */
    data object NoAccount : SignInResult

    /** The phone holds [expectedEmail]'s lists; another account was picked (decision 57). */
    data class OtherAccount(val expectedEmail: String?) : SignInResult

    /** Signed in with Google, but the database's access gate refused the account (decision 60, revised). */
    data object NoAccess : SignInResult

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
    /**
     * Whether the database lets this account in (`/access/check`, decision 60, revised): false
     * when the rules refused it, null when there was no answer (offline), which is no verdict.
     */
    private val accessAllowed: suspend () -> Boolean? = { true },
) {
    private val firebaseUser: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val state: StateFlow<AccountState> = combine(firebaseUser, record.account) { user, stored ->
        when {
            stored == null -> AccountState.SignedOut
            stored.denied -> AccountState.SessionLost(stored.uid, stored.email, accessDenied = true)
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
        if (accessAllowed() == false) {
            // Nothing is recorded for an account that never got in; one that holds lists here
            // keeps them, and says why it cannot sync.
            auth.signOut()
            if (stored != null) record.setDenied(true)
            return SignInResult.NoAccess
        }
        record.set(AccountRecord.Account(user.uid, user.email, user.displayName))
        return SignInResult.Done
    }

    /**
     * Whether the session still works. A refresh the server refuses for the user
     * (`FirebaseAuthInvalidUserException`) ends it, which [state] reports as [AccountState.SessionLost].
     * So does the access gate turning the account away: then every refusal that follows says
     * nothing about the data, and no list or op may be dropped because of it. Anything else
     * (no network above all) is no verdict, and counts as working.
     */
    suspend fun checkSession(): Boolean {
        val user = auth.currentUser ?: return false
        try {
            user.getIdToken(false).await()
        } catch (_: FirebaseAuthInvalidUserException) {
            auth.signOut()
            return false
        } catch (_: FirebaseException) {
            return true
        }
        if (accessAllowed() == false) {
            record.setDenied(true)
            auth.signOut()
            return false
        }
        return true
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
