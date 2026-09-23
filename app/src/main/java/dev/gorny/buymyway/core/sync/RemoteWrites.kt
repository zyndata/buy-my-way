package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.model.Role
import java.util.Locale

/**
 * What a change writes to RTDB, as the paths of one multi-path update from the root
 * (STATE.md decision 56). Pure, so the shapes the rules check are tested on the JVM.
 *
 * An op writes only the field group it changes, with that group's stamp, so a tick and an
 * edit made on two phones never overwrite each other. Every item write also sets
 * [CHANGED_AT] to the server's clock, which is what a catch-up queries by (decision 54).
 * A null value removes the field.
 */
object RemoteWrites {
    /** `ServerValue.TIMESTAMP`, spelled out so this file needs no Firebase class. */
    val SERVER_TIME: Map<String, String> = mapOf(".sv" to "timestamp")

    const val CHANGED_AT = "changedAt"
    const val OWNER = "owner"

    fun list(listId: String) = "lists/$listId"

    fun meta(listId: String) = "lists/$listId/meta"

    fun categories(listId: String) = "lists/$listId/categories"

    fun category(listId: String, categoryId: String) = "lists/$listId/categories/$categoryId"

    fun items(listId: String) = "lists/$listId/items"

    fun item(listId: String, itemId: String) = "lists/$listId/items/$itemId"

    fun userLists(uid: String) = "userLists/$uid"

    fun user(uid: String) = "users/$uid"

    fun prefs(uid: String) = "users/$uid/prefs"

    fun categoryMemory(uid: String) = "users/$uid/prefs/categoryMemory"

    fun listSort(uid: String) = "users/$uid/prefs/listSort"

    /** „Moje produkty" (Phase 8b): the user's own words, private to their account. */
    fun ownProducts(uid: String) = "users/$uid/prefs/products"

    fun members(listId: String) = "lists/$listId/members"

    fun member(listId: String, uid: String) = "lists/$listId/members/$uid"

    fun presence(listId: String) = "lists/$listId/presence"

    fun invite(token: String) = "invites/$token"

    /** The owner's own index of the invites they made, so expired ones can be found (decision 64). */
    fun userInvites(uid: String) = "users/$uid/invites"

    fun photos(listId: String) = "photos/$listId"

    fun photo(listId: String, itemId: String) = "photos/$listId/$itemId"

    fun emailIndex(key: String) = "emailIndex/$key"

    /** This phone's FCM registration, written by its own user and read by nobody (decision 98). */
    fun fcmTokens(uid: String) = "fcmTokens/$uid"

    fun fcmToken(uid: String, token: String) = "fcmTokens/$uid/$token"

    /** How a role is written in `members` and `/userLists`. */
    fun roleValue(role: Role): String = role.name.lowercase(Locale.ROOT)

    /**
     * The `/emailIndex` key for [email] (decision 63): lower case, `.` written as `,`. Null for
     * an address RTDB cannot use as a key; that person is invited by link.
     */
    fun emailKey(email: String): String? {
        val normalised = email.trim().lowercase(Locale.ROOT)
        if (!normalised.contains('@') || normalised.any { it in FORBIDDEN_IN_KEYS || it.isISOControl() || it.isWhitespace() }) return null
        return normalised.replace('.', ',')
    }

    private const val FORBIDDEN_IN_KEYS = "$#[]/"

