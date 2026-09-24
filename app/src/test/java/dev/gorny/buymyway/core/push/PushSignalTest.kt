package dev.gorny.buymyway.core.push

import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.data.prefs.NotificationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a push is worth saying (PLAN.md Phase 9, task 3; STATE.md decisions 93–95). All of it is
 * pure, so the counting, the tally and what the switches hide are settled here rather than on a
 * phone.
 */
class PushSignalTest {

    private fun put(itemId: String = "i1", at: Long = 100) =
        Op.ItemPut("op", "list", "alice", at, itemId, ItemContent(name = "mleko", categoryId = "nabial"))

    private fun check(checked: Boolean) = Op.ItemCheck("op", "list", "alice", 100, "i1", checked)

    @Test
    fun `an item put is an addition only when it is the op that created the item`() {
        assertEquals(PushSignal.Kind.ADDED, PushSignal.kindOf(put(), createdNow = true))
        assertEquals(PushSignal.Kind.CHANGED, PushSignal.kindOf(put(), createdNow = false))
    }

    @Test
    fun `a tick is a purchase and an untick is a change`() {
        assertEquals(PushSignal.Kind.CHECKED, PushSignal.kindOf(check(true), createdNow = false))
        assertEquals(PushSignal.Kind.CHANGED, PushSignal.kindOf(check(false), createdNow = false))
    }

    @Test
    fun `everything else is a change`() {
        val others = listOf(
            Op.ItemDelete("op", "list", "alice", 100, "i1"),
            Op.ListPut("op", "list", "alice", 100, "Zakupy", emptyList()),
            Op.ListDelete("op", "list", "alice", 100),
            Op.ClearChecked("op", "list", "alice", 100),
            Op.CategoryPut("op", "list", "alice", 100, "c1", "Moje", false),
            Op.CategoryDelete("op", "list", "alice", 100, "c1", "inne"),
        )
        for (op in others) assertEquals(op.toString(), PushSignal.Kind.CHANGED, PushSignal.kindOf(op, false))
    }

    @Test
    fun `counts add up and know when they say nothing`() {
        var counts = PushSignal.Counts()
        assertTrue(counts.isEmpty)
        counts = counts + PushSignal.Kind.ADDED + PushSignal.Kind.ADDED + PushSignal.Kind.CHECKED
        assertEquals(PushSignal.Counts(added = 2, checked = 1), counts)
        assertEquals(3, counts.total)
        assertFalse(counts.isEmpty)
        assertEquals(PushSignal.Counts(2, 2, 1), counts + PushSignal.Counts(0, 1, 1))
    }

    @Test
    fun `the parts are read added, then checked, then the rest, and a zero is left out`() {
        val parts = PushSignal.parts(PushSignal.Counts(added = 3, checked = 2))
        assertEquals(listOf(PushSignal.Kind.ADDED, PushSignal.Kind.CHECKED), parts.map { it.kind })
        assertEquals(listOf(3, 2), parts.map { it.count })
        assertEquals(emptyList<PushSignal.Part>(), PushSignal.parts(PushSignal.Counts()))
        assertEquals(
            listOf(PushSignal.Kind.CHANGED),
            PushSignal.parts(PushSignal.Counts(changed = 1)).map { it.kind },
        )
    }

    @Test
    fun `a second message adds to the tally rather than replacing it`() {
        val first = PushSignal.Tally().plus(PushSignal.Counts(added = 2), "Ania")
        val second = first.plus(PushSignal.Counts(added = 3, checked = 1), "Ania")
        assertEquals(PushSignal.Counts(added = 5, checked = 1), second.counts)
        assertEquals("Ania", second.singleActor)
    }

    @Test
    fun `two people behind one tally leave it with no name`() {
        val tally = PushSignal.Tally()
            .plus(PushSignal.Counts(added = 1), "Ania")
            .plus(PushSignal.Counts(checked = 1), "Bartek")
        assertTrue(tally.manyActors)
        assertNull(tally.singleActor)
        // A message with no name at all does not turn one person into several.
        val anonymous = PushSignal.Tally().plus(PushSignal.Counts(added = 1), "Ania").plus(PushSignal.Counts(added = 1), null)
        assertEquals("Ania", anonymous.singleActor)
    }

