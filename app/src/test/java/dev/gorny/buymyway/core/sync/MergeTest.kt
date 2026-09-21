package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property-style tests of the merge (PLAN.md Phase 2, task 2). Each scenario draws random op
 * sets from a small space (few items, few actors, few distinct timestamps, so collisions and
 * ties are common) and checks a law over at least [ORDERS] random orders.
 */
class MergeTest {

    private companion object {
        const val ORDERS = 1000
        const val LIST = "list-1"
        val ITEMS = listOf("item-a", "item-b", "item-c")
        val ACTORS = listOf(null, "uid-ania", "uid-bartek")
        val CUSTOM = "cat-custom"
    }

    private var opCounter = 0

    private fun id() = "op-${opCounter++}"

    private fun Random.actor() = ACTORS.random(this)

    private fun Random.at() = nextLong(1, 12)

    private fun Random.content() = ItemContent(
        name = listOf("Mleko", "Chleb", "Jajka").random(this),
        quantity = listOf(null, 1.0, 2.5).random(this),
        unit = listOf(null, "kg", "szt.").random(this),
        categoryId = listOf("nabial", "pieczywo", CUSTOM, "inne").random(this),
        note = listOf(null, "bez laktozy").random(this),
        sortKey = nextInt(0, 3).toDouble(),
    )

    private fun Random.op(): Op {
        val item = ITEMS.random(this)
        return when (nextInt(10)) {
            0, 1, 2 -> Op.ItemPut(id(), LIST, actor(), at(), item, content())
            3, 4 -> Op.ItemCheck(id(), LIST, actor(), at(), item, nextBoolean())
            5 -> Op.ItemDelete(id(), LIST, actor(), at(), item)
            6 -> Op.ListPut(id(), LIST, actor(), at(), listOf("Zakupy", "Sobota").random(this),
                BuiltinCategories.IDS.shuffled(this).take(3))
            7 -> Op.ClearChecked(id(), LIST, actor(), at())
            8 -> Op.CategoryPut(id(), LIST, actor(), at(), CUSTOM, listOf("Apteka", "Drogeria").random(this), false)
            else -> Op.CategoryDelete(id(), LIST, actor(), at(), CUSTOM, listOf("inne", "nabial").random(this))
        }
    }

    private fun Random.ops(n: Int) = List(n) { op() }

    private fun applyAll(ops: List<Op>, start: ListState = ListState()) = Merge.apply(ops, start)

    /** What a screen would show, which must converge too, not only the raw state. */
    private fun ListState.visibleItems() = items.values.filter { Merge.isVisible(it, list) }.sortedBy { it.id }

    // --- Laws over random op sets -------------------------------------------------------------

    @Test
    fun anyOrderOfTheSameOpsGivesTheSameState() {
        val random = Random(1)
        repeat(20) {
            val ops = random.ops(25)
            val expected = applyAll(ops)
            repeat(ORDERS) {
                val state = applyAll(ops.shuffled(random))
                assertEquals(expected, state)
                assertEquals(expected.visibleItems(), state.visibleItems())
            }
        }
    }

    @Test
    fun applyingOpsAgainChangesNothing() {
        val random = Random(2)
        repeat(20) {
            val ops = random.ops(20)
            val expected = applyAll(ops)
            repeat(ORDERS) {
                // Every op at least once, some many times, in any order.
                val repeated = (ops + ops.filter { random.nextBoolean() } + ops.shuffled(random).take(5)).shuffled(random)
                assertEquals(expected, applyAll(repeated))
            }
            assertEquals(expected, applyAll(ops, expected))
        }
    }

    @Test
    fun mergeIsAssociativeCommutativeAndIdempotentOnStates() {
        val random = Random(3)
        repeat(ORDERS) {
            val a = applyAll(random.ops(6))
            val b = applyAll(random.ops(6))
            val c = applyAll(random.ops(6))
            assertEquals(Merge.merge(a, b), Merge.merge(b, a))
            assertEquals(Merge.merge(Merge.merge(a, b), c), Merge.merge(a, Merge.merge(b, c)))
            assertEquals(a, Merge.merge(a, a))
        }
    }

