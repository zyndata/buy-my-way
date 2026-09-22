package dev.gorny.buymyway.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** An item counts on a card while it is known, not deleted and not cleared from „Kupione". */
private const val LIVE_ITEM = """
    i.listId = l.id AND i.updatedAt > 0 AND i.deletedAt IS NULL
    AND NOT (i.checked AND l.clearedAt IS NOT NULL AND i.checkedAt <= l.clearedAt)
"""

data class ListSummaryRow(
    @Embedded val list: ListEntity,
    val total: Int,
    val checkedCount: Int,
)

@Dao
interface ListDao {
    /** The home screen: every live list, oldest first, with its „3 / 12". */
    @Query(
        """
        SELECT l.*,
            (SELECT COUNT(*) FROM items i WHERE $LIVE_ITEM) AS total,
            (SELECT COUNT(*) FROM items i WHERE $LIVE_ITEM AND i.checked) AS checkedCount
        FROM lists l
        WHERE l.deletedAt IS NULL AND l.updatedAt > 0
        ORDER BY l.createdAt, l.id
        """,
    )
    fun observeSummaries(): Flow<List<ListSummaryRow>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observe(id: String): Flow<ListEntity?>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun get(id: String): ListEntity?

    @Query("SELECT id FROM lists")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM lists")
    suspend fun getAll(): List<ListEntity>

    /** Lists made while signed out, which sign-in adopts (STATE.md decision 57). */
    @Query("SELECT id FROM lists WHERE ownerUid IS NULL ORDER BY createdAt, id")
    suspend fun ownerlessIds(): List<String>

    @Query("UPDATE lists SET ownerUid = :uid, updatedBy = COALESCE(updatedBy, :uid) WHERE id = :id")
    suspend fun adopt(id: String, uid: String)

    @Upsert
    suspend fun upsert(list: ListEntity)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM lists")
    suspend fun deleteAll()
}

@Dao
interface ItemDao {
    /** The list screen: known, undeleted items; the screen's view drops the cleared ones. */
    @Query("SELECT * FROM items WHERE listId = :listId AND updatedAt > 0 AND deletedAt IS NULL")
    fun observeForList(listId: String): Flow<List<ItemEntity>>

    /** The edit sheet. */
    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: String): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: String): ItemEntity?

    /** Every row of a list, tombstones included: what the merge and the sweep work on. */
    @Query("SELECT * FROM items WHERE listId = :listId")
    suspend fun getAllForList(listId: String): List<ItemEntity>

    @Upsert
    suspend fun upsert(item: ItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM items WHERE listId = :listId")
    suspend fun deleteForList(listId: String)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    /** Sign-in: what was done signed out is signed with the uid (decision 57). */
    @Query(
        """
        UPDATE items SET
            createdBy = CASE WHEN createdAt > 0 THEN COALESCE(createdBy, :uid) ELSE createdBy END,
            updatedBy = CASE WHEN updatedAt > 0 THEN COALESCE(updatedBy, :uid) ELSE updatedBy END,
            checkedBy = CASE WHEN checkedAt IS NOT NULL THEN COALESCE(checkedBy, :uid) ELSE checkedBy END
        WHERE listId = :listId
        """,
    )
    suspend fun stampActors(listId: String, uid: String)
}

@Dao
interface CategoryDao {
    /** Tombstones included: an item in a deleted category is shown where it was moved. */
    @Query("SELECT * FROM categories WHERE listId = :listId")
    fun observeForList(listId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE listId = :listId")
    suspend fun getAllForList(listId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE listId = :listId AND id = :id")
    suspend fun get(listId: String, id: String): CategoryEntity?

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE listId = :listId AND id IN (:ids)")
    suspend fun deleteByIds(listId: String, ids: List<String>)

    @Query("DELETE FROM categories WHERE listId = :listId")
    suspend fun deleteForList(listId: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("UPDATE categories SET updatedBy = COALESCE(updatedBy, :uid) WHERE listId = :listId AND updatedAt > 0")
    suspend fun stampActors(listId: String, uid: String)
}

@Dao
interface MemberDao {
    /** The sharing screen: owner first, then by name. */
    @Query(
        """
        SELECT * FROM members WHERE listId = :listId
        ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'EDITOR' THEN 1 ELSE 2 END, name, uid
        """,
    )
    fun observeForList(listId: String): Flow<List<MemberEntity>>

    @Upsert
    suspend fun upsert(member: MemberEntity)

    @Query("DELETE FROM members WHERE listId = :listId AND uid = :uid")
    suspend fun delete(listId: String, uid: String)

    @Query("DELETE FROM members WHERE listId = :listId")
    suspend fun deleteForList(listId: String)

    @Query("DELETE FROM members")
    suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: OutboxOpEntity): Long

    /** The oldest ops first, as they must be written. */
    @Query("SELECT * FROM outbox_ops ORDER BY seq LIMIT :limit")
    suspend fun oldest(limit: Int): List<OutboxOpEntity>

    @Query("SELECT COUNT(*) FROM outbox_ops")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_ops")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM outbox_ops WHERE listId = :listId")
    suspend fun countForList(listId: String): Int

    @Query("SELECT MAX(seq) FROM outbox_ops WHERE listId = :listId")
    suspend fun maxSeqForList(listId: String): Long?

    /** Called once RTDB has acknowledged the write, or the rules refused it (decision 56). */
    @Query("DELETE FROM outbox_ops WHERE opId IN (:opIds)")
    suspend fun delete(opIds: List<String>)

    @Query("DELETE FROM outbox_ops WHERE listId = :listId")
    suspend fun deleteForList(listId: String)

    /** After a whole-list upload: the ops it already carried. */
    @Query("DELETE FROM outbox_ops WHERE listId = :listId AND seq <= :seq")
    suspend fun deleteForListUpTo(listId: String, seq: Long)

    @Query("DELETE FROM outbox_ops")
    suspend fun deleteAll()
}

@Dao
interface ListSyncDao {
    @Query("SELECT * FROM list_sync WHERE listId = :listId")
    suspend fun get(listId: String): ListSyncEntity?

    @Query("SELECT * FROM list_sync WHERE listId = :listId")
    fun observe(listId: String): Flow<ListSyncEntity?>

    @Upsert
    suspend fun upsert(sync: ListSyncEntity)

    @Query("DELETE FROM list_sync WHERE listId = :listId")
    suspend fun delete(listId: String)

    @Query("DELETE FROM list_sync")
    suspend fun deleteAll()
}

@Dao
interface NameHistoryDao {
    /**
     * Autocomplete: names starting with [keyPrefix], most used first. The prefix is folded
     * (`TextKey.fold`), so it holds only letters, digits and spaces, nothing LIKE treats specially.
     */
    @Query(
        """
        SELECT * FROM name_history WHERE `key` LIKE :keyPrefix || '%'
        ORDER BY useCount DESC, lastUsedAt DESC LIMIT :limit
        """,
    )
    fun observeMatching(keyPrefix: String, limit: Int): Flow<List<NameHistoryEntity>>

    @Query("SELECT * FROM name_history WHERE `key` = :key")
    suspend fun get(key: String): NameHistoryEntity?

    /** Entries used after [at]: what the category memory has not sent yet (decision 59). */
    @Query("SELECT * FROM name_history WHERE lastUsedAt > :at ORDER BY lastUsedAt LIMIT :limit")
    suspend fun usedAfter(at: Long, limit: Int): List<NameHistoryEntity>

    @Upsert
    suspend fun upsert(entry: NameHistoryEntity)

    @Query("DELETE FROM name_history")
    suspend fun deleteAll()
}
