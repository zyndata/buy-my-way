package dev.gorny.buymyway.core.text

import java.math.BigDecimal

/**
 * The row's quick „−/+" (STATE.md decisions 121 and 123): how far one tap moves a quantity, by
 * its unit, and which units the menu offers. Pieces and anything unknown move by 1, kilograms and
 * litres by half, grams and decagrams by ten, millilitres by a hundred. „−" on the last step
 * clears the quantity and never the item.
 */
object QuantityStep {
    /** The same ceiling as the edit sheet's field ([QuantityFormat.parse]). */
    private val MAX = BigDecimal(10_000)

    /** What the menu offers, in this order; [PIECES] also stands for no unit at all. */
    const val PIECES = "szt."
    val UNITS = listOf(PIECES, "g", "kg", "ml", "l")

    private val PIECE_WORDS = setOf("", "szt", "sztuk", "sztuka", "sztuki")

    /** One key per unit, so „sztuki", „szt." and no unit at all are the same choice. */
    fun key(unit: String?): String {
        val k = unit?.trim()?.lowercase()?.removeSuffix(".").orEmpty()
        return if (k in PIECE_WORDS) "szt" else k
    }

    fun step(unit: String?): BigDecimal = when (key(unit)) {
        "kg", "l" -> BigDecimal("0.5")
        "g", "dag" -> BigDecimal(10)
        "ml" -> BigDecimal(100)
        else -> BigDecimal.ONE
    }

    /** What a unit starts at when the menu switches to it and has nothing to remember. */
    fun start(unit: String?): Double = when (key(unit)) {
        "g", "ml" -> 100.0
        "dag" -> 10.0
        else -> 1.0
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
