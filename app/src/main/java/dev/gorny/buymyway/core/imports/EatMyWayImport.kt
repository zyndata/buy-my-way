package dev.gorny.buymyway.core.imports

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.text.TextKey
import dev.gorny.buymyway.core.text.TextLimits

/**
 * One line of a shared list, as the text gave it. [categoryId] is set when the line stood under
 * one of the nine departments, [categoryName] when it stood under a heading of its own, and
 * neither when the text gave no heading at all — only then is the name categorised by the
 * dictionary (PLAN.md *Import from Eat My Way*).
 */
data class ImportedItem(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
)

/** Everything a shared text turned out to hold. */
data class ImportedList(
    /** The „Lista zakupów — …" line, when the text had one. */
    val title: String?,
    /** What a new list made out of this text is called. */
    val suggestedName: String,
    val items: List<ImportedItem>,
    /** Whether the text really is an Eat My Way list, as opposed to any other text. */
    val fromEatMyWay: Boolean,
)

/**
 * Reads the plain text Eat My Way shares (PLAN.md *Import from Eat My Way*, Phase 8 task 1).
 * Pure, so the whole format is tested without a phone.
 *
 * ```
 * Lista zakupów — tydzień 15.09 – 21.09
 *
 * Warzywa i owoce
 * • Cebula — 2 szt. (160 g)
 * • Czosnek — 2 ząbki (10 g)
 * ```
 *
 * It is written to be *tolerant*, because what arrives through a share sheet is whatever the
 * sending app felt like sending: any text at all becomes one item per non-empty line rather
 * than nothing (PLAN.md's third acceptance criterion). So the shape above is recognised where
 * it is there, and everything else is still read as a list of names.
 *
 * Nothing here is Eat My Way's code; only its output format is read, and that format is fixed
 * by `formatShoppingList` in its `src/lib/shopping.ts` (STATE.md decision 10).
 */
object EatMyWayImport {

    private const val TITLE_PREFIX = "lista zakupow"

    /** What Eat My Way prints instead of a body when nothing has to be bought. */
    private const val NOTHING_TO_BUY = "brak skladnikow do kupienia"

    /** What a list made from a text with no title of its own is called. */
    const val DEFAULT_NAME = "Zakupy"

    /** The bullet Eat My Way writes, and the ones a person's own list might use instead. */
    private val bullet = Regex("""^\s*(?:[•·*–—-]|\d+[.)])\s+""")

    /** „Cebula — 2 szt.": the dash that holds a name apart from its amount. */
    private val dash = Regex("""\s+[—–-]\s+""")

    /** „(160 g)" at the end of an amount: informational, and never part of the quantity. */
    private val grams = Regex("""\s*\(\s*\d+(?:[.,]\d+)?\s*g\s*\)\s*$""")

    /** A leading number, with the thousands groups Polish formatting may have put in it. */
    private val leadingAmount = Regex("""^(\d+(?: \d{3})*)(?:[.,](\d+))?\s*(.*)$""")

    private const val MAX_QUANTITY = 10_000.0

    /**
     * The spaces Polish number formatting puts inside a thousands group („1 200"): U+00A0 and
     * U+202F. Written as code points because neither is visible in source. They are made
     * ordinary spaces before a line is read, so such a group is one number and not a unit.
     */
    private val NBSP = 160.toChar()
    private val NARROW_NBSP = 8239.toChar()

    /** Department label (folded) → its id, so „Nabiał i jaja" maps onto `nabial`. */
    private val departments: Map<String, String> =
        BuiltinCategories.ALL.associate { (id, label) -> TextKey.fold(label) to id }

    /**
     * Units written the short way, so an imported „2 szt." and a typed „2 sztuki" meet.
     * Household measures („ząbki", „kromki") are left exactly as they were written: they are
     * what the line says, and [unitKey] is what decides whether two of them are the same unit.
     */
    private val units: Map<String, String> = buildMap {
        listOf("szt", "sztuka", "sztuki", "sztuk").forEach { put(it, "szt.") }
        listOf("op", "opak", "opakowanie", "opakowania", "opakowan").forEach { put(it, "op.") }
        listOf("g", "gr", "gram", "grama", "gramy", "gramow").forEach { put(it, "g") }
        listOf("kg", "kilo", "kilogram", "kilograma", "kilogramy", "kilogramow").forEach { put(it, "kg") }
        listOf("dag", "dkg", "deko", "deka").forEach { put(it, "dag") }
        listOf("l", "litr", "litra", "litry", "litrow").forEach { put(it, "l") }
        listOf("ml", "mililitr", "mililitra", "mililitry", "mililitrow").forEach { put(it, "ml") }
    }

    /**
     * Eat My Way's household measures, every printed form under the one word they mean, so that
     * „1 ząbek" and „2 ząbki" count as the same unit and their quantities are summed. Its
     * `MEASURE_NAMES` table is the reference; only the words are the same, no code is shared.
     */
    private val measures: Map<String, String> = buildMap {
        fun measure(base: String, vararg forms: String) = forms.forEach { put(TextKey.fold(it), base) }
        measure("zabek", "ząbek", "ząbki", "ząbków", "ząbka")
        measure("kromka", "kromka", "kromki", "kromek")
        measure("plaster", "plaster", "plastry", "plastrów", "plastra")
        measure("garsc", "garść", "garście", "garści")
        measure("lyzka", "łyżka", "łyżki", "łyżek")
        measure("lyzeczka", "łyżeczka", "łyżeczki", "łyżeczek")
        measure("szklanka", "szklanka", "szklanki", "szklanek")
        measure("kubek", "kubek", "kubki", "kubków", "kubka")
        measure("peczek", "pęczek", "pęczki", "pęczków", "pęczka")
        measure("galazka", "gałązka", "gałązki", "gałązek")
        measure("porcja", "porcja", "porcje", "porcji")
        measure("mala szt", "mała szt.", "małe szt.", "małych szt.", "małej szt.")
        measure("srednia szt", "średnia szt.", "średnie szt.", "średnich szt.", "średniej szt.")
        measure("duza szt", "duża szt.", "duże szt.", "dużych szt.", "dużej szt.")
    }

