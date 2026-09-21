package dev.gorny.buymyway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.sync.ListState
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private var now = 1_000_000L
    private var ids = 0
    private var preferredOrder = listOf("napoje", "nabial")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ListRepository(
            db = db,
            categoryOrder = { preferredOrder },
            categorize = { name -> if (name.contains("mleko", ignoreCase = true)) "nabial" else "inne" },
            clock = { now },
            newId = { "id-${ids++}" },
        )
    }

    @After
    fun close() = db.close()

    private suspend fun outboxOps(): List<Op> = db.outbox().oldest(Int.MAX_VALUE).map { Op.decode(it.payload) }

    /** Room must hold exactly what the pure merge makes of the outbox. */
    private suspend fun assertRoomIsTheMergeOfTheOutbox(listId: String) {
        val expected = Merge.apply(outboxOps().filter { it.listId == listId }, ListState())
        assertEquals(expected, repo.loadState(listId))
    }

    @Test
    fun aNewListHasTheNineDepartmentsInThePreferredOrder() = runTest {
        val listId = repo.createList("  Sobota  ")
        val detail = repo.observeList(listId).first()!!
        assertEquals("Sobota", detail.list.name)
        assertEquals(listOf("napoje", "nabial", "warzywa"), detail.categories.take(3).map { it.id })
        assertEquals(BuiltinCategories.IDS.toSet(), detail.categories.map { it.id }.toSet())
        assertEquals(10, outboxOps().size) // list.put + nine category.put
        assertTrue(db.listSync().get(listId)!!.dirty)
        assertRoomIsTheMergeOfTheOutbox(listId)
    }

    @Test
    fun everyMutationIsAnOpAppliedToRoomAndQueuedInOneStep() = runTest {
        val listId = repo.createList("Sobota")
        val milk = repo.addItem(listId, "Mleko", quantity = 2.0, unit = "l").itemId
        val bread = repo.addItem(listId, "Chleb").itemId
        repo.updateItem(bread, ItemContent("Chleb żytni", categoryId = "pieczywo", note = "krojony"))
        repo.setChecked(milk, true)
        val shop = repo.addCategory(listId, "Drogeria")
        repo.addItem(listId, "Szampon", categoryId = shop)
        repo.renameCategory(listId, shop, "Rossmann")
        repo.deleteCategory(listId, shop, moveItemsTo = "inne")
        repo.renameList(listId, "Niedziela")
        repo.setCategoryOrder(listId, listOf("pieczywo"))
        repo.setAllChecked(listId, true)
        repo.clearChecked(listId)
        repo.deleteItem(repo.addItem(listId, "Sól").itemId)

        assertRoomIsTheMergeOfTheOutbox(listId)
        assertEquals(outboxOps().size, db.outbox().observeCount().first())
        assertEquals(outboxOps().map { it.id }.distinct().size, outboxOps().size)
        val detail = repo.observeList(listId).first()!!
        assertEquals("Niedziela", detail.list.name)
        assertEquals("pieczywo", detail.list.categoryOrder.first())
        assertEquals(0, detail.total) // everything was bought and cleared, the salt deleted
    }

    @Test
    fun addingPicksTheRememberedCategoryThenTheDictionary() = runTest {
        val listId = repo.createList("Sobota")
        val first = repo.addItem(listId, "Mleko owsiane").itemId
        assertEquals("nabial", repo.observeItem(first).first()!!.categoryId)

        repo.updateItem(first, ItemContent("Mleko owsiane", categoryId = "napoje", sortKey = 1.0))
        repo.deleteItem(first)
        val second = repo.addItem(listId, "mleko OWSIANE").itemId
        assertEquals("napoje", repo.observeItem(second).first()!!.categoryId)
        assertEquals(listOf(NameSuggestion("mleko OWSIANE", "napoje")), repo.observeSuggestions("mleko o").first())
    }

    @Test
    fun addingANameFromKupioneBringsItBack() = runTest {
        val listId = repo.createList("Sobota")
        val butter = repo.addItem(listId, "Masło", quantity = 2.0, unit = "szt.", categoryId = "nabial").itemId
        repo.setChecked(butter, true)

        val result = repo.addItem(listId, "  masło ", quantity = 5.0)
        assertEquals(ListRepository.AddResult.Revived(butter), result)
        val item = repo.observeItem(butter).first()!!
        assertFalse(item.checked)
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals(1, repo.observeList(listId).first()!!.total)
        assertRoomIsTheMergeOfTheOutbox(listId)
    }

    @Test
    fun theHomeScreenCountsBoughtAndAll() = runTest {
        val a = repo.createList("A")
        now += 1
        repo.createList("B")
        repo.setChecked(repo.addItem(a, "Mleko").itemId, true)
        repo.addItem(a, "Chleb")
        val summaries = repo.observeLists().first()
        assertEquals(listOf("A", "B"), summaries.map { it.list.name })
        assertEquals(1 to 2, summaries.first().checked to summaries.first().total)

        repo.deleteList(a)
        assertEquals(listOf("B"), repo.observeLists().first().map { it.list.name })
        assertNull(repo.observeList(a).first())
    }

    @Test
    fun theBuiltInDepartmentsCannotBeDeleted() = runTest {
        val listId = repo.createList("Sobota")
        try {
            repo.deleteCategory(listId, "nabial")
            fail("deleted a built-in category")
        } catch (expected: IllegalArgumentException) {
            assertEquals(10, outboxOps().size)
        }
    }

    @Test
    fun theSweepExpiresOldPurchasesPurgesOldTombstonesAndRunsOnceADay() = runTest {
        val listId = repo.createList("Sobota")
        val flour = repo.addItem(listId, "Mąka").itemId
        repo.setChecked(flour, true)
        val salt = repo.addItem(listId, "Sól").itemId
        repo.deleteItem(salt)

        now += 91 * Merge.DAY_MS
        assertTrue(repo.sweep(listId))
        assertNull(repo.observeItem(flour).first()) // expired: an ordinary delete, queued
        assertTrue(outboxOps().any { it is Op.ItemDelete && it.itemId == flour })
        assertNull(db.items().get(salt)) // its tombstone was older than 30 days: gone
        assertFalse(repo.sweep(listId)) // not again the same day

        now += Merge.DAY_MS
        assertTrue(repo.sweep(listId))
    }

    @Test
    fun aDeletedListIsForgottenThirtyDaysLater() = runTest {
        val listId = repo.createList("Stara")
        repo.addItem(listId, "Mleko")
        repo.deleteList(listId)
        now += 31 * Merge.DAY_MS
        assertTrue(repo.sweep(listId))
        assertNull(db.lists().get(listId))
        assertEquals(emptyList<Any>(), db.items().getAllForList(listId))
        assertEquals(emptyList<Any>(), db.categories().getAllForList(listId))
    }

    @Test
    fun remoteNodesAreMergedWithoutTouchingTheOutbox() = runTest {
        val listId = repo.createList("Sobota")
        val milk = repo.addItem(listId, "Mleko").itemId
        val queued = outboxOps().size
        val local = repo.loadState(listId).items.getValue(milk)

        val remoteTick = local.copy(checked = true, checkedAt = now + 5, checkedBy = "uid-bartek")
        val remoteNew = Merge.blankItem("remote-item", listId)
            .copy(name = "Kawa", categoryId = "napoje", createdAt = now + 6, updatedAt = now + 6, updatedBy = "uid-bartek")
        repo.applyRemote(listId, ListState(items = mapOf(milk to remoteTick, remoteNew.id to remoteNew)), serverTime = 500)
        repo.applyRemote(listId, ListState(), serverTime = 400) // an older read never moves it back

        assertEquals(queued, outboxOps().size)
        val detail = repo.observeList(listId).first()!!
        assertEquals(listOf("Mleko"), detail.bought.map { it.name })
        assertEquals(listOf("Kawa"), detail.sections.flatMap { it.items }.map { it.name })
        val sync = db.listSync().get(listId)!!
        assertEquals(500L, sync.seenUpTo)
        assertTrue(sync.synced)
    }
}