    // --- Tombstones ---------------------------------------------------------------------------

    @Test
    fun aDeletedItemStaysDeletedWhateverArrivesAfterIt() {
        val random = Random(4)
        repeat(ORDERS) {
            val delete = Op.ItemDelete(id(), LIST, random.actor(), random.at(), "item-a")
            val others = random.ops(15)
            val state = applyAll((others + delete).shuffled(random))
            val item = state.items.getValue("item-a")
            assertNotNull(item.deletedAt)
            assertTrue(Merge.isGone(item, state.list))
            assertFalse(Merge.isVisible(item, state.list))
        }
    }

    @Test
    fun aNewerPutDoesNotBringADeletedItemBack() {
        val put = Op.ItemPut(id(), LIST, "uid-ania", 1, "item-a", ItemContent("Mleko"))
        val delete = Op.ItemDelete(id(), LIST, "uid-bartek", 2, "item-a")
        val laterEdit = Op.ItemPut(id(), LIST, "uid-ania", 3, "item-a", ItemContent("Mleko 2%"))
        val random = Random(5)
        repeat(ORDERS) {
            val state = applyAll(listOf(put, delete, laterEdit).shuffled(random))
            val item = state.items.getValue("item-a")
            assertEquals(2L, item.deletedAt)
            assertFalse(Merge.isVisible(item, state.list))
        }
    }

    // --- The tick and the content are independent --------------------------------------------

    @Test
    fun aTickRacingANoteEditKeepsBoth() {
        val create = Op.ItemPut(id(), LIST, "uid-ania", 1, "item-a", ItemContent("Mleko", categoryId = "nabial"))
        val tick = Op.ItemCheck(id(), LIST, "uid-bartek", 5, "item-a", true)
        val note = Op.ItemPut(id(), LIST, "uid-ania", 4, "item-a", ItemContent("Mleko", categoryId = "nabial", note = "2%"))
        val random = Random(6)
        repeat(ORDERS) {
            val item = applyAll(listOf(create, tick, note).shuffled(random)).items.getValue("item-a")
            assertEquals("2%", item.note)
            assertTrue(item.checked)
            assertEquals("uid-bartek", item.checkedBy)
            assertEquals(4L, item.updatedAt)
            assertEquals(1L, item.createdAt)
        }
    }

    @Test
    fun theLaterTickWinsAndTheLaterContentWinsEachOnItsOwn() {
        val random = Random(7)
        repeat(ORDERS) {
            val ticks = List(4) { Op.ItemCheck(id(), LIST, random.actor(), random.at(), "item-a", random.nextBoolean()) }
            val puts = List(4) { Op.ItemPut(id(), LIST, random.actor(), random.at(), "item-a", random.content()) }
            val item = applyAll((ticks + puts).shuffled(random)).items.getValue("item-a")
            val lastTick = ticks.maxWith(compareBy<Op.ItemCheck>({ it.at }, { it.actor.orEmpty() }, { it.checked }))
            val lastPut = puts.maxWith(compareBy<Op.ItemPut>({ it.at }, { it.actor.orEmpty() }, { it.content.toString() }))
            assertEquals(lastTick.checked, item.checked)
            assertEquals(lastTick.at, item.checkedAt)
            assertEquals(lastPut.content, item.content)
            assertEquals(lastPut.at, item.updatedAt)
            assertEquals(puts.minOf { it.at }, item.createdAt)
        }
    }

    @Test
    fun aTickThatArrivesBeforeTheItemIsKeptButNotShown() {
        val tick = Op.ItemCheck(id(), LIST, "uid-bartek", 5, "item-a", true)
        val early = applyAll(listOf(tick))
        assertFalse(Merge.isVisible(early.items.getValue("item-a"), early.list))

        val put = Op.ItemPut(id(), LIST, "uid-ania", 3, "item-a", ItemContent("Chleb"))
        val item = applyAll(listOf(put), early).items.getValue("item-a")
        assertTrue(item.checked)
        assertEquals("Chleb", item.name)
    }

