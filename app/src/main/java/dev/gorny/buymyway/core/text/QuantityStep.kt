package dev.gorny.buymyway.core.text

import java.math.BigDecimal

/**
 * The row's quick „−/+" (STATE.md decision 121): how far one tap moves a quantity, by its unit.
 * Pieces and anything unknown move by 1, kilograms and litres by half, grams and millilitres by
 * a hundred, decagrams by ten. „−" on the last step clears the quantity and never the item.
 */
object QuantityStep {
    /** The same ceiling as the edit sheet's field ([QuantityFormat.parse]). */
    private val MAX = BigDecimal(10_000)

    fun step(unit: String?): BigDecimal = when (unit?.trim()?.lowercase()?.removeSuffix(".")) {
        "kg", "l" -> BigDecimal("0.5")
        "g", "ml" -> BigDecimal(100)
        "dag" -> BigDecimal(10)
        else -> BigDecimal.ONE
    }

    /** From nothing to one step, but never less than one: „+" on a bare „kg" means 1 kg. */
    fun up(quantity: Double?, unit: String?): Double {
        val step = step(unit)
        val next = if (quantity == null) step.max(BigDecimal.ONE) else BigDecimal.valueOf(quantity) + step
        return next.min(MAX).toDouble()
    }

    /** Null (no quantity) once a step would reach zero; null stays null. */
    fun down(quantity: Double?, unit: String?): Double? {
        if (quantity == null) return null
        val next = BigDecimal.valueOf(quantity) - step(unit)
        return if (next.signum() > 0) next.toDouble() else null
    }
}
