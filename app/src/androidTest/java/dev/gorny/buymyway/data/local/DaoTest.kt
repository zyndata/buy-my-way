package dev.gorny.buymyway.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Every DAO query a screen reads, against a real SQLite (STATE.md decision 33). */
@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun list(id: String, createdAt: Long = 1, clearedAt: Long? = null, deletedAt: Long? = null) = ListEntity(
        id, "Lista $id", null, false, BuiltinCategories.IDS, createdAt, createdAt, null, clearedAt, deletedAt,
    )

    private fun item(
        id: String,
        listId: String,
        checked: Boolean = false,
        checkedAt: Long? = null,
        updatedAt: Long = 1,
        deletedAt: Long? = null,
    ) = ItemEntity(
        id, listId, "Rzecz $id", null, null, "inne", null, null, 0.0,
        checked, checkedAt, null, 1, null, updatedAt, null, deletedAt,
    )

    @Test
    fun listSummariesCountOnlyLiveItemsAndSkipDeletedLists() = runTest {
        db.lists().upsert(list("a", createdAt = 2, clearedAt = 10))
        db.lists().upsert(list("b", createdAt = 1))
        db.lists().upsert(list("gone", deletedAt = 5))
        db.items().upsertAll(
            listOf(
                item("1", "a"),
                item("2", "a", checked = true, checkedAt = 20), // bought after the clear: counts
                item("3", "a", checked = true, checkedAt = 5), // cleared
                item("4", "a", deletedAt = 3), // deleted
                item("5", "a", updatedAt = 0), // content not arrived yet
                item("6", "b", checked = true, checkedAt = 1),
            ),
        )
        val rows = db.lists().observeSummaries().first()
        assertEquals(listOf("b", "a"), rows.map { it.list.id })
        assertEquals(listOf(1 to 1, 2 to 1), rows.map { it.total to it.checkedCount })
        assertEquals(BuiltinCategories.IDS, rows.first().list.categoryOrder)
    }

    @Test
    fun listAndItemQueriesForTheListScreenAndTheEditSheet() = runTest {
        db.lists().upsert(list("a"))
        db.items().upsertAll(listOf(item("1", "a"), item("2", "a", deletedAt = 2), item("3", "b")))
        assertEquals("Lista a", db.lists().observe("a").first()?.name)
        assertEquals(listOf("1"), db.items().observeForList("a").first().map { it.id })
        assertEquals("Rzecz 1", db.items().observe("1").first()?.name)
        assertEquals(setOf("1", "2"), db.items().getAllForList("a").map { it.id }.toSet())

        db.items().deleteByIds(listOf("1"))
        assertNull(db.items().observe("1").first())
        db.items().deleteForList("a")
        assertEquals(emptyList<ItemEntity>(), db.items().getAllForList("a"))
    }

    @Test
    fun categoriesKeepTombstonesForTheScreensResolution() = runTest {
        db.categories().upsert(CategoryEntity("a", "apteka", "Apteka", false, 1, null, null, null))
        db.categories().upsert(CategoryEntity("a", "stare", "Stare", false, 1, null, 2, "inne"))
        db.categories().upsert(CategoryEntity("b", "apteka", "Apteka B", false, 1, null, null, null))
        val rows = db.categories().observeForList("a").first()
        assertEquals(setOf("apteka", "stare"), rows.map { it.id }.toSet())
        assertEquals("inne", db.categories().get("a", "stare")?.moveItemsTo)
        db.categories().deleteByIds("a", listOf("stare"))
        assertEquals(listOf("apteka"), db.categories().getAllForList("a").map { it.id })
    }

    @Test
    fun membersAreListedOwnerFirst() = runTest {
        db.members().upsert(MemberEntity("a", "u3", Role.VIEWER.name, 3, "Celina", null, null))
        db.members().upsert(MemberEntity("a", "u2", Role.EDITOR.name, 2, "Bartek", null, null))
        db.members().upsert(MemberEntity("a", "u1", Role.OWNER.name, 1, "Zosia", null, null))
        db.members().upsert(MemberEntity("b", "u9", Role.OWNER.name, 1, "Inna", null, null))
        assertEquals(listOf("u1", "u2", "u3"), db.members().observeForList("a").first().map { it.uid })
        db.members().delete("a", "u2")
        assertEquals(listOf("u1", "u3"), db.members().observeForList("a").first().map { it.uid })
    }

    @Test
    fun theOutboxKeepsOrderAndIgnoresADuplicateOp() = runTest {
        db.outbox().insert(OutboxOpEntity(opId = "o1", listId = "a", payload = "{}", createdAt = 1))
        db.outbox().insert(OutboxOpEntity(opId = "o2", listId = "b", payload = "{}", createdAt = 2))
        assertEquals(-1L, db.outbox().insert(OutboxOpEntity(opId = "o1", listId = "a", payload = "{}", createdAt = 3)))
        db.outbox().insert(OutboxOpEntity(opId = "o3", listId = "a", payload = "{}", createdAt = 3))

        assertEquals(listOf("o1", "o2"), db.outbox().oldest(2).map { it.opId })
        assertEquals(3, db.outbox().observeCount().first())
        assertEquals(2, db.outbox().countForList("a"))
        db.outbox().delete(listOf("o1", "o2"))
        assertEquals(listOf("o3"), db.outbox().oldest(10).map { it.opId })
    }

    @Test
    fun listSyncIsReadAndReplaced() = runTest {
        assertNull(db.listSync().get("a"))
        db.listSync().upsert(ListSyncEntity("a", seenUpTo = 5, synced = true, dirty = true, sweptAt = 0))
        db.listSync().upsert(ListSyncEntity("a", seenUpTo = 7, synced = true, dirty = false, sweptAt = 3))
        assertEquals(ListSyncEntity("a", 7, true, false, 3), db.listSync().observe("a").first())
    }

    @Test
    fun nameHistoryMatchesByPrefixMostUsedFirst() = runTest {
        db.nameHistory().upsert(NameHistoryEntity("mleko", "Mleko", "nabial", 10, 5))
        db.nameHistory().upsert(NameHistoryEntity("mleko owsiane", "Mleko owsiane", "nabial", 20, 1))
        db.nameHistory().upsert(NameHistoryEntity("maslo", "Masło", "nabial", 30, 9))
        db.nameHistory().upsert(NameHistoryEntity("smietana", "Śmietana", "nabial", 30, 9))
        assertEquals(
            listOf("Mleko", "Mleko owsiane"),
            db.nameHistory().observeMatching("ml", 8).first().map { it.name },
        )
        assertEquals(listOf("Masło", "Mleko"), db.nameHistory().observeMatching("m", 2).first().map { it.name })
        assertEquals("Mleko owsiane", db.nameHistory().get("mleko owsiane")?.name)
    }
}
