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

    @Upsert
    suspend fun upsert(list: ListEntity)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun delete(id: String)
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

    @Query("SELECT COUNT(*) FROM outbox_ops WHERE listId = :listId")
    suspend fun countForList(listId: String): Int

    /** Called once RTDB has acknowledged the write (Phase 5). */
    @Query("DELETE FROM outbox_ops WHERE opId IN (:opIds)")
    suspend fun delete(opIds: List<String>)
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

    @Upsert
    suspend fun upsert(entry: NameHistoryEntity)
}
