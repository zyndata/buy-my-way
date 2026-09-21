package dev.gorny.buymyway.core.categorize

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.text.TextKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Proposes a category for a typed, dictated or imported name, from the bundled product
 * dictionary (`assets/products-pl.json`, STATE.md decision 40). Never a network call.
 *
 * Polish inflects, so words are matched by their common prefix with a short allowance for the
 * ending on either side: „ziemniaków" meets „ziemniaki", „cebulę" meets „cebula". Every word of
 * a dictionary entry must meet a word of the name, and the entry that matches the most letters
 * wins, so „mleko kokosowe z puszki" is coconut milk, not milk. A tie between two different
 * departments proposes „Inne": a miss is fine, a wrong department is not.
 */
class Categorizer(dictionary: Dictionary) {

    @Serializable
    data class Dictionary(
        val version: Int,
        /** Category id → product names in their usual shop form. */
        val categories: Map<String, List<String>>,
        /** Category id → words that decide the category wherever they appear („mrożone"). */
        val overrides: Map<String, List<String>> = emptyMap(),
    )

    private class Entry(val words: List<String>, val categoryId: String)

    private val entries: List<Entry> = dictionary.categories.flatMap { (categoryId, names) ->
        names.map { Entry(meaningful(TextKey.words(it)), categoryId) }.filter { it.words.isNotEmpty() }
    }

    private val overrides: List<Pair<String, String>> = dictionary.overrides.flatMap { (categoryId, words) ->
        words.map { TextKey.fold(it) to categoryId }
    }

    /**
     * The category id for [name]. [corrections] are the user's own choices, keyed by
     * [TextKey.fold] of the name; they win over the dictionary.
     */
    fun categorize(name: String, corrections: Map<String, String> = emptyMap()): String {
        corrections[TextKey.fold(name)]?.let { return it }
        val words = meaningful(TextKey.words(name))
        if (words.isEmpty()) return BuiltinCategories.FALLBACK

        overrides.firstOrNull { (word, _) -> words.any { matchLength(it, word) > 0 } }?.let { return it.second }

        var best: Score? = null
        val winners = mutableSetOf<String>()
        for (entry in entries) {
            val score = score(entry.words, words) ?: continue
            val cmp = best?.let { compareValues(score, it) } ?: 1
            if (cmp > 0) {
                best = score
                winners.clear()
            }
            if (cmp >= 0) winners += entry.categoryId
        }
        return winners.singleOrNull() ?: BuiltinCategories.FALLBACK
    }

    /** Letters matched, then (as a tie-break) fewer letters of inflection on either side. */
    private data class Score(val matched: Int, val distance: Int) : Comparable<Score> {
        override fun compareTo(other: Score): Int =
            compareValuesBy(this, other, { it.matched }, { -it.distance })
    }

    private fun score(entryWords: List<String>, inputWords: List<String>): Score? {
        val used = BooleanArray(inputWords.size)
        var matched = 0
        var distance = 0
        for (entryWord in entryWords) {
            var bestIndex = -1
            var bestLength = 0
            inputWords.forEachIndexed { i, inputWord ->
                if (!used[i]) {
                    val length = matchLength(inputWord, entryWord)
                    if (length > bestLength) {
                        bestLength = length
                        bestIndex = i
                    }
                }
            }
            if (bestIndex < 0) return null
            used[bestIndex] = true
            matched += bestLength
            distance += entryWord.length - bestLength + inputWords[bestIndex].length - bestLength
        }
        return Score(matched, distance)
    }

    companion object {
        private const val MIN_STEM = 3
        private const val LONG_STEM = 4
        private const val SHORT_WORD = 5
        private const val MAX_ENDING = 3

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): Categorizer = Categorizer(json.decodeFromString(Dictionary.serializer(), text))

        private val stopWords = setOf("z", "ze", "w", "we", "i", "na", "do", "bez", "o", "od", "dla", "a", "po", "lub", "albo")

        private val quantityWords = setOf(
            // units
            "kg", "g", "dag", "dkg", "mg", "l", "ml", "szt", "sztuk", "sztuka", "sztuki", "op", "opak",
            "kilo", "kilogram", "kilogramy", "kilogramow", "gram", "gramy", "gramow",
            "litr", "litra", "litry", "litrow", "mililitr", "mililitrow",
            // counts
            "pol", "jeden", "jedna", "jedno", "dwa", "dwie", "trzy", "cztery", "piec", "szesc",
            "siedem", "osiem", "dziewiec", "dziesiec", "kilka", "pare",
        )

        /** Drops numbers, units, counts and little words: what is left names the product. */
        internal fun meaningful(words: List<String>): List<String> = words.filter { word ->
            word !in stopWords && word !in quantityWords && word.none(Char::isDigit)
        }

        /**
         * How many letters two folded words share when one could be an inflected form of the
         * other; 0 when they cannot. Equal words match on their whole length.
         */
        internal fun matchLength(input: String, entry: String): Int {
            if (input == entry) return entry.length
            val prefix = input.commonPrefixWith(entry).length
            if (prefix < MIN_STEM) return 0
            // Three shared letters are a stem only for short words („jajek" ↔ „jajka");
            // between long ones they are a coincidence („piersi" ↔ „pieprz").
            if (prefix < LONG_STEM && minOf(input.length, entry.length) > SHORT_WORD) return 0
            if (entry.length - prefix > MAX_ENDING || input.length - prefix > MAX_ENDING) return 0
            return prefix
        }
    }
}
