package dev.gorny.buymyway.core.parse

import dev.gorny.buymyway.core.text.TextKey

/** One thing to buy as the user typed (or, from Phase 7, dictated) it. */
data class ParsedItem(val name: String, val quantity: Double? = null, val unit: String? = null)

/**
 * Reads what is typed into the add bar: „2 kg ziemniaki, mleko, masło x2" is three items
 * (PLAN.md Phase 3, task 3). Pure, so Phase 7's dictation reuses it (STATE.md decision 47).
 *
 * A quantity is read at the start of an item („2 kg ziemniaki", „pół kilo cebuli", „dwa
 * mleka", „500g sera") or at its end („ziemniaki 2 kg", „masło x2"). Units are written the
 * short way whatever the long form was („kilo" → „kg"). What is left is the name, as typed.
 */
object ItemParser {
    /** Commas separate items, except inside a number („1,5 kg"). */
    private val separator = Regex("""(?<!\d),|,(?!\d)|[;\n]""")

    private val number = Regex("""\d+(?:[.,]\d+)?""")

    /** A number and a unit written together: „500g", „1,5l", „2szt.". */
    private val numberWithUnit = Regex("""(\d+(?:[.,]\d+)?)(\p{L}+\.?)""")

    /** „x2", „2x", „×2". */
    private val times = Regex("""[x×](\d+)|(\d+)[x×]""", RegexOption.IGNORE_CASE)

    private val units: Map<String, String> = buildMap {
        listOf("kg", "kilo", "kilogram", "kilograma", "kilogramy", "kilogramow").forEach { put(it, "kg") }
        listOf("g", "gr", "gram", "grama", "gramy", "gramow").forEach { put(it, "g") }
        listOf("dag", "dkg", "deko", "deka").forEach { put(it, "dag") }
        listOf("l", "litr", "litra", "litry", "litrow").forEach { put(it, "l") }
        listOf("ml", "mililitr", "mililitra", "mililitry", "mililitrow").forEach { put(it, "ml") }
        listOf("szt", "sztuka", "sztuki", "sztuk").forEach { put(it, "szt.") }
        listOf("op", "opak", "opakowanie", "opakowania", "opakowan").forEach { put(it, "op.") }
    }

    /** Units that mean „one of them" when they stand without a number: „kilo ziemniaków". */
    private val impliesOne = setOf("kilo", "kilogram", "litr")

    private val numberWords: Map<String, Double> = mapOf(
        "pol" to 0.5, "poltora" to 1.5,
        "jeden" to 1.0, "jedna" to 1.0, "jedno" to 1.0,
        "dwa" to 2.0, "dwie" to 2.0, "trzy" to 3.0, "cztery" to 4.0, "piec" to 5.0,
        "szesc" to 6.0, "siedem" to 7.0, "osiem" to 8.0, "dziewiec" to 9.0, "dziesiec" to 10.0,
        "jedenascie" to 11.0, "dwanascie" to 12.0, "trzynascie" to 13.0, "czternascie" to 14.0,
        "pietnascie" to 15.0, "szesnascie" to 16.0, "siedemnascie" to 17.0, "osiemnascie" to 18.0,
        "dziewietnascie" to 19.0, "dwadziescia" to 20.0,
    )

    private const val MAX_QUANTITY = 10_000.0

    /** Every item in [text], in order; empty parts and parts with no name are dropped. */
    fun parseAll(text: String): List<ParsedItem> = segments(text).mapNotNull(::parse)

    /** One item, or null when [segment] names nothing („2 kg" alone). */
    fun parse(segment: String): ParsedItem? {
        val tokens = segment.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null

        var quantity: Double? = null
        var unit: String? = null

        val lead = leadingQuantity(tokens)
        if (lead != null) {
            quantity = lead.quantity
            unit = lead.unit
            repeat(lead.tokens) { tokens.removeAt(0) }
        } else {
            val trail = trailingQuantity(tokens)
            if (trail != null) {
                quantity = trail.quantity
                unit = trail.unit
                repeat(trail.tokens) { tokens.removeAt(tokens.lastIndex) }
            }
        }

        val name = tokens.joinToString(" ").trim()
        if (TextKey.fold(name).isEmpty() || number.matches(name)) return null
        return ParsedItem(name, quantity, unit)
    }

    /** The text split into the parts [parseAll] reads, untrimmed. */
    fun segments(text: String): List<String> = text.split(separator)

    /**
     * [text] with the name of its last item replaced by [name], keeping that item's quantity:
     * what picking an autocomplete suggestion does to „mleko, 2 kg ziem".
     */
    fun replaceLastName(text: String, name: String): String {
        val cut = separator.findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0
        val head = text.substring(0, cut)
        val last = text.substring(cut)
        val tokens = last.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val keep = leadingQuantity(tokens)?.tokens ?: 0
        val prefix = tokens.take(keep).joinToString(" ")
        val spacer = if (head.isNotEmpty()) " " else ""
        return head.trimEnd() + spacer + listOf(prefix, name).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The name part of the last item in [text]: what autocomplete looks up. */
    fun lastName(text: String): String {
        val last = segments(text).lastOrNull().orEmpty()
        val tokens = last.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val skip = leadingQuantity(tokens)?.tokens ?: 0
        return tokens.drop(skip).joinToString(" ")
    }

    private class Found(val quantity: Double, val unit: String?, val tokens: Int)

    private fun leadingQuantity(tokens: List<String>): Found? {
        if (tokens.size < 2) return null
        val first = tokens[0]
        val next = tokens[1]

        times.matchEntire(first)?.let { m -> return found(m.groupValues[1].ifEmpty { m.groupValues[2] }, null, 1) }

        numberWithUnit.matchEntire(first)?.let { m ->
            val u = unitOf(m.groupValues[2]) ?: return null
            return found(m.groupValues[1], u, 1)
        }

        val value = if (number.matches(first)) toDouble(first) else numberWords[TextKey.fold(first)]
        if (value != null) {
            val u = unitOf(next)
            return if (u != null) {
                // „2 kg" alone takes both tokens and leaves no name, so it is not an item.
                valid(value)?.let { Found(it, u, 2) }
            } else {
                valid(value)?.let { Found(it, null, 1) }
            }
        }

        val folded = TextKey.fold(first)
        if (folded in impliesOne) return Found(1.0, units.getValue(folded), 1)
        return null
    }

    private fun trailingQuantity(tokens: List<String>): Found? {
        if (tokens.size < 2) return null
        val last = tokens.last()

        times.matchEntire(last)?.let { m -> return found(m.groupValues[1].ifEmpty { m.groupValues[2] }, null, 1) }

        numberWithUnit.matchEntire(last)?.let { m ->
            val u = unitOf(m.groupValues[2]) ?: return null
            return found(m.groupValues[1], u, 1)
        }

        if (tokens.size >= 3 && number.matches(tokens[tokens.size - 2])) {
            val u = unitOf(last)
            if (u != null) return found(tokens[tokens.size - 2], u, 2)
        }
        if (number.matches(last)) return found(last, null, 1)
        return null
    }

    private fun found(digits: String, unit: String?, tokens: Int): Found? =
        valid(toDouble(digits))?.let { Found(it, unit, tokens) }

    private fun valid(value: Double?): Double? = value?.takeIf { it > 0 && it <= MAX_QUANTITY }

    private fun toDouble(digits: String): Double? = digits.replace(',', '.').toDoubleOrNull()

    private fun unitOf(token: String): String? = units[TextKey.fold(token.removeSuffix("."))]
}
