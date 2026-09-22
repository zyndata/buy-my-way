package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.Op

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
                val local = known.list?.takeIf { it.createdAt > 0 }
                mapOf(
                    "$base/name" to op.name,
                    "$base/categoryOrder" to op.categoryOrder,
                    "$base/updatedAt" to op.at,
                    "$base/updatedBy" to actor,
                    "$base/ownerUid" to (known.list?.ownerUid ?: op.ownerUid ?: uid),
                    "$base/createdAt" to (local?.createdAt ?: op.at),
                    "${userLists(uid)}/${op.listId}" to OWNER,
                )
            }
            // Decision 36: the items and categories go with the list; the meta is the tombstone.
            is Op.ListDelete -> mapOf(
                "${meta(op.listId)}/deletedAt" to op.at,
                items(op.listId) to null,
                categories(op.listId) to null,
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

    /** What is left of a deleted list after 30 days, removed by its owner (decision 56). */
    fun removeList(listId: String, uid: String): Map<String, Any?> = mapOf(
        list(listId) to null,
        "${userLists(uid)}/$listId" to null,
    )

    /** `/users/{uid}` and `/emailIndex/{hash}`, written at every sign-in (decision 57). */
    fun profile(uid: String, name: String?, email: String?, photoUrl: String?, emailHash: String?, at: Long): Map<String, Any?> =
        buildMap {
            val base = user(uid)
            put("$base/name", name)
            put("$base/email", email)
            put("$base/photoUrl", photoUrl)
            put("$base/updatedAt", at)
            if (emailHash != null) put("emailIndex/$emailHash", uid)
        }
}
