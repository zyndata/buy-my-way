package dev.gorny.buymyway.data

import androidx.room.withTransaction
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.ListDetail
import dev.gorny.buymyway.core.model.ListSummary
import dev.gorny.buymyway.core.model.ListViews
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.model.ShoppingList
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.text.TextKey
import dev.gorny.buymyway.core.text.TextLimits
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.local.ListSyncEntity
import dev.gorny.buymyway.data.local.NameHistoryEntity
import dev.gorny.buymyway.data.local.OutboxOpEntity
import dev.gorny.buymyway.data.local.toDomain
import dev.gorny.buymyway.data.local.toEntity
import dev.gorny.buymyway.data.prefs.CategoryOrderSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/** A name the add bar can offer, with the category it was last filed under. */
data class NameSuggestion(val name: String, val categoryId: String)

/**
 * The one way the app changes its lists (PLAN.md Phase 2, task 3).
 *
 * Every mutation is an [Op]: merged into Room through the same [Merge] that merges remote
 * nodes, and put in the outbox, in one transaction, so a change is either on screen *and*
 * waiting to be sent, or neither. No network here: the sync layer (`data/sync`) empties the
 * outbox into RTDB and merges what it reads back through [applyRemote].
 * Screens read the `observe…` flows, which Room re-emits after every commit.
 */
