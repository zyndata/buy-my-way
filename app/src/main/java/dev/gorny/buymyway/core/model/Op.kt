package dev.gorny.buymyway.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every mutation, ever (PLAN.md *Architecture*). An op is applied to Room at once and kept in
 * the outbox until the sync layer (Phase 5) has written the node it touches to RTDB.
 *
 * `at` is milliseconds since the epoch on the clock of the device that made the change, `actor`
 * the Firebase uid (null while signed out). The serial names are the outbox's storage format and
 * must not change.
 */
@Serializable
sealed class Op {
    abstract val id: String
    abstract val listId: String
    abstract val actor: String?
    abstract val at: Long

    /** Upsert of an item's content fields. */
    @Serializable
    @SerialName("item.put")
    data class ItemPut(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
        val itemId: String,
        val content: ItemContent,
    ) : Op()

    @Serializable
    @SerialName("item.check")
    data class ItemCheck(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
        val itemId: String,
        val checked: Boolean,
    ) : Op()

    @Serializable
    @SerialName("item.delete")
    data class ItemDelete(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
        val itemId: String,
    ) : Op()

    /** Creates the list, renames it, or reorders its categories. */
    @Serializable
    @SerialName("list.put")
    data class ListPut(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
        val name: String,
        val categoryOrder: List<String>,
        val ownerUid: String? = null,
    ) : Op()

    /** Not in PLAN.md's first list; STATE.md decision 39. */
    @Serializable
    @SerialName("list.delete")
    data class ListDelete(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
    ) : Op()

    @Serializable
    @SerialName("category.put")
    data class CategoryPut(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
        val categoryId: String,
        val name: String,
        val builtin: Boolean,
    ) : Op()

    @Serializable
    @SerialName("category.delete")
    data class CategoryDelete(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
        val categoryId: String,
        val moveItemsTo: String,
    ) : Op()

    /** „Wyczyść kupione": every item checked at or before `at` is gone (decision 39). */
    @Serializable
    @SerialName("items.clearChecked")
    data class ClearChecked(
        override val id: String,
        override val listId: String,
        override val actor: String?,
        override val at: Long,
    ) : Op()

    companion object {
        private val json = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

        fun encode(op: Op): String = json.encodeToString(serializer(), op)

        fun decode(text: String): Op = json.decodeFromString(serializer(), text)
    }
}