    /**
     * How two units are told apart when quantities are summed: the same word whatever form it
     * was printed in, and nothing at all for a line with no unit. The *stored* unit stays as it
     * was written, so a row still reads „2 ząbki" rather than a dictionary form.
     */
    fun unitKey(unit: String?): String {
        val folded = TextKey.fold(unit.orEmpty())
        if (folded.isEmpty()) return ""
        return measures[folded] ?: units[folded] ?: folded
    }

    /** Everything [text] names, and what a list made out of it would be called. */
    fun parse(text: String): ImportedList {
        // A Polish thousands group is a non-breaking space; it is a space like any other here.
        val lines = text.replace(NBSP, ' ').replace(NARROW_NBSP, ' ').lines().map { it.trim() }
        val titleIndex = lines.indexOfFirst { it.isNotEmpty() }
            .takeIf { it >= 0 && TextKey.fold(lines[it]).startsWith(TITLE_PREFIX) }
        val title = titleIndex?.let { lines[it] }
        val hasBullet = lines.any { bullet.containsMatchIn(it) }

        val items = mutableListOf<ImportedItem>()
        var categoryId: String? = null
        var categoryName: String? = null
        lines.forEachIndexed { index, line ->
            when {
                line.isEmpty() || index == titleIndex -> Unit
                TextKey.fold(line) == NOTHING_TO_BUY -> Unit
                isHeading(lines, index) -> {
                    val id = departments[TextKey.fold(line)]
                    categoryId = id
                    categoryName = if (id == null) line.take(TextLimits.CATEGORY_NAME) else null
                }
                else -> item(line, categoryId, categoryName)?.let { items += it }
            }
        }
        return ImportedList(
            title = title,
            suggestedName = nameFrom(title),
            items = items,
            fromEatMyWay = hasBullet || title != null,
        )
    }

    /**
     * Whether the line at [index] heads the lines under it. A department label always does. Any
     * other line does when the next thing in the text is a bullet, which is what an Eat My Way
     * list brought a category of its own would look like. Nothing else is a heading, so a
     * hand-typed „Lista zakupów / mleko / chleb" stays three lines and loses no name.
     */
    private fun isHeading(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (bullet.containsMatchIn(line)) return false
        if (TextKey.fold(line) in departments) return true
        val next = lines.drop(index + 1).firstOrNull { it.isNotEmpty() } ?: return false
        return bullet.containsMatchIn(next)
    }

    /** One line as the thing it names, or null when it names nothing. */
    private fun item(line: String, categoryId: String?, categoryName: String?): ImportedItem? {
        val body = line.replaceFirst(bullet, "").trim()
        if (body.isEmpty()) return null

        // The name may hold a dash of its own („Ser feta - grecki — 200 g"), so the amount is
        // taken from the last one; a line with no amount at all keeps every word as its name.
        val split = dash.findAll(body).lastOrNull()
        val amount = split?.let { parseAmount(body.substring(it.range.last + 1)) }
        val name = clean(if (split == null || amount == null) body else body.substring(0, split.range.first))
        if (name.isEmpty()) return null

        return ImportedItem(
            name = name,
            quantity = amount?.quantity,
            unit = amount?.unit,
            categoryId = categoryId,
            categoryName = categoryName,
        )
    }

    private class Amount(val quantity: Double?, val unit: String?)

    /**
     * „2 szt. (160 g)" → 2 and „szt."; null when the text does not begin with a number, which
     * is what leaves „Sól — do smaku" whole. A number outside what an item may hold is still an
     * amount — the line said how much, just not believably — so it is taken off the name and
     * dropped, rather than left to read „Ryż — 99999 g".
     */
    private fun parseAmount(text: String): Amount? {
        val match = leadingAmount.matchEntire(text.replace(grams, "").trim()) ?: return null
        val digits = match.groupValues[1].replace(" ", "")
        val fraction = match.groupValues[2]
        val value = (if (fraction.isEmpty()) digits else "$digits.$fraction").toDoubleOrNull()
        if (value == null || value <= 0 || value > MAX_QUANTITY) return Amount(null, null)

        val written = match.groupValues[3].trim()
        val folded = TextKey.fold(written)
        val unit = when {
            written.isEmpty() -> null
            folded in units -> units.getValue(folded)
            else -> written.take(TextLimits.UNIT)
        }
        return Amount(value, unit)
    }

    /** „Lista zakupów — tydzień 15.09 – 21.09" → „tydzień 15.09 – 21.09". */
    private fun nameFrom(title: String?): String {
        if (title == null) return DEFAULT_NAME
        val rest = dash.findAll(title).firstOrNull()?.let { title.substring(it.range.last + 1) }
        return clean(rest.orEmpty()).ifEmpty { clean(title) }.ifEmpty { DEFAULT_NAME }.take(100)
    }

    private fun clean(text: String): String = text.trim().replace(Regex("""\s+"""), " ")
}
