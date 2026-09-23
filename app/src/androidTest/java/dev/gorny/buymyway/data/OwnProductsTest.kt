package dev.gorny.buymyway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.categorize.NameIndex
import dev.gorny.buymyway.core.voice.Dictation
import dev.gorny.buymyway.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * „Moje produkty" in a real Room database (PLAN.md Phase 8b, tasks 1 and 4): what the screen
 * stores, what a delete leaves behind so it can travel (STATE.md decision 88), and the two
 * things one entry fixes — where dictation cuts, and which department an item lands in
 * (decision 90).
 */
@RunWith(AndroidJUnit4::class)
class OwnProductsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ListRepository
    private var now = 1_000_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ListRepository(
            db = db,
            categoryOrder = { emptyList() },
            // The dictionary knows bread and nothing else here.
            categorize = { name -> if (name.contains("chleb", ignoreCase = true)) "pieczywo" else "inne" },
            clock = { now },
        )
    }

    @After
    fun close() = db.close()

    private suspend fun shown() = repo.observeOwnProducts().first().map { it.name to it.categoryId }

    @Test
    fun aProductIsStoredUnderItsFoldedNameAndShownAsItWasTyped() = runTest {
        val key = repo.setOwnProduct("Chleb wiejski", "pieczywo")

        assertEquals("chleb wiejski", key)
        assertEquals(listOf("Chleb wiejski" to "pieczywo"), shown())
        // The same name in any spelling is the same entry, not a second row.
        now++
        repo.setOwnProduct("chleb  WIEJSKI ", "sypkie")
        assertEquals(listOf("chleb WIEJSKI" to "sypkie"), shown())
    }

    @Test
    fun aRenameReplacesTheEntryAndLeavesNoStrayRow() = runTest {
        val old = repo.setOwnProduct("Chleb wiejsky", "pieczywo")
        now++
        repo.setOwnProduct("Chleb wiejski", "pieczywo", replacing = old)

        assertEquals(listOf("Chleb wiejski" to "pieczywo"), shown())
        assertEquals(listOf("chleb wiejski"), repo.ownProductKeys())
        // The old key stays behind as a tombstone, so the other phone learns of the rename.
        assertNotNull(db.ownProducts().get(old))
        assertNotNull(db.ownProducts().get(old)?.deletedAt)
    }

    @Test
    fun aDeleteLeavesATombstoneWithANewerStamp() = runTest {
        val key = repo.setOwnProduct("Chleb wiejski", "pieczywo")
        val before = db.ownProducts().get(key)!!
        now += 10
        repo.deleteOwnProduct(key)

        val after = db.ownProducts().get(key)!!
        assertEquals(emptyList<Pair<String, String>>(), shown())
        assertEquals(emptyList<String>(), repo.ownProductKeys())
        assertTrue(after.at > before.at)
        assertEquals(after.at, after.deletedAt)
        // The name is kept, because the node that travels must still carry one.
        assertEquals("Chleb wiejski", after.name)
        // Adding it again brings it back rather than making a second row.
        now++
        repo.setOwnProduct("chleb wiejski", "sypkie")
        assertEquals(listOf("chleb wiejski" to "sypkie"), shown())
    }

    /** Two phones may share a clock that stands still; a stamp still moves forward. */
    @Test
    fun aStampNeverStandsStill() = runTest {
        val key = repo.setOwnProduct("Chleb wiejski", "pieczywo")
        val first = db.ownProducts().get(key)!!.at
        repo.setOwnProduct("Chleb wiejski", "sypkie")
        val second = db.ownProducts().get(key)!!.at
        repo.deleteOwnProduct(key)

        assertTrue(second > first)
        assertTrue(db.ownProducts().get(key)!!.at > second)
    }

    @Test
    fun onlyABuiltInDepartmentIsAccepted() = runTest {
        // A list's own category would mean nothing on another list (decision 89).
        val failed = runCatching { repo.setOwnProduct("Chleb wiejski", "apteka") }.exceptionOrNull()
        assertTrue(failed.toString(), failed is IllegalArgumentException)
        assertEquals(emptyList<Pair<String, String>>(), shown())

        assertTrue(runCatching { repo.setOwnProduct("   ", "inne") }.isFailure)
    }

    /** The phase's first acceptance criterion, on the department half. */
    @Test
    fun aCuratedDepartmentIsWhatTheAddBarProposes() = runTest {
        val listId = repo.createList("Sobota")
        assertEquals("inne", repo.proposeCategory(listId, "Kefir malinowy"))

        now++
        repo.setOwnProduct("Kefir malinowy", "nabial")
        assertEquals("nabial", repo.proposeCategory(listId, "kefir  malinowy"))

        // And it beats what the name happened to be filed under last (decision 90).
        now++
        repo.addItem(listId, "Kefir malinowy", categoryId = "napoje")
        assertEquals("nabial", repo.proposeCategory(listId, "Kefir malinowy"))

        // Once it is deleted, the proposal falls back to the memory and then the dictionary.
        now++
        repo.deleteOwnProduct("kefir malinowy")
        assertEquals("napoje", repo.proposeCategory(listId, "Kefir malinowy"))
        assertEquals("pieczywo", repo.proposeCategory(listId, "Chleb"))
    }

    /**
     * The same criterion on the cutting half, over the index the app actually builds from Room.
     * The bundled dictionary is left out here on purpose: these two names are only ever known
     * because the user curated them.
     */
    @Test
    fun dictationCutsAtACuratedName() = runTest {
        val spoken = "dropsy owsiane kefir malinowy"
        assertEquals(1, Dictation.parse(spoken, known()).size)

        repo.setOwnProduct("Dropsy owsiane", "inne")
        now++
        repo.setOwnProduct("Kefir malinowy", "nabial")
        assertEquals(
            listOf("dropsy owsiane", "kefir malinowy"),
            Dictation.parse(spoken, known()).map { it.name },
        )

        // A deleted entry stops cutting, as it stops proposing.
        now++
        repo.deleteOwnProduct("kefir malinowy")
        assertEquals(1, Dictation.parse(spoken, known()).size)
    }

    /** Nothing is learned on its own: the phase's second acceptance criterion. */
    @Test
    fun addingAndTickingItemsLeavesMojeProduktyUntouched() = runTest {
        val listId = repo.createList("Sobota")
        val itemId = repo.addItem(listId, "Chleb wiejski").itemId
        now++
        repo.setChecked(itemId, true)
        now++
        repo.addItem(listId, "Kefir malinowy", 2.0, "szt")

        assertEquals(emptyList<Pair<String, String>>(), shown())
        assertEquals(emptyList<String>(), repo.ownProductKeys())
        // The category memory did fill, and it is a different table.
        assertNotNull(db.nameHistory().get("chleb wiejski"))
    }

    @Test
    fun signingOutTakesTheProductsWithEverythingElse() = runTest {
        repo.setOwnProduct("Chleb wiejski", "pieczywo")
        repo.clearAll()

        assertNull(db.ownProducts().get("chleb wiejski"))
        assertEquals(emptyList<Pair<String, String>>(), shown())
    }

    /** As `AppContainer.knownNames` builds it: the curated names beside the dictionary's. */
    private suspend fun known(): Dictation.KnownNames {
        val curated = NameIndex.ofFolded(repo.ownProductKeys())
        return Dictation.KnownNames { words, from -> curated.lengthAt(words, from) }
    }
}
