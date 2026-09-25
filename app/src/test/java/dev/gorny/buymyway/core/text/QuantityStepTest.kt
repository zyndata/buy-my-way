package dev.gorny.buymyway.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityStepTest {
    @Test
    fun piecesAndUnknownUnitsMoveByOne() {
        assertEquals(3.0, QuantityStep.up(2.0, null), 0.0)
        assertEquals(3.0, QuantityStep.up(2.0, "szt."), 0.0)
        assertEquals(5.0, QuantityStep.down(6.0, "opak"))
        assertEquals(2.5, QuantityStep.up(1.5, null), 0.0)
    }

    @Test
    fun weightsAndVolumesMoveByAStepThatFitsTheUnit() {
        assertEquals(2.0, QuantityStep.up(1.5, "kg"), 0.0)
        assertEquals(1.0, QuantityStep.down(1.5, "L"))
        assertEquals(260.0, QuantityStep.up(250.0, "g"), 0.0)
        assertEquals(400.0, QuantityStep.down(500.0, "ml"))
        assertEquals(30.0, QuantityStep.up(20.0, "dag"), 0.0)
    }

    @Test
    fun plusOnNothingGivesOneStepButNeverLessThanOne() {
        assertEquals(1.0, QuantityStep.up(null, null), 0.0)
        assertEquals(1.0, QuantityStep.up(null, "kg"), 0.0)
        assertEquals(10.0, QuantityStep.up(null, "g"), 0.0)
    }

    @Test
    fun minusOnTheLastStepClearsTheQuantityAndNothingStaysNothing() {
        assertNull(QuantityStep.down(1.0, null))
        assertNull(QuantityStep.down(0.5, "kg"))
        assertNull(QuantityStep.down(10.0, "g"))
        assertNull(QuantityStep.down(null, null))
    }

    @Test
    fun piecesAreOneUnitHoweverTheyAreSpelled() {
        assertEquals("szt", QuantityStep.key(null))
        assertEquals("szt", QuantityStep.key("sztuki"))
        assertEquals("szt", QuantityStep.key(" Szt. "))
        assertEquals("g", QuantityStep.key("g"))
        assertEquals("ząbki", QuantityStep.key("ząbki"))
    }

    @Test
    fun aUnitSwitchedToStartsWhereItMakesSense() {
        assertEquals(1.0, QuantityStep.start("szt."), 0.0)
        assertEquals(100.0, QuantityStep.start("g"), 0.0)
        assertEquals(1.0, QuantityStep.start("kg"), 0.0)
    }

    @Test
    fun theStepsDoNotDriftAndStopAtTheFieldsCeiling() {
        var q: Double? = 0.1
        repeat(3) { q = QuantityStep.up(q, "kg") }
        assertEquals(1.6, q!!, 0.0)
        assertEquals(10_000.0, QuantityStep.up(9_999.5, null), 0.0)
    }
}
