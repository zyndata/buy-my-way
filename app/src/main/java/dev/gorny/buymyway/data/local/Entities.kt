package dev.gorny.buymyway.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gorny.buymyway.core.model.Category
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.ShoppingList

/*
 * Schema v1 (PLAN.md Phase 2, STATE.md decisions 39 and 42); v2 adds `items.manualKey` (decision 67). The tables mirror the domain types
 * of core/model one to one; the mappers below are the only place the two meet. No foreign keys:
 * a node may legitimately arrive before the node it belongs to, and deleting a list is a
 * tombstone, not a cascade.
 */

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerUid: String?,
    val shared: Boolean,
    val categoryOrder: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String?,
    val clearedAt: Long?,
    val deletedAt: Long?,
)

@Entity(tableName = "items", indices = [Index("listId")])
data class ItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val categoryId: String,
    val note: String?,
    val photoAt: Long?,
    val sortKey: Double,
    /** Schema v2 (STATE.md decision 67). */
    val manualKey: Double?,
    val checked: Boolean,
    val checkedAt: Long?,
    val checkedBy: String?,
    val createdAt: Long,
    val createdBy: String?,
    val updatedAt: Long,
    val updatedBy: String?,
    val deletedAt: Long?,
)

@Entity(tableName = "categories", primaryKeys = ["listId", "id"])
data class CategoryEntity(
    val listId: String,
    val id: String,
    val name: String,
    val builtin: Boolean,
    val updatedAt: Long,
    val updatedBy: String?,
    val deletedAt: Long?,
    val moveItemsTo: String?,
)

@Entity(tableName = "members", primaryKeys = ["listId", "uid"])
data class MemberEntity(
    val listId: String,
    val uid: String,
    val role: String,
    val since: Long,
    val name: String?,
    val email: String?,
    val photoUrl: String?,
)

/** An op waiting to be written to RTDB (`data/sync`). `seq` keeps the order they were made in. */
@Entity(tableName = "outbox_ops", indices = [Index(value = ["opId"], unique = true), Index("listId")])
data class OutboxOpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val listId: String,
    /** `Op.encode(op)`: the op as JSON. */
    val payload: String,
    val createdAt: Long,
)

/** What this device knows about a list's sync: nothing here ever leaves the device. */
@Entity(tableName = "list_sync")
data class ListSyncEntity(
    @PrimaryKey val listId: String,
    /** Server time of the newest remote change applied to Room. */
    val seenUpTo: Long,
    /** True once the list exists in RTDB. */
    val synced: Boolean,
    /** True while the outbox holds ops for this list. */
    val dirty: Boolean,
    /** When the 90-day expiry and the tombstone purge last ran (decision 42). */
    val sweptAt: Long,
)

/** Autocomplete history (decision 36): names only, so it survives the items' expiry. */
@Entity(tableName = "name_history")
data class NameHistoryEntity(
    /** `TextKey.fold(name)`. */
    @PrimaryKey val key: String,
    val name: String,
    val categoryId: String,
    val lastUsedAt: Long,
    @ColumnInfo(defaultValue = "1") val useCount: Int,
)

/**
 * „Moje produkty" (Phase 8b): words the user curated on purpose, with the department they
 * belong to. Per account and never learned automatically (decision 81). A deleted entry stays
 * as a tombstone, so the delete reaches the user's other phone (decision 88).
 */
@Entity(tableName = "own_product")
data class OwnProductEntity(
    /** `TextKey.fold(name)`. */
    @PrimaryKey val key: String,
    val name: String,
    /** Always one of the nine built-in departments (decision 89). */
    val categoryId: String,
    /** When this entry was last set on any of the user's phones; last-writer-wins by it. */
    val at: Long,
    val deletedAt: Long?,
)

fun ListEntity.toDomain() = ShoppingList(id, name, ownerUid, shared, categoryOrder, createdAt, updatedAt, updatedBy, clearedAt, deletedAt)

fun ShoppingList.toEntity() = ListEntity(id, name, ownerUid, shared, categoryOrder, createdAt, updatedAt, updatedBy, clearedAt, deletedAt)

fun ItemEntity.toDomain() = Item(
    id, listId, name, quantity, unit, categoryId, note, photoAt, sortKey, manualKey,
    checked, checkedAt, checkedBy, createdAt, createdBy, updatedAt, updatedBy, deletedAt,
)

fun Item.toEntity() = ItemEntity(
    id, listId, name, quantity, unit, categoryId, note, photoAt, sortKey, manualKey,
    checked, checkedAt, checkedBy, createdAt, createdBy, updatedAt, updatedBy, deletedAt,
)

fun CategoryEntity.toDomain() = Category(id, listId, name, builtin, updatedAt, updatedBy, deletedAt, moveItemsTo)

fun Category.toEntity() = CategoryEntity(listId, id, name, builtin, updatedAt, updatedBy, deletedAt, moveItemsTo)

fun MemberEntity.toDomain() = Member(
    listId, uid, Role.entries.firstOrNull { it.name == role } ?: Role.VIEWER, since, name, email, photoUrl,
)

fun Member.toEntity() = MemberEntity(listId, uid, role.name, since, name, email, photoUrl)
