package dev.gorny.buymyway.ui.share

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.R
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.auth.AccountState
import dev.gorny.buymyway.data.share.Sharing
import dev.gorny.buymyway.data.share.Sharing.SharingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShareUiState(
    val loading: Boolean = true,
    val signedIn: Boolean = false,
    val listName: String = "",
    val isOwner: Boolean = false,
    val shared: Boolean = false,
    val members: List<Member> = emptyList(),
    val myUid: String? = null,
    /** A sharing act is on its way to RTDB. */
    val busy: Boolean = false,
    /** The list is gone from the phone (deleted, left, or taken away). */
    val gone: Boolean = false,
)

sealed interface ShareEvent {
    /** A link to hand to the share sheet. */
    data class Link(val url: String, val listName: String) : ShareEvent

    data class Message(@param:StringRes val text: Int, val arg: String? = null) : ShareEvent

    /** Nobody signed in with [email] yet: offer the link instead (decision 63). */
    data class OfferLink(val email: String) : ShareEvent

    /** This user left the list. */
    data object Left : ShareEvent
}

/** Udostępnianie (PLAN.md *Screens*, Phase 5 task 4). */
class ShareViewModel(
    repo: ListRepository,
    private val listId: String,
    private val sharing: Sharing,
    account: Flow<AccountState>,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val events = Channel<ShareEvent>(Channel.BUFFERED)
    val messages: Flow<ShareEvent> = events.receiveAsFlow()

    val state: StateFlow<ShareUiState> = combine(
        repo.observeList(listId),
        repo.observeMembers(listId),
        account,
        busy,
    ) { detail, members, account, busy ->
        val uid = (account as? AccountState.SignedIn)?.uid
        val list = detail?.list
        ShareUiState(
            loading = account == AccountState.Loading,
            signedIn = uid != null,
            listName = list?.name.orEmpty(),
            isOwner = list != null && (list.ownerUid == null || list.ownerUid == uid),
            shared = list?.shared == true,
            members = members,
            myUid = uid,
            busy = busy,
            gone = list == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShareUiState())

    fun inviteLink(role: Role) = act {
        val url = sharing.inviteLink(listId, role)
        events.send(ShareEvent.Link(url, state.value.listName))
    }

    fun inviteByEmail(email: String, role: Role) = act {
        when (val result = sharing.inviteByEmail(listId, email, role)) {
            is Sharing.EmailInvite.Added -> events.send(ShareEvent.Message(R.string.share_email_added, email.trim()))
            Sharing.EmailInvite.AlreadyMember -> events.send(ShareEvent.Message(R.string.share_email_already, email.trim()))
            Sharing.EmailInvite.NotFound -> events.send(ShareEvent.OfferLink(email.trim()))
            Sharing.EmailInvite.InvalidAddress -> events.send(ShareEvent.Message(R.string.share_email_invalid))
        }
    }

    fun setRole(member: Member, role: Role) = act { sharing.setRole(listId, member, role) }

    fun remove(member: Member) = act { sharing.remove(listId, member) }

    fun makePrivate() = act { sharing.makePrivate(listId) }

    fun leave() = act {
        sharing.leave(listId)
        events.send(ShareEvent.Left)
    }

    /** One act at a time; a failure becomes a sentence, never a crash. */
    private fun act(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SharingFailure) {
                events.send(ShareEvent.Message(messageFor(e)))
            } catch (_: Exception) {
                events.send(ShareEvent.Message(R.string.share_failed))
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        @StringRes
        fun messageFor(failure: SharingFailure): Int = when (failure) {
            is SharingFailure.NotSignedIn -> R.string.share_sign_in_needed
            is SharingFailure.Offline -> R.string.share_offline
            is SharingFailure.Refused -> R.string.share_refused
            is SharingFailure.Expired -> R.string.invite_expired
            is SharingFailure.NoSuchInvite -> R.string.invite_not_found
        }
    }
}
