package dev.gorny.buymyway.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeTest {
    @Test
    fun mergingAStateWithItselfChangesNothing() {
        val state = mapOf("name" to "Mleko", "checked" to false)
        assertEquals(state, Merge.merge(state, state))
    }
}