    @Test
    fun `a tally survives being written down, and a corrupt one is simply no tally`() {
        val tally = PushSignal.Tally().plus(PushSignal.Counts(added = 2, checked = 1), "Ania")
        assertEquals(tally, PushSignal.decodeTally(PushSignal.encodeTally(tally)))
        assertEquals(PushSignal.Tally(), PushSignal.decodeTally(null))
        assertEquals(PushSignal.Tally(), PushSignal.decodeTally("{not json"))
        assertEquals(PushSignal.Tally(), PushSignal.decodeTally(""))
    }

    // --- The names the catch-up fills in (decision 106) -------------------------------------

    @Test
    fun `bought names gather in the order they were read, without repeats`() {
        val tally = PushSignal.Tally()
            .plus(PushSignal.Counts(checked = 2), "Ania")
            .plusBought(listOf("mleko", "chleb"))
            .plus(PushSignal.Counts(checked = 1), "Ania")
            // The second catch-up reads the whole list again: „mleko" must not double up.
            .plusBought(listOf("mleko", "masło"))
        assertEquals(listOf("mleko", "chleb", "masło"), tally.bought)
        assertEquals(PushSignal.Counts(checked = 3), tally.counts)
    }

    @Test
    fun `a tally keeps only the most recent names, and the counts are untouched by them`() {
        val many = (1..PushSignal.MAX_BOUGHT + 5).map { "rzecz $it" }
        val tally = PushSignal.Tally().plus(PushSignal.Counts(checked = many.size), "Ania").plusBought(many)
        assertEquals(PushSignal.MAX_BOUGHT, tally.bought.size)
        assertEquals("rzecz ${PushSignal.MAX_BOUGHT + 5}", tally.bought.last())
        assertEquals(many.size, tally.counts.checked)
    }

    @Test
    fun `names survive being written down, and a tally stored before them reads as none`() {
        val tally = PushSignal.Tally().plus(PushSignal.Counts(checked = 1), "Ania").plusBought(listOf("mleko"))
        assertEquals(tally, PushSignal.decodeTally(PushSignal.encodeTally(tally)))
        // What Phase 9 wrote, which has no `bought` field at all.
        val old = PushSignal.decodeTally("""{"counts":{"added":0,"checked":1,"changed":0},"actor":"Ania"}""")
        assertEquals(emptyList<String>(), old.bought)
        assertEquals(1, old.counts.checked)
    }

    @Test
    fun `a payload is read as numbers, and nonsense counts as nothing`() {
        val payload = mapOf(
            PushSignal.ADDED to "3",
            PushSignal.CHECKED to "2",
            PushSignal.CHANGED to "-5",
        )
        assertEquals(PushSignal.Counts(added = 3, checked = 2, changed = 0), PushSignal.countsOf(payload))
        assertEquals(PushSignal.Counts(), PushSignal.countsOf(emptyMap()))
        assertEquals(PushSignal.Counts(), PushSignal.countsOf(mapOf(PushSignal.ADDED to "wcale")))
    }

    // --- What the switches of this phone hide (decision 94) ---------------------------------

    @Test
    fun `nothing at all is shown while the master switch is off`() {
        val off = NotificationPreferences.Switches(enabled = false)
        assertFalse(off.allows(PushSignal.KIND_CHANGES))
        assertFalse(off.allows(PushSignal.KIND_SHARED))
    }

    @Test
    fun `each switch hides its own kind and leaves the others`() {
        val counts = PushSignal.Counts(added = 3, checked = 2, changed = 1)
        val all = NotificationPreferences.Switches(enabled = true)
        assertEquals(counts, all.filter(counts))

        val noAdds = all.copy(added = false)
        assertEquals(PushSignal.Counts(checked = 2), noAdds.filter(counts))

        val noChecks = all.copy(checked = false)
        assertEquals(PushSignal.Counts(added = 3, changed = 1), noChecks.filter(counts))

        val onlyShared = all.copy(added = false, checked = false)
        assertTrue(onlyShared.filter(counts).isEmpty)
        assertFalse(onlyShared.allows(PushSignal.KIND_CHANGES))
        assertTrue(onlyShared.allows(PushSignal.KIND_SHARED))

        assertFalse(all.copy(shared = false).allows(PushSignal.KIND_SHARED))
        assertTrue(all.copy(shared = false).allows(PushSignal.KIND_CHANGES))
    }
}