    /**
     * The update for [op]. [known] is what the device holds for the op's list, for the fields a
     * write must repeat as they are (`createdAt`, `ownerUid`); [uid] is the signed-in user.
     */
    fun forOp(op: Op, uid: String, known: ListState): Map<String, Any?> {
        val actor = op.actor ?: uid
        return when (op) {
            is Op.ItemPut -> {
                val base = item(op.listId, op.itemId)
                val local = known.items[op.itemId]?.takeIf { it.createdAt > 0 }
                val c = op.content
                mapOf(
                    "$base/name" to c.name,
                    "$base/quantity" to c.quantity,
                    "$base/unit" to c.unit,
                    "$base/categoryId" to c.categoryId,
                    "$base/note" to c.note,
                    "$base/photoAt" to c.photoAt,
                    "$base/sortKey" to c.sortKey,
                    "$base/manualKey" to c.manualKey,
                    "$base/updatedAt" to op.at,
                    "$base/updatedBy" to actor,
                    "$base/createdAt" to (local?.createdAt ?: op.at),
                    "$base/createdBy" to (local?.let { it.createdBy ?: uid } ?: actor),
                    "$base/$CHANGED_AT" to SERVER_TIME,
                )
            }
            is Op.ItemCheck -> {
                val base = item(op.listId, op.itemId)
                mapOf(
                    "$base/checked" to op.checked,
                    "$base/checkedAt" to op.at,
                    "$base/checkedBy" to actor,
                    "$base/$CHANGED_AT" to SERVER_TIME,
                )
            }
            is Op.ItemDelete -> {
                val base = item(op.listId, op.itemId)
                mapOf("$base/deletedAt" to op.at, "$base/$CHANGED_AT" to SERVER_TIME)
            }
            is Op.ListPut -> {
                val base = meta(op.listId)
                // An editor renames a shared list too; only the owner has an "owner" entry.
                val owner = known.list?.ownerUid ?: op.ownerUid ?: uid
                val local = known.list?.takeIf { it.createdAt > 0 }
                mapOf(
                    "$base/name" to op.name,
                    "$base/categoryOrder" to op.categoryOrder,
                    "$base/updatedAt" to op.at,
                    "$base/updatedBy" to actor,
                    "$base/ownerUid" to owner,
                    "$base/createdAt" to (local?.createdAt ?: op.at),
                ) + if (owner == uid) mapOf("${userLists(uid)}/${op.listId}" to OWNER) else emptyMap()
            }
            // Decision 36: the items, categories and photos go with the list; the meta is the
            // tombstone. Phase 6 added the photos (decision 72).
            is Op.ListDelete -> mapOf(
                "${meta(op.listId)}/deletedAt" to op.at,
                items(op.listId) to null,
                categories(op.listId) to null,
                photos(op.listId) to null,
            )
            is Op.ClearChecked -> mapOf("${meta(op.listId)}/clearedAt" to op.at)
            is Op.CategoryPut -> {
                val base = category(op.listId, op.categoryId)
                mapOf(
                    "$base/name" to op.name,
                    "$base/builtin" to op.builtin,
                    "$base/updatedAt" to op.at,
                    "$base/updatedBy" to actor,
                )
            }
            is Op.CategoryDelete -> {
                val base = category(op.listId, op.categoryId)
                mapOf("$base/deletedAt" to op.at, "$base/moveItemsTo" to op.moveItemsTo)
            }
        }
    }

    /**
     * A list that is not in RTDB yet, whole, in one update: its meta, every category and item
     * the device knows the content of, and the owner's `/userLists` entry. Nodes whose content
     * never arrived (`updatedAt == 0`) can only have come from RTDB, so they are left out.
     */
    fun wholeList(state: ListState, uid: String): Map<String, Any?> {
        val list = requireNotNull(state.list) { "a list without its meta cannot be uploaded" }
        return buildMap {
            put(meta(list.id), NodeCodec.listToNode(list.copy(ownerUid = list.ownerUid ?: uid)))
            for (category in state.categories.values.filter { it.updatedAt > 0 }.sortedBy { it.id }) {
                put(category(list.id, category.id), NodeCodec.categoryToNode(category))
            }
            for (item in state.items.values.filter { it.updatedAt > 0 }.sortedBy { it.id }) {
                put(item(list.id, item.id), NodeCodec.itemToNode(item) + (CHANGED_AT to SERVER_TIME))
            }
            put("${userLists(uid)}/${list.id}", OWNER)
        }
    }

    /**
     * What is left of a deleted list after 30 days, removed by its owner (decision 56), with
     * the `/userLists` entry of everyone it was shared with.
     */
    fun removeList(listId: String, uid: String, memberUids: Collection<String> = emptyList()): Map<String, Any?> = buildMap {
        put(list(listId), null)
        put(photos(listId), null)
        for (member in (memberUids + uid).distinct().sorted()) put("${userLists(member)}/$listId", null)
    }

    /** Nodes gone for more than 30 days, and their photos, removed by the owner (decision 66). */
    fun purge(listId: String, itemIds: Collection<String>, categoryIds: Collection<String>): Map<String, Any?> = buildMap {
        for (itemId in itemIds.sorted()) {
            put(item(listId, itemId), null)
            put(photo(listId, itemId), null)
        }
        for (categoryId in categoryIds.sorted()) put(category(listId, categoryId), null)
    }

    /** A photo, written before the item's `photoAt` names it (decision 71). */
    fun putPhoto(listId: String, itemId: String, photo: NodeCodec.Photo): Map<String, Any?> =
        mapOf(photo(listId, itemId) to NodeCodec.photoToNode(photo))