    // --- „Wyczyść kupione" -------------------------------------------------------------------

    @Test
    fun clearCheckedRemovesWhatWasTickedBeforeItInAnyOrder() {
        val random = Random(8)
        val setup = ITEMS.mapIndexed { i, item -> Op.ItemPut(id(), LIST, "uid-ania", 1L + i, item, ItemContent(item)) }
        val tickA = Op.ItemCheck(id(), LIST, "uid-ania", 5, "item-a", true)
        val tickB = Op.ItemCheck(id(), LIST, "uid-bartek", 6, "item-b", true) // arrives late on some devices
        val clear = Op.ClearChecked(id(), LIST, "uid-ania", 7)
        val tickCAfter = Op.ItemCheck(id(), LIST, "uid-bartek", 8, "item-c", true)
        repeat(ORDERS) {
            val state = applyAll((setup + listOf(tickA, tickB, clear, tickCAfter)).shuffled(random))
            assertEquals(listOf("item-c"), state.visibleItems().map { it.id })
        }
    }

    @Test
    fun anItemUntickedAfterTheClearComesBack() {
        val put = Op.ItemPut(id(), LIST, "uid-ania", 1, "item-a", ItemContent("Masło"))
        val tick = Op.ItemCheck(id(), LIST, "uid-ania", 2, "item-a", true)
        val clear = Op.ClearChecked(id(), LIST, "uid-ania", 3)
        val untick = Op.ItemCheck(id(), LIST, "uid-bartek", 4, "item-a", false)
        val random = Random(9)
        repeat(ORDERS) {
            val state = applyAll(listOf(put, tick, clear, untick).shuffled(random))
            assertEquals(listOf("item-a"), state.visibleItems().map { it.id })
        }
    }

    // --- Categories ---------------------------------------------------------------------------

    @Test
    fun itemsOfADeletedCategoryAreShownWhereTheyWereMoved() {
        val random = Random(10)
        val create = Op.CategoryPut(id(), LIST, "uid-ania", 1, CUSTOM, "Apteka", false)
        val lateItem = Op.ItemPut(id(), LIST, "uid-bartek", 9, "item-a", ItemContent("Plastry", categoryId = CUSTOM))
        val delete = Op.CategoryDelete(id(), LIST, "uid-ania", 5, CUSTOM, "nabial")
        repeat(ORDERS) {
            val state = applyAll(listOf(create, lateItem, delete).shuffled(random))
            val item = state.items.getValue("item-a")
            assertEquals(CUSTOM, item.categoryId)
            assertEquals("nabial", Merge.resolveCategory(item.categoryId, state.categories))
        }
    }

    @Test
    fun categoryResolutionFollowsChainsAndFallsBackToInne() {
        val state = applyAll(
            listOf(
                Op.CategoryPut(id(), LIST, null, 1, "x", "X", false),
                Op.CategoryPut(id(), LIST, null, 1, "y", "Y", false),
                Op.CategoryDelete(id(), LIST, null, 2, "x", "y"),
                Op.CategoryDelete(id(), LIST, null, 3, "y", "x"), // a cycle
                Op.CategoryPut(id(), LIST, null, 1, "z", "Z", false),
                Op.CategoryDelete(id(), LIST, null, 2, "z", "mieso"),
            ),
        )
        assertEquals("inne", Merge.resolveCategory("x", state.categories))
        assertEquals("mieso", Merge.resolveCategory("z", state.categories))
        assertEquals("pieczywo", Merge.resolveCategory("pieczywo", state.categories))
        assertEquals("inne", Merge.resolveCategory("never-seen", state.categories))
    }

    // --- Local vs remote ----------------------------------------------------------------------

