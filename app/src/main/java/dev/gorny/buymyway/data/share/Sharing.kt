package dev.gorny.buymyway.data.share

import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.ShoppingList
import dev.gorny.buymyway.core.share.InviteLinks
import dev.gorny.buymyway.core.sync.RemoteWrites
import dev.gorny.buymyway.data.ListRepository
import dev.gorny.buymyway.data.remote.RemoteDenied
import dev.gorny.buymyway.data.remote.RemoteFailure
import dev.gorny.buymyway.data.remote.RemoteLists
import dev.gorny.buymyway.data.remote.asNode
import dev.gorny.buymyway.data.sync.Connection
import dev.gorny.buymyway.data.sync.SyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sharing (PLAN.md *Sharing & permissions*, Phase 5 task 4; STATE.md decision 64). Every act
 * here is online: one acknowledged multi-path update, then the list is read again so Room
 * holds what the server now has. None of it goes through the outbox. Failures are
 * [SharingFailure]s, which the screens turn into sentences.
 */
class Sharing(
    private val remote: RemoteLists,
    private val engine: SyncEngine,
    private val repo: ListRepository,
    private val connection: Connection,
    /** The signed-in user with a session, or null. */
    private val me: suspend () -> Me?,
    private val random: (ByteArray) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = SyncEngine.DEFAULT_TIMEOUT_MS,
) {
    data class Me(val uid: String, val name: String?)

    sealed class SharingFailure(message: String) : Exception(message) {
        class NotSignedIn : SharingFailure("not signed in")

        /** No answer: offline, most likely. Nothing was changed. */
        class Offline : SharingFailure("no answer")

        /** The rules refused: this user may not do it (any more). */
        class Refused : SharingFailure("refused")

        class Expired : SharingFailure("invite expired")

        class NoSuchInvite : SharingFailure("no such invite")
    }

    /** What an invite link says, read before accepting it. */
    data class Invite(
        val token: String,
        val listId: String,
        val role: Role,
        val by: String,
        val byName: String?,
        val listName: String,
        val expiresAt: Long,
    )

    sealed interface EmailInvite {
        data class Added(val uid: String) : EmailInvite

        data object AlreadyMember : EmailInvite

        /** Nobody with that address has signed in yet: send them the link instead. */
        data object NotFound : EmailInvite

        data object InvalidAddress : EmailInvite
    }

    /** A link anyone signed in can use, each person once, for [INVITE_DAYS] days. */
    suspend fun inviteLink(listId: String, role: Role): String = online { user ->
        val list = ownList(listId, user)
        val token = InviteLinks.newToken(random)
        val expiresAt = clock() + INVITE_DAYS * DAY_MS
        ack(RemoteWrites.invite(token, listId, role, user.uid, user.name, list.name, expiresAt))
        InviteLinks.link(token)
    }

    /**
     * Adds whoever signed in with [email] (decision 63). Nobody is e-mailed: someone the index
     * does not know gets the link from the screen.
     */
    suspend fun inviteByEmail(listId: String, email: String, role: Role): EmailInvite {
        val key = RemoteWrites.emailKey(email) ?: return EmailInvite.InvalidAddress
        return online { user ->
            ownList(listId, user)
            val uid = io { remote.read(RemoteWrites.emailIndex(key)) } as? String
            when {
                uid == null -> EmailInvite.NotFound
                uid == user.uid || repo.observeMembers(listId).first().any { it.uid == uid } -> EmailInvite.AlreadyMember
                else -> {
                    ack(RemoteWrites.setMember(listId, uid, role, isNew = true))
                    engine.pull(user.uid, listId)
                    EmailInvite.Added(uid)
                }
            }
        }
    }

    suspend fun setRole(listId: String, member: Member, role: Role) = online { user ->
        require(role != Role.OWNER && member.role != Role.OWNER) { "the owner's role does not change" }
        ack(RemoteWrites.setMember(listId, member.uid, role, isNew = false))
        engine.pull(user.uid, listId)
    }

    suspend fun remove(listId: String, member: Member) = online { user ->
        require(member.role != Role.OWNER) { "the owner is not removed" }
        ack(RemoteWrites.removeMember(listId, member.uid))
        engine.pull(user.uid, listId)
    }

    /** „Uczyń prywatną": everyone else loses the list at once. */
    suspend fun makePrivate(listId: String) = online { user ->
        val members = repo.observeMembers(listId).first().map { it.uid }
        ack(RemoteWrites.makePrivate(listId, user.uid, members))
        engine.pull(user.uid, listId)
    }

    /** „Opuść listę" (decision 64): this user's member entry goes, and the list leaves the phone. */
    suspend fun leave(listId: String) = online { user ->
        ack(RemoteWrites.removeMember(listId, user.uid))
        repo.forget(listId)
    }

    /** What an invite link offers, or [SharingFailure.NoSuchInvite]. */
    suspend fun readInvite(token: String): Invite = online { readInviteNode(token) }

    /** Joins the list the invite is for; returns its id, to open it. */
    suspend fun accept(token: String): String = online { user ->
        val invite = readInviteNode(token)
        if (repo.observeMembers(invite.listId).first().any { it.uid == user.uid }) return@online invite.listId
        if (invite.expiresAt <= clock()) throw SharingFailure.Expired()
        ack(RemoteWrites.accept(token, invite.listId, invite.role, user.uid))
        engine.pull(user.uid, invite.listId)
        invite.listId
    }

    // --- Internals ------------------------------------------------------------------------

    private suspend fun readInviteNode(token: String): Invite {
        if (!InviteLinks.isToken(token)) throw SharingFailure.NoSuchInvite()
        val node = io { remote.read(RemoteWrites.invite(token)) }?.asNode() ?: throw SharingFailure.NoSuchInvite()
        val listId = node["listId"] as? String ?: throw SharingFailure.NoSuchInvite()
        val role = Role.entries.firstOrNull { it.name.equals(node["role"] as? String, ignoreCase = true) && it != Role.OWNER }
            ?: throw SharingFailure.NoSuchInvite()
        return Invite(
            token = token,
            listId = listId,
            role = role,
            by = node["by"] as? String ?: "",
            byName = node["byName"] as? String,
            listName = node["listName"] as? String ?: "",
            expiresAt = (node["expiresAt"] as? Number)?.toLong() ?: 0,
        )
    }

    /**
     * The owner's list, in RTDB and shared: a list still on the phone only is uploaded first,
     * and a private one gets its members node (PLAN.md: „Udostępnij" creates it).
     */
    private suspend fun ownList(listId: String, user: Me): ShoppingList {
        val list = repo.loadState(listId).list?.takeIf { it.deletedAt == null } ?: throw SharingFailure.Refused()
        if (list.ownerUid != null && list.ownerUid != user.uid) throw SharingFailure.Refused()
        if (!repo.observeSynced(listId).first()) {
            engine.flush(user.uid)
            if (!repo.observeSynced(listId).first()) throw SharingFailure.Offline()
        }
        if (!list.shared) {
            ack(RemoteWrites.share(listId, user.uid))
            engine.pull(user.uid, listId)
        }
        return list
    }

    private suspend fun <T> online(block: suspend (Me) -> T): T {
        val user = me() ?: throw SharingFailure.NotSignedIn()
        return try {
            connection.hold { block(user) }
        } catch (_: RemoteDenied) {
            throw SharingFailure.Refused()
        } catch (_: RemoteFailure) {
            throw SharingFailure.Offline()
        }
    }

    private suspend fun ack(paths: Map<String, Any?>) = io { remote.update(paths).await() }

    private suspend fun <T> io(block: suspend () -> T): T {
        val answer = withTimeoutOrNull(timeoutMs) { listOf(block()) } ?: throw RemoteFailure("no answer in $timeoutMs ms")
        return answer.single()
    }

    companion object {
        const val INVITE_DAYS = 7L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
