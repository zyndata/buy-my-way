package dev.gorny.buymyway.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityFormatTest {
    @Test
    fun numbersArePrintedThePolishWay() {
        assertEquals("2", QuantityFormat.number(2.0))
        assertEquals("1,5", QuantityFormat.number(1.5))
        assertEquals("0,333", QuantityFormat.number(1.0 / 3))
        assertEquals("1000", QuantityFormat.number(1000.0))
    }

    @Test
    fun aQuantityIsShownWithItsUnitAndAUnitAloneIsNot() {
        assertEquals("2 kg", QuantityFormat.format(2.0, "kg"))
        assertEquals("3", QuantityFormat.format(3.0, null))
        assertEquals("3", QuantityFormat.format(3.0, " "))
        assertNull(QuantityFormat.format(null, "kg"))
    }

    @Test
    fun theEditSheetFieldAcceptsBothDecimalMarks() {
        assertEquals(1.5, QuantityFormat.parse("1,5").getOrThrow()!!, 0.0)
        assertEquals(1.5, QuantityFormat.parse(" 1.5 ").getOrThrow()!!, 0.0)
        assertNull(QuantityFormat.parse("").getOrThrow())
        assertTrue(QuantityFormat.parse("dwa").isFailure)
        assertTrue(QuantityFormat.parse("0").isFailure)
        assertTrue(QuantityFormat.parse("-1").isFailure)
    }
}
