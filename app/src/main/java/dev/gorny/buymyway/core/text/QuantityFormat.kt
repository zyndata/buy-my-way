package dev.gorny.buymyway.core.text

import java.math.BigDecimal
import java.math.RoundingMode

/** How a quantity is printed on a row and in the edit sheet: „2 kg", „1,5 l", „3". */
object QuantityFormat {
    /** „2", „1,5", „0,25": no trailing zeros, a Polish decimal comma, at most three decimals. */
    fun number(value: Double): String =
        BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    /** Null when there is no quantity at all; a unit without a number is not shown. */
    fun format(quantity: Double?, unit: String?): String? {
        if (quantity == null) return null
        return listOfNotNull(number(quantity), unit?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    /** The edit sheet's quantity field: „1,5" or „1.5"; blank is no quantity; nonsense is null. */
    fun parse(text: String): Result<Double?> {
        val t = text.trim()
        if (t.isEmpty()) return Result.success(null)
        val value = t.replace(',', '.').toDoubleOrNull()
        return if (value != null && value > 0 && value <= 10_000) Result.success(value) else Result.failure(IllegalArgumentException(t))
    }
}