    fun removePhoto(listId: String, itemId: String): Map<String, Any?> = mapOf(photo(listId, itemId) to null)

    /** `/users/{uid}` and `/emailIndex/{key}`, written at every sign-in (decisions 57 and 63). */
    fun profile(uid: String, name: String?, email: String?, photoUrl: String?, at: Long): Map<String, Any?> = buildMap {
        val base = user(uid)
        put("$base/name", name)
        put("$base/email", email)
        put("$base/photoUrl", photoUrl)
        put("$base/updatedAt", at)
        email?.let(::emailKey)?.let { put(emailIndex(it), uid) }
    }

    // --- Sharing (decision 64): online acts, not ops -----------------------------------------

    /** „Udostępnij": a private list gets its members node, with its owner in it. */
    fun share(listId: String, ownerUid: String): Map<String, Any?> = mapOf(
        member(listId, ownerUid) to mapOf("role" to OWNER, "since" to SERVER_TIME),
    )

    /** An invite link's node, and the owner's index entry for it. */
    fun invite(token: String, listId: String, role: Role, uid: String, byName: String?, listName: String, expiresAt: Long): Map<String, Any?> =
        mapOf(
            invite(token) to buildMap {
                put("listId", listId)
                put("role", roleValue(role))
                put("by", uid)
                put("expiresAt", expiresAt)
                put("listName", listName)
                byName?.let { put("byName", it) }
            },
            "${userInvites(uid)}/$token" to mapOf("listId" to listId, "expiresAt" to expiresAt),
        )

    fun removeInvites(uid: String, tokens: Collection<String>): Map<String, Any?> = buildMap {
        for (token in tokens.sorted()) {
            put(invite(token), null)
            put("${userInvites(uid)}/$token", null)
        }
    }

    /** Accepting an invite: [uid] adds themselves under the invite's rules. */
    fun accept(token: String, listId: String, role: Role, uid: String): Map<String, Any?> = mapOf(
        member(listId, uid) to mapOf("role" to roleValue(role), "since" to SERVER_TIME, "invite" to token),
        "${userLists(uid)}/$listId" to roleValue(role),
    )

    /** The owner adds someone found by e-mail ([isNew]), or changes a member's role. */
    fun setMember(listId: String, uid: String, role: Role, isNew: Boolean): Map<String, Any?> = if (isNew) {
        mapOf(
            member(listId, uid) to mapOf("role" to roleValue(role), "since" to SERVER_TIME),
            "${userLists(uid)}/$listId" to roleValue(role),
        )
    } else {
        mapOf("${member(listId, uid)}/role" to roleValue(role), "${userLists(uid)}/$listId" to roleValue(role))
    }

    /** The owner removes a member, or a member leaves: the same three paths. */
    fun removeMember(listId: String, uid: String): Map<String, Any?> = mapOf(
        member(listId, uid) to null,
        "${userLists(uid)}/$listId" to null,
        "${presence(listId)}/$uid" to null,
    )

    // --- Phase 9: the push registration and „Usuń moje dane" ---------------------------------

    /** This device's FCM token, stamped by the server so the rules can insist on `now`. */
    fun registerToken(uid: String, token: String): Map<String, Any?> =
        mapOf(fcmToken(uid, token) to mapOf("at" to SERVER_TIME))

    fun removeToken(uid: String, token: String): Map<String, Any?> = mapOf(fcmToken(uid, token) to null)

    /**
     * „Usuń moje dane" (decision 97), last: what is left of one user once their own lists are
     * gone and they have left everybody else's. Each `/userLists` entry is removed by its own
     * path, because the rules are written one list at a time and do not reach up to the parent.
     */
    fun forgetUser(uid: String, email: String?, listIds: Collection<String>): Map<String, Any?> = buildMap {
        for (listId in listIds.distinct().sorted()) put("${userLists(uid)}/$listId", null)
        put(user(uid), null)
        put(fcmTokens(uid), null)
        email?.let(::emailKey)?.let { put(emailIndex(it), null) }
    }

    /** „Uczyń prywatną": every member goes, and with them their `/userLists` entries. */
    fun makePrivate(listId: String, ownerUid: String, memberUids: Collection<String>): Map<String, Any?> = buildMap {
        put(members(listId), null)
        for (member in memberUids.filter { it != ownerUid }.sorted()) put("${userLists(member)}/$listId", null)
    }
}