class ListRepository(
    private val db: AppDatabase,
    private val categoryOrder: CategoryOrderSource,
    private val categorize: suspend (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** The Firebase uid; null while signed out (STATE.md decision 57). */
    private val actor: suspend () -> String? = { null },
) {
    sealed interface AddResult {
        val itemId: String

        data class Added(override val itemId: String) : AddResult

        /** The name was in „Kupione" and came back instead of being added twice. */
        data class Revived(override val itemId: String) : AddResult
    }

    private var lastAt = 0L

    /** Strictly increasing on this device, so two edits in one millisecond keep their order. */
    @Synchronized
    private fun nextAt(): Long {
        lastAt = maxOf(clock(), lastAt + 1)
        return lastAt
    }

    // --- Flows per screen -----------------------------------------------------------------

    /** Listy (home). */
    fun observeLists(): Flow<List<ListSummary>> = db.lists().observeSummaries()
        .map { rows -> rows.map { ListSummary(it.list.toDomain(), it.checkedCount, it.total) } }
        .distinctUntilChanged()

    /**
     * Lista. Null when the list does not exist or was deleted. [lingering] are items just
     * ticked that the screen still shows in place (see [ListViews.detail]).
     */
    fun observeList(listId: String, lingering: Flow<Set<String>> = flowOf(emptySet())): Flow<ListDetail?> = combine(
        db.lists().observe(listId),
        db.categories().observeForList(listId),
        db.items().observeForList(listId),
        lingering,
    ) { list, categories, items, inPlace ->
        list?.toDomain()
            ?.takeIf { it.deletedAt == null && it.updatedAt > 0 }
            ?.let { ListViews.detail(it, categories.map { c -> c.toDomain() }, items.map { i -> i.toDomain() }, inPlace) }
    }.distinctUntilChanged()

    /** The edit sheet. */
    fun observeItem(itemId: String): Flow<Item?> = db.items().observe(itemId)
        .map { it?.toDomain()?.takeIf { item -> item.updatedAt > 0 && item.deletedAt == null } }
        .distinctUntilChanged()

    /** Udostępnianie (filled from Phase 5). */
    fun observeMembers(listId: String): Flow<List<Member>> =
        db.members().observeForList(listId).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /** Autocomplete for the add bar. */
    fun observeSuggestions(typed: String, limit: Int = 8): Flow<List<NameSuggestion>> =
        db.nameHistory().observeMatching(TextKey.fold(typed), limit)
            .map { rows -> rows.map { NameSuggestion(it.name, it.categoryId) } }
            .distinctUntilChanged()

    /** Ops not yet written to RTDB. */
    fun observePendingOps(): Flow<Int> = db.outbox().observeCount()

    // --- Lists ----------------------------------------------------------------------------

    /** A new private list with the nine departments, in the user's preferred order. */
    suspend fun createList(name: String): String {
        val listName = cleanName(name, TextLimits.LIST_NAME)
        val order = BuiltinCategories.completeOrder(categoryOrder.defaultOrder())
        val listId = newId()
        val at = nextAt()
        val uid = actor()
        val ops = buildList {
            add(Op.ListPut(newId(), listId, uid, at, listName, order, ownerUid = uid))
            BuiltinCategories.ALL.forEach { (id, label) ->
                add(Op.CategoryPut(newId(), listId, uid, at, id, label, builtin = true))
            }
        }
        commit(ops)
        return listId
    }

    suspend fun renameList(listId: String, name: String) = db.withTransaction {
        val list = liveList(listId)
        commit(listOf(Op.ListPut(newId(), listId, actor(), nextAt(), cleanName(name, TextLimits.LIST_NAME), list.categoryOrder)))
    }

    suspend fun setCategoryOrder(listId: String, order: List<String>) = db.withTransaction {
        val list = liveList(listId)
        val custom = db.categories().getAllForList(listId).map { it.toDomain() }
            .filter { Merge.isVisible(it) && !it.builtin }.map { it.id }
        val complete = BuiltinCategories.completeOrder(order, custom)
        commit(listOf(Op.ListPut(newId(), listId, actor(), nextAt(), list.name, complete)))
    }

    suspend fun deleteList(listId: String) {
        commit(listOf(Op.ListDelete(newId(), listId, actor(), nextAt())))
    }

    // --- Items ----------------------------------------------------------------------------

    /**
     * Adds [name] to the list, or brings it back from „Kupione" if it is there (decision 36:
     * photo, category and quantity are kept). Without an explicit [categoryId] the category is
     * the one this name was last filed under on this device, or the dictionary's proposal.
     */
    suspend fun addItem(
        listId: String,
        name: String,
        quantity: Double? = null,
        unit: String? = null,
        categoryId: String? = null,
        note: String? = null,
    ): AddResult = db.withTransaction {
        val itemName = cleanName(name, TextLimits.ITEM_NAME)
        val list = liveList(listId)
        val items = db.items().getAllForList(listId).map { it.toDomain() }
        val at = nextAt()

        val revived = ListViews.revivable(list, items, itemName)
        if (revived != null) {
            commit(listOf(Op.ItemCheck(newId(), listId, actor(), at, revived.id, checked = false)))
            remember(revived.name, revived.categoryId, at)
            return@withTransaction AddResult.Revived(revived.id)
        }

        val category = categoryId ?: proposeCategory(listId, itemName)
        val itemId = newId()
        val content = ItemContent(
            name = itemName,
            quantity = quantity,
            unit = cleanOptional(unit, TextLimits.UNIT),
            categoryId = category,
            note = cleanOptional(note, TextLimits.NOTE),
            sortKey = ListViews.nextSortKey(list, items, category),
        )
        commit(listOf(Op.ItemPut(newId(), listId, actor(), at, itemId, content)))
        remember(itemName, category, at)
        AddResult.Added(itemId)
    }

    /** The edit sheet's „Zapisz". */
    suspend fun updateItem(itemId: String, content: ItemContent) = db.withTransaction {
        val item = liveItem(itemId)
        val cleaned = content.copy(
            name = cleanName(content.name, TextLimits.ITEM_NAME),
            unit = cleanOptional(content.unit, TextLimits.UNIT),
            note = cleanOptional(content.note, TextLimits.NOTE),
        )
        if (cleaned == item.content) return@withTransaction
        val at = nextAt()
        commit(listOf(Op.ItemPut(newId(), item.listId, actor(), at, itemId, cleaned)))
        remember(cleaned.name, cleaned.categoryId, at)
    }

    /** A drag within a category: only the moved item's `sortKey` changes, and history does not. */
    suspend fun moveItem(itemId: String, sortKey: Double) = db.withTransaction {
        val item = liveItem(itemId)
        if (item.sortKey == sortKey) return@withTransaction
        commit(listOf(Op.ItemPut(newId(), item.listId, actor(), nextAt(), itemId, item.content.copy(sortKey = sortKey))))
    }

    suspend fun setChecked(itemId: String, checked: Boolean) = db.withTransaction {
        val item = liveItem(itemId)
        if (item.checked == checked) return@withTransaction
        commit(listOf(Op.ItemCheck(newId(), item.listId, actor(), nextAt(), itemId, checked)))
    }

    /** „Zaznacz wszystko" / „Odznacz wszystko". */
    suspend fun setAllChecked(listId: String, checked: Boolean) = db.withTransaction {
        val list = liveList(listId)
        val at = nextAt()
        val ops = db.items().getAllForList(listId).map { it.toDomain() }
            .filter { Merge.isVisible(it, list) && it.checked != checked }
            .sortedBy { it.id }
            .map { Op.ItemCheck(newId(), listId, actor(), at, it.id, checked) }
        commit(ops)
    }

    suspend fun deleteItem(itemId: String) = db.withTransaction {
        val item = liveItem(itemId)
        commit(listOf(Op.ItemDelete(newId(), item.listId, actor(), nextAt(), itemId)))
    }

    /** „Wyczyść kupione". */
    suspend fun clearChecked(listId: String) = db.withTransaction {
        liveList(listId)
        commit(listOf(Op.ClearChecked(newId(), listId, actor(), nextAt())))
    }

    // --- Categories -----------------------------------------------------------------------

    /** A category of this list only, placed last in its walk order. */
    suspend fun addCategory(listId: String, name: String): String = db.withTransaction {
        val list = liveList(listId)
        val categoryId = newId()
        val at = nextAt()
        commit(
            listOf(
                Op.CategoryPut(newId(), listId, actor(), at, categoryId, cleanName(name, TextLimits.CATEGORY_NAME), builtin = false),
                Op.ListPut(newId(), listId, actor(), at, list.name, list.categoryOrder + categoryId),
            ),
        )
        categoryId
    }

    suspend fun renameCategory(listId: String, categoryId: String, name: String) = db.withTransaction {
        val category = db.categories().get(listId, categoryId)?.toDomain()?.takeIf { Merge.isVisible(it) }
            ?: throw NoSuchElementException("category $categoryId")
        commit(listOf(Op.CategoryPut(newId(), listId, actor(), nextAt(), categoryId, cleanName(name, TextLimits.CATEGORY_NAME), category.builtin)))
    }

    /**
     * Deletes a list's own category; its items are shown under [moveItemsTo] from then on
     * (decision 39). The nine departments stay: they are what an import maps onto.
     */
    suspend fun deleteCategory(listId: String, categoryId: String, moveItemsTo: String = BuiltinCategories.FALLBACK) =
        db.withTransaction {
            require(!BuiltinCategories.isBuiltin(categoryId)) { "a built-in category cannot be deleted" }
            require(moveItemsTo != categoryId) { "items cannot move into the category being deleted" }
            liveList(listId)
            commit(listOf(Op.CategoryDelete(newId(), listId, actor(), nextAt(), categoryId, moveItemsTo)))
        }

    // --- Lifetime (decision 36) -----------------------------------------------------------

    /**
     * Expires items bought 90 days ago and forgets tombstones older than 30 days, at most once
     * a day per list. Called when a list is opened (Phase 3), never in the background. Returns
     * whether it ran.
     */
    suspend fun sweep(listId: String): Boolean = db.withTransaction {
        val now = clock()
        val sync = db.listSync().get(listId)
        if (sync != null && sync.sweptAt > 0 && now - sync.sweptAt < Merge.DAY_MS) return@withTransaction false

        val expired = Merge.expiredItems(loadState(listId), now)
        if (expired.isNotEmpty()) {
            val at = nextAt()
            commit(expired.map { Op.ItemDelete(newId(), listId, actor(), at, it) })
        }

        val purge = Merge.purgeable(loadState(listId), now)
        if (purge.wholeList) {
            forget(listId)
            return@withTransaction true
        }
        if (purge.itemIds.isNotEmpty()) db.items().deleteByIds(purge.itemIds)
        if (purge.categoryIds.isNotEmpty()) db.categories().deleteByIds(listId, purge.categoryIds)
        val current = db.listSync().get(listId) ?: newSync(listId)
        db.listSync().upsert(current.copy(sweptAt = now))
        true
    }

    // --- Remote ---------------------------------------------------------------------------

    /**
     * Merges nodes read from RTDB into Room (the sync layer's entry). Nothing goes to the
     * outbox: these changes are already on the server. [serverTime] is the newest `changedAt`
     * the read covered; `seenUpTo` only moves forward.
     */
    suspend fun applyRemote(listId: String, remote: ListState, serverTime: Long?) = db.withTransaction {
        mergeIntoRoom(remote)
        val sync = db.listSync().get(listId) ?: newSync(listId)
        db.listSync().upsert(
            sync.copy(synced = true, seenUpTo = maxOf(sync.seenUpTo, serverTime ?: 0)),
        )
    }

    /**
     * Sign-in (decision 57): every list made while signed out gets [uid] as its owner and as
     * the author of everything done to it, so it can go to RTDB under the rules. Their queued
     * ops are dropped: the lists go up whole ([ListSyncEntity.synced] is still false), and that
     * upload carries everything the ops did. Returns the adopted list ids.
     */
    suspend fun adoptOwnerless(uid: String): List<String> = db.withTransaction {
        val ids = db.lists().ownerlessIds()
        for (listId in ids) {
            db.lists().adopt(listId, uid)
            db.categories().stampActors(listId, uid)
            db.items().stampActors(listId, uid)
            db.outbox().deleteForList(listId)
            val sync = db.listSync().get(listId) ?: newSync(listId)
            db.listSync().upsert(sync.copy(synced = false, dirty = false))
        }
        ids
    }

    /** The list is in RTDB now; [dirty] says whether the outbox still holds ops for it. */
    suspend fun markSynced(listId: String, dirty: Boolean) = db.withTransaction {
        val sync = db.listSync().get(listId) ?: newSync(listId)
        db.listSync().upsert(sync.copy(synced = true, dirty = dirty))
    }

    /** Removes every trace of a list from the phone: a purge, not a delete op. */
    suspend fun forget(listId: String) = db.withTransaction {
        db.items().deleteForList(listId)
        db.categories().deleteForList(listId)
        db.members().deleteForList(listId)
        db.outbox().deleteForList(listId)
        db.lists().delete(listId)
        db.listSync().delete(listId)
    }

    /** Sign-out: nothing of this account stays on the phone. RTDB keeps the lists. */
    suspend fun clearAll() = db.withTransaction {
        db.lists().deleteAll()
        db.items().deleteAll()
        db.categories().deleteAll()
        db.members().deleteAll()
        db.outbox().deleteAll()
        db.listSync().deleteAll()
        db.nameHistory().deleteAll()
    }

    /** The whole of one list as the merge sees it, tombstones included. */
    suspend fun loadState(listId: String): ListState = ListState(
        list = db.lists().get(listId)?.toDomain(),
        categories = db.categories().getAllForList(listId).associate { it.id to it.toDomain() },
        items = db.items().getAllForList(listId).associate { it.id to it.toDomain() },
    )

    // --- Internals ------------------------------------------------------------------------

    private suspend fun commit(ops: List<Op>) {
        if (ops.isEmpty()) return
        db.withTransaction {
            for (op in ops) {
                mergeIntoRoom(Merge.nodesOf(op))
                db.outbox().insert(OutboxOpEntity(opId = op.id, listId = op.listId, payload = Op.encode(op), createdAt = op.at))
            }
            for (listId in ops.map { it.listId }.distinct()) {
                val sync = db.listSync().get(listId) ?: newSync(listId)
                if (!sync.dirty) db.listSync().upsert(sync.copy(dirty = true))
            }
        }
    }

    private suspend fun mergeIntoRoom(state: ListState) {
        state.list?.let { remote ->
            db.lists().upsert(Merge.mergeRemote(db.lists().get(remote.id)?.toDomain(), remote).toEntity())
        }
        for (remote in state.categories.values) {
            val local = db.categories().get(remote.listId, remote.id)?.toDomain()
            db.categories().upsert(Merge.mergeRemote(local, remote).toEntity())
        }
        for (remote in state.items.values) {
            db.items().upsert(Merge.mergeRemote(db.items().get(remote.id)?.toDomain(), remote).toEntity())
        }
    }

    private suspend fun liveList(listId: String): ShoppingList =
        db.lists().get(listId)?.toDomain()?.takeIf { it.deletedAt == null && it.updatedAt > 0 }
            ?: throw NoSuchElementException("list $listId")

    private suspend fun liveItem(itemId: String): Item {
        val item = db.items().get(itemId)?.toDomain() ?: throw NoSuchElementException("item $itemId")
        val list = db.lists().get(item.listId)?.toDomain()
        if (!Merge.isVisible(item, list)) throw NoSuchElementException("item $itemId")
        return item
    }

    /**
     * The category the add bar proposes for [name]: the one this name was last filed under on
     * this device, if this list still has it, or the dictionary's guess.
     */
    suspend fun proposeCategory(listId: String, name: String): String =
        rememberedCategory(listId, name) ?: categorize(name)

    /** The user's last choice for this name, if that category is usable in this list. */
    private suspend fun rememberedCategory(listId: String, name: String): String? {
        val remembered = db.nameHistory().get(TextKey.fold(name))?.categoryId ?: return null
        if (BuiltinCategories.isBuiltin(remembered)) return remembered
        val category = db.categories().get(listId, remembered)?.toDomain()
        return remembered.takeIf { category != null && Merge.isVisible(category) }
    }

    private suspend fun remember(name: String, categoryId: String, at: Long) {
        val key = TextKey.fold(name)
        if (key.isEmpty()) return
        val known = db.nameHistory().get(key)
        db.nameHistory().upsert(
            NameHistoryEntity(key, name, categoryId, at, (known?.useCount ?: 0) + 1),
        )
    }

    private fun newSync(listId: String) = ListSyncEntity(listId, seenUpTo = 0, synced = false, dirty = false, sweptAt = 0)

    private fun cleanName(name: String, limit: Int): String {
        val cleaned = name.trim().replace(Regex("\\s+"), " ").take(limit).trimEnd()
        require(cleaned.isNotEmpty()) { "a name cannot be empty" }
        return cleaned
    }

    private fun cleanOptional(text: String?, limit: Int): String? = text?.trim()?.take(limit)?.trimEnd()?.ifEmpty { null }
}