    /**
     * Two devices each make their own changes, then exchange whole nodes in either direction,
     * through the RTDB codec, in random order: both end where applying every op would.
     */
    @Test
    fun localAndRemoteConvergeWhicheverSideArrivesFirst() {
        val random = Random(11)
        repeat(ORDERS) {
            val phoneA = random.ops(8)
            val phoneB = random.ops(8)
            val stateA = applyAll(phoneA.shuffled(random))
            val stateB = applyAll(phoneB.shuffled(random))
            val expected = applyAll(phoneA + phoneB)

            val aThenB = viaRtdb(stateB).fold(stateA) { acc, node -> Merge.merge(acc, node) }
            val bThenA = viaRtdb(stateA).fold(stateB) { acc, node -> Merge.merge(acc, node) }
            assertEquals(expected, aThenB)
            assertEquals(expected, bThenA)
        }
    }

    @Test
    fun mergeRemoteOfASingleItemIsTheSameJoin() {
        val random = Random(12)
        repeat(ORDERS) {
            val local = applyAll(random.ops(6).filterIsInstance<Op.ItemPut>().map { it.copy(itemId = "item-a") })
                .items["item-a"]
            val remote = applyAll(
                List(3) { Op.ItemCheck(id(), LIST, random.actor(), random.at(), "item-a", random.nextBoolean()) },
            ).items.getValue("item-a")
            val merged = Merge.mergeRemote(local, remote)
            assertEquals(merged, Merge.mergeRemote(merged, remote))
            if (local != null) assertEquals(merged, Merge.mergeRemote(remote, local))
        }
    }

    /** Every node of a state as the other device would read it: encoded, decoded, one by one. */
    private fun viaRtdb(state: ListState): List<ListState> {
        val nodes = mutableListOf<ListState>()
        state.list?.let { nodes += ListState(list = NodeCodec.listFromNode(it.id, NodeCodec.listToNode(it), it.shared)) }
        state.categories.values.forEach {
            nodes += ListState(categories = mapOf(it.id to NodeCodec.categoryFromNode(it.listId, it.id, NodeCodec.categoryToNode(it))))
        }
        state.items.values.forEach {
            nodes += ListState(items = mapOf(it.id to NodeCodec.itemFromNode(it.listId, it.id, NodeCodec.itemToNode(it))))
        }
        return nodes.shuffled(Random(nodes.size))
    }

    // --- Lifetime -----------------------------------------------------------------------------

    @Test
    fun boughtItemsExpireAfterNinetyDaysAndTombstonesArePurgedAfterThirty() {
        val day = Merge.DAY_MS
        val now = 200 * day
        val state = applyAll(
            listOf(
                Op.ListPut(id(), LIST, null, 1, "Zakupy", BuiltinCategories.IDS),
                Op.ItemPut(id(), LIST, null, 1, "old-bought", ItemContent("Mąka")),
                Op.ItemCheck(id(), LIST, null, now - 90 * day, "old-bought", true),
                Op.ItemPut(id(), LIST, null, 1, "recent-bought", ItemContent("Cukier")),
                Op.ItemCheck(id(), LIST, null, now - 89 * day, "recent-bought", true),
                Op.ItemPut(id(), LIST, null, 1, "to-buy", ItemContent("Sól")),
                Op.ItemPut(id(), LIST, null, 1, "old-tombstone", ItemContent("Pieprz")),
                Op.ItemDelete(id(), LIST, null, now - 30 * day, "old-tombstone"),
                Op.ItemPut(id(), LIST, null, 1, "new-tombstone", ItemContent("Ryż")),
                Op.ItemDelete(id(), LIST, null, now - 29 * day, "new-tombstone"),
            ),
        )
        assertEquals(listOf("old-bought"), Merge.expiredItems(state, now))
        assertEquals(Merge.Purge(false, listOf("old-tombstone"), emptyList()), Merge.purgeable(state, now))

        val deletedList = applyAll(listOf(Op.ListDelete(id(), LIST, null, now - 31 * day)), state)
        assertTrue(Merge.purgeable(deletedList, now).wholeList)
    }
}
