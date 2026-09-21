package dev.gorny.buymyway.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderingTest {
    @Test
    fun listsNamedInTheOrderComeFirstAndTheRestKeepTheirPlace() {
        val lists = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), Ordering.byIds(lists, listOf("c", "gone", "a")) { it })
        assertEquals(lists, Ordering.byIds(lists, emptyList()) { it })
    }

    @Test
    fun moveTakesAnElementOutAndPutsItAtTheTarget() {
        assertEquals(listOf("b", "c", "a"), Ordering.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), Ordering.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), Ordering.move(listOf("a", "b"), 0, 5))
    }

    @Test
    fun theMovedItemGetsAKeyBetweenItsNewNeighbours() {
        assertEquals(1.5, Ordering.sortKeyBetween(1.0, 2.0), 0.0)
        assertEquals(0.0, Ordering.sortKeyBetween(null, 1.0), 0.0)
        assertEquals(4.0, Ordering.sortKeyBetween(3.0, null), 0.0)
        assertEquals(1.0, Ordering.sortKeyBetween(null, null), 0.0)

        // [1, 2, 3] with the last moved to the front: its key sorts before 1…
        assertTrue(Ordering.sortKeyAt(Ordering.move(listOf(1.0, 2.0, 3.0), 2, 0), 0) < 1.0)
        // …and the middle one moved last sorts after 3.
        assertTrue(Ordering.sortKeyAt(Ordering.move(listOf(1.0, 2.0, 3.0), 1, 2), 2) > 3.0)
        // The first moved between the other two lands between them.
        val key = Ordering.sortKeyAt(Ordering.move(listOf(1.0, 2.0, 3.0), 0, 1), 1)
        assertTrue(key > 2.0 && key < 3.0)
    }
}
