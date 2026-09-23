package dev.gorny.buymyway.data

import androidx.room.withTransaction
import dev.gorny.buymyway.core.imports.ImportPlan
import dev.gorny.buymyway.core.imports.ImportedItem
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.ListDetail
import dev.gorny.buymyway.core.model.ListSummary
import dev.gorny.buymyway.core.model.ListViews
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.SortView
import dev.gorny.buymyway.core.model.ShoppingList
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.text.TextKey
import dev.gorny.buymyway.core.text.TextLimits
import dev.gorny.buymyway.data.local.AppDatabase
import dev.gorny.buymyway.data.local.ListSyncEntity
import dev.gorny.buymyway.data.local.NameHistoryEntity
import dev.gorny.buymyway.data.local.OutboxOpEntity
import dev.gorny.buymyway.data.local.OwnProductEntity
import dev.gorny.buymyway.data.local.toDomain
import dev.gorny.buymyway.data.local.toEntity
import dev.gorny.buymyway.data.prefs.CategoryOrderSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/** One of „Moje produkty": a name the user curated, with the department they gave it. */
data class OwnProduct(val key: String, val name: String, val categoryId: String)

/** A name the add bar can offer, with the category it was last filed under. */
data class NameSuggestion(val name: String, val categoryId: String)

/** What one import did, so the screen can say it in a sentence. */
data class ImportSummary(val added: Int, val summed: Int, val revived: Int) {
    val total: Int get() = added + summed
}

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

    /** A change to a list shared with this user as a viewer (PLAN.md *Sharing & permissions*). */
    class ReadOnlyList(listId: String) : IllegalStateException("list $listId is read-only here")

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
    fun observeList(
        listId: String,
        lingering: Flow<Set<String>> = flowOf(emptySet()),
        view: Flow<SortView> = flowOf(SortView.DEPARTMENTS),
    ): Flow<ListDetail?> = combine(
        db.lists().observe(listId),
        db.categories().observeForList(listId),
        db.items().observeForList(listId),
        lingering,
        view,
    ) { list, categories, items, inPlace, sortView ->
        list?.toDomain()
            ?.takeIf { it.deletedAt == null && it.updatedAt > 0 }
            ?.let { ListViews.detail(it, categories.map { c -> c.toDomain() }, items.map { i -> i.toDomain() }, inPlace, sortView) }
    }.distinctUntilChanged()

    /** Every known, undeleted item of a list, ticked or not: what the screen diffs for remote ticks. */
    fun observeItems(listId: String): Flow<List<Item>> =
        db.items().observeForList(listId).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /**
     * The lists an import may be poured into: the ones this user owns, and the shared ones
     * where they edit (PLAN.md Phase 8, task 3). A viewer's lists are not offered at all,
     * rather than refused after the fact.
     */
    fun observeEditableLists(): Flow<List<ListSummary>> =
        combine(observeLists(), observeAllMembers()) { lists, members ->
            val me = actor()
            lists.filter { summary ->
                val list = summary.list
                list.ownerUid == null || list.ownerUid == me ||
                    members.any { it.listId == list.id && it.uid == me && it.role == Role.EDITOR }
            }
        }.distinctUntilChanged()

    /** Everyone the lists on this phone are shared with. */
    fun observeAllMembers(): Flow<List<Member>> =
        db.members().observeAll().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /** The edit sheet. */
    fun observeItem(itemId: String): Flow<Item?> = db.items().observe(itemId)
        .map { it?.toDomain()?.takeIf { item -> item.updatedAt > 0 && item.deletedAt == null } }
        .distinctUntilChanged()

    /** Udostępnianie (filled from Phase 5). */
    fun observeMembers(listId: String): Flow<List<Member>> =
        db.members().observeForList(listId).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /** The members as last read, for the push sender (Phase 9) and anything else off a flow. */
    suspend fun membersOf(listId: String): List<Member> = db.members().getForList(listId).map { it.toDomain() }

    /** Autocomplete for the add bar. */
    fun observeSuggestions(typed: String, limit: Int = 8): Flow<List<NameSuggestion>> =
        db.nameHistory().observeMatching(TextKey.fold(typed), limit)
            .map { rows -> rows.map { NameSuggestion(it.name, it.categoryId) } }
            .distinctUntilChanged()

    /** Whether the list is in RTDB (uploaded, or read from there). */
    fun observeSynced(listId: String): Flow<Boolean> =
        db.listSync().observe(listId).map { it?.synced == true }.distinctUntilChanged()

    /** The server time of the newest remote change applied to this list. */
    suspend fun seenUpTo(listId: String): Long = db.listSync().get(listId)?.seenUpTo ?: 0

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
        /** Added in the „Ręcznie" view: it goes to the end of that order (decision 67). */
        placeLast: Boolean = false,
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
            manualKey = if (placeLast) ListViews.nextManualKey(items.filter { Merge.isVisible(it, list) }) else null,
        )
        commit(listOf(Op.ItemPut(newId(), listId, actor(), at, itemId, content)))
        remember(itemName, category, at)
        AddResult.Added(itemId)
    }

    /**
     * A whole shared text into one list, as one batch of ops (PLAN.md Phase 8, task 3). What it
     * does is decided by [ImportPlan] first: a line that names something the list already holds,
     * in the same unit, grows that item's quantity instead of adding a second row, and one
     * sitting in „Kupione" comes back (decision 36). A heading the list has no category for
     * becomes one; a line that had no heading is categorised as a typed one would be.
     */
    suspend fun importItems(listId: String, items: List<ImportedItem>): ImportSummary = db.withTransaction {
        val list = liveList(listId)
        val existing = db.items().getAllForList(listId).map { it.toDomain() }
        val plan = ImportPlan.of(items, list, existing)
        if (plan.isEmpty) return@withTransaction ImportSummary(0, 0, 0)

        val at = nextAt()
        val uid = actor()
        val ops = mutableListOf<Op>()

        // Headings this list has no category for. They are created once, and the list's walk
        // order gains them all in a single put, so the batch stays one write per node.
        val byName = db.categories().getAllForList(listId).map { it.toDomain() }
            .filter { Merge.isVisible(it) }
            .associateBy { TextKey.fold(it.name) }
        val made = mutableMapOf<String, String>()
        fun categoryFor(name: String): String {
            val key = TextKey.fold(name)
            byName[key]?.let { return it.id }
            return made.getOrPut(key) {
                val id = newId()
                ops += Op.CategoryPut(newId(), listId, uid, at, id, cleanName(name, TextLimits.CATEGORY_NAME), builtin = false)
                id
            }
        }

        val sortKeys = mutableMapOf<String, Double>()
        val added = plan.added.map { line ->
            val name = cleanName(line.name, TextLimits.ITEM_NAME)
            val category = when {
                line.categoryId != null && BuiltinCategories.isBuiltin(line.categoryId) -> line.categoryId
                line.categoryName != null -> categoryFor(line.categoryName)
                else -> proposeCategory(listId, name)
            }
            val sortKey = sortKeys.getOrPut(category) { ListViews.nextSortKey(list, existing, category) }
            sortKeys[category] = sortKey + 1.0
            ops += Op.ItemPut(
                newId(), listId, uid, at, newId(),
                ItemContent(
                    name = name,
                    quantity = line.quantity,
                    unit = cleanOptional(line.unit, TextLimits.UNIT),
                    categoryId = category,
                    sortKey = sortKey,
                ),
            )
            name to category
        }
        if (made.isNotEmpty()) {
            ops += Op.ListPut(newId(), listId, uid, at, list.name, list.categoryOrder + made.values)
        }

        for (grown in plan.summed) {
            val item = existing.first { it.id == grown.itemId }
            ops += Op.ItemPut(newId(), listId, uid, at, item.id, item.content.copy(quantity = grown.quantity))
            if (grown.revive) ops += Op.ItemCheck(newId(), listId, uid, at, item.id, checked = false)
        }

        commit(ops)
        added.forEach { (name, category) -> remember(name, category, at) }
        ImportSummary(
            added = plan.added.size,
            summed = plan.summed.size,
            revived = plan.summed.count { it.revive },
        )
    }

    /**
     * The edit sheet's „Zapisz". The photo is not the sheet's to write: the stored `photoAt` is
     * kept, so a sheet opened before an upload finished cannot put the old one back (decision 71).
     */
    suspend fun updateItem(itemId: String, content: ItemContent) = db.withTransaction {
        val item = liveItem(itemId)
        val cleaned = content.copy(
            name = cleanName(content.name, TextLimits.ITEM_NAME),
            unit = cleanOptional(content.unit, TextLimits.UNIT),
            note = cleanOptional(content.note, TextLimits.NOTE),
            photoAt = item.photoAt,
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

    /** A drag in the „Ręcznie" view: only the moved item's `manualKey` changes. */
    suspend fun moveItemManually(itemId: String, manualKey: Double) = db.withTransaction {
        val item = liveItem(itemId)
        if (item.manualKey == manualKey) return@withTransaction
        commit(listOf(Op.ItemPut(newId(), item.listId, actor(), nextAt(), itemId, item.content.copy(manualKey = manualKey))))
    }

    /** Places every item still to buy that has no „Ręcznie" position, after the placed ones. */
    suspend fun placeAllManually(listId: String) = db.withTransaction {
        if (!canEdit(listId)) return@withTransaction
        val state = loadState(listId)
        val list = state.list ?: return@withTransaction
        val shown = ListViews.detail(list, state.categories.values, state.items.values, view = SortView.MANUAL)
            .sections.flatMap { it.items }
        placeManually(listId, ListViews.placements(shown))
    }

    /** Places items that have no „Ręcznie" position yet (decision 67), one `item.put` each. */
    suspend fun placeManually(listId: String, keys: Map<String, Double>) = db.withTransaction {
        if (keys.isEmpty()) return@withTransaction
        val at = nextAt()
        val ops = keys.keys.sorted().mapNotNull { itemId ->
            val item = db.items().get(itemId)?.toDomain()?.takeIf { it.listId == listId && it.manualKey == null } ?: return@mapNotNull null
            Op.ItemPut(newId(), listId, actor(), at, itemId, item.content.copy(manualKey = keys.getValue(itemId)))
        }
        commit(ops)
    }

    /**
     * The item's photo is in RTDB now, as of [at] (decision 71): `PhotoWorker` calls this only
     * after `/photos` acknowledged the write. An older photo never replaces a newer one.
     */
    suspend fun setPhotoAt(itemId: String, at: Long) = db.withTransaction {
        val item = liveItem(itemId)
        if ((item.photoAt ?: Long.MIN_VALUE) >= at) return@withTransaction
        commit(listOf(Op.ItemPut(newId(), item.listId, actor(), nextAt(), itemId, item.content.copy(photoAt = at))))
    }

    /** „Usuń zdjęcie": the item names no photo from now on. Returns whether it named one. */
    suspend fun clearPhoto(itemId: String): Boolean = db.withTransaction {
        val item = liveItem(itemId)
        if (item.photoAt == null) return@withTransaction false
        commit(listOf(Op.ItemPut(newId(), item.listId, actor(), nextAt(), itemId, item.content.copy(photoAt = null))))
        true
    }

    /** An item still shown on its list (its content known, not deleted or cleared). */
    suspend fun isLive(itemId: String): Boolean {
        val item = db.items().get(itemId)?.toDomain() ?: return false
        return Merge.isVisible(item, db.lists().get(item.listId)?.toDomain())
    }

    suspend fun isSynced(listId: String): Boolean = db.listSync().get(listId)?.synced == true

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

        // Expiry is a change like any other, so a viewer leaves it to the others.
        val expired = if (canEdit(listId)) Merge.expiredItems(loadState(listId), now) else emptyList()
        if (expired.isNotEmpty()) {
            val at = nextAt()
            commit(expired.map { Op.ItemDelete(newId(), listId, actor(), at, it) })
        }

        // Decision 66: the owner's phone removes old nodes from RTDB first, during a catch-up,
        // and forgets them only then; purging here would leave them in RTDB for ever.
        val uid = actor()
        val ownedAndSynced = uid != null && db.lists().get(listId)?.ownerUid == uid && sync?.synced == true
        val purge = Merge.purgeable(loadState(listId), now)
        if (purge.wholeList && !ownedAndSynced) {
            forget(listId)
            return@withTransaction true
        }
        if (!ownedAndSynced) purgeLocal(listId, purge.itemIds, purge.categoryIds)
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

    /**
     * The members node as last read (decision 64): Room's copy is replaced, and the list is
     * shared exactly when it has one. Names already known are kept when a read has none.
     */
    suspend fun applyMembers(listId: String, members: List<Member>) = db.withTransaction {
        val known = db.members().getForList(listId).associateBy { it.uid }
        db.members().deleteForList(listId)
        for (member in members) {
            val old = known[member.uid]
            db.members().upsert(
                member.copy(
                    name = member.name ?: old?.name,
                    email = member.email ?: old?.email,
                    photoUrl = member.photoUrl ?: old?.photoUrl,
                ).toEntity(),
            )
        }
        db.lists().setShared(listId, members.isNotEmpty())
    }

    /** Members whose name this phone has not read yet. */
    suspend fun membersWithoutProfile(listId: String): List<String> =
        db.members().getForList(listId).filter { it.name == null && it.email == null }.map { it.uid }

    /** Nodes RTDB no longer has (removed after 30 days, decision 66), gone from the phone too. */
    suspend fun purgeLocal(listId: String, itemIds: List<String>, categoryIds: List<String>) = db.withTransaction {
        if (itemIds.isNotEmpty()) db.items().deleteByIds(itemIds)
        if (categoryIds.isNotEmpty()) db.categories().deleteByIds(listId, categoryIds)
    }

    /**
     * What the signed-in user may do with a list: a list without an owner (signed out) or
     * owned here is theirs; on a shared list, the members node says.
     */
    suspend fun roleOf(listId: String): Role? {
        val list = db.lists().get(listId) ?: return null
        val uid = actor()
        if (list.ownerUid == null || list.ownerUid == uid) return Role.OWNER
        return db.members().getForList(listId).firstOrNull { it.uid == uid }?.toDomain()?.role
    }

    suspend fun canEdit(listId: String): Boolean = roleOf(listId).let { it == Role.OWNER || it == Role.EDITOR }

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
        db.ownProducts().deleteAll()
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
            for (listId in ops.map { it.listId }.distinct()) {
                // A list made just now has no row yet; anything else must be editable here.
                if (db.lists().get(listId) != null && !canEdit(listId)) throw ReadOnlyList(listId)
            }
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

    // --- „Moje produkty" (Phase 8b) -------------------------------------------------------

    /** What Ustawienia → „Moje produkty" shows: the live entries, by name. */
    fun observeOwnProducts(): Flow<List<OwnProduct>> = db.ownProducts().observeAll()
        .map { rows -> rows.map { OwnProduct(it.key, it.name, it.categoryId) } }
        .distinctUntilChanged()

    /** The folded names the user curated: where dictation may also cut (PLAN.md task 4). */
    suspend fun ownProductKeys(): List<String> = db.ownProducts().all().map { it.key }

    /**
     * „Zapamiętaj", and the screen's add and edit: one name with the department it belongs to.
     * Renaming is a new key and a tombstone on the old one, because the key *is* the folded
     * name. Returns the key of the entry that now holds it.
     */
    suspend fun setOwnProduct(name: String, categoryId: String, replacing: String? = null): String {
        val cleaned = cleanName(name, TextLimits.ITEM_NAME)
        val key = TextKey.fold(cleaned)
        require(key.isNotEmpty()) { "a product name must hold a letter or a digit" }
        require(BuiltinCategories.isBuiltin(categoryId)) { "a product's department must be a built-in one" }
        val at = stamp(db.ownProducts().get(key)?.at)
        db.withTransaction {
            if (replacing != null && replacing != key) deleteOwnProduct(replacing)
            db.ownProducts().upsert(OwnProductEntity(key, cleaned, categoryId, at, deletedAt = null))
        }
        return key
    }

    /** A tombstone, so the delete reaches the user's other phone (decision 88). */
    suspend fun deleteOwnProduct(key: String) {
        val known = db.ownProducts().get(key) ?: return
        val at = stamp(known.at)
        db.ownProducts().upsert(known.copy(at = at, deletedAt = at))
    }

    /** „Cofnij" after a delete: the entry as it was, set again now. */
    suspend fun restoreOwnProduct(key: String) {
        val known = db.ownProducts().get(key) ?: return
        if (known.deletedAt == null) return
        val at = stamp(known.at)
        db.ownProducts().upsert(known.copy(at = at, deletedAt = null))
    }

    /** Later than what is stored, even if this phone's clock is not (as a stamped preference does). */
    private fun stamp(previous: Long?): Long = maxOf(clock(), (previous ?: 0) + 1)

    /**
     * The category the add bar proposes for [name]: the department the user gave this name in
     * „Moje produkty", else the one it was last filed under on this device if this list still
     * has that category, else the dictionary's guess (STATE.md decision 90).
     */
    suspend fun proposeCategory(listId: String, name: String): String =
        ownProductCategory(name) ?: rememberedCategory(listId, name) ?: categorize(name)

    /** A curated department is always one of the nine, so it is usable in every list (decision 89). */
    private suspend fun ownProductCategory(name: String): String? =
        db.ownProducts().get(TextKey.fold(name))
            ?.takeIf { it.deletedAt == null && BuiltinCategories.isBuiltin(it.categoryId) }
            ?.categoryId

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
