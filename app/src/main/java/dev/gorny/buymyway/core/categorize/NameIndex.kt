package dev.gorny.buymyway.core.categorize

import dev.gorny.buymyway.core.text.TextKey

/**
 * Names — the bundled dictionary's, or the ones this phone has seen — with one question:
 * how many words, from here on, name one thing? Dictation cuts an utterance with the answer,
 * because a phone's recognizer writes no commas (STATE.md decisions 78 and 80).
 *
 * The match is stricter than [Categorizer]'s: a spoken word may carry at most two letters
 * beyond the stored name, so „ziemniaków" still meets „ziemniaki" while „pomarańczowy" no
 * longer meets „pomarańcze" and cannot start an item of its own in „sok pomarańczowy". A
 * Polish adjective belongs to the noun before it.
 */
class NameIndex(names: List<List<String>>) {

    private val byLength: Map<Int, List<List<String>>> = names.filter { it.isNotEmpty() }.groupBy { it.size }

    private val longest: Int = byLength.keys.maxOrNull() ?: 0

    /**
     * The most words of [words], from [from] on, that name one thing here, or 0 when none do.
     * [words] are folded ([TextKey.words]). The longest name wins, so „mleko kokosowe" is one
     * thing rather than „mleko" and something else.
     */
    fun lengthAt(words: List<String>, from: Int): Int {
        for (length in minOf(longest, words.size - from) downTo 1) {
            val window = words.subList(from, from + length)
            if (window.any(::saysHowMuch)) continue
            if (byLength[length].orEmpty().any { name -> namesTheSameThing(name, window) }) return length
        }
        return 0
    }

    /** Every word of the name meets a different word of the window, in any order. */
    private fun namesTheSameThing(name: List<String>, window: List<String>): Boolean {
        val used = BooleanArray(window.size)
        for (word in name) {
            val index = window.indices.firstOrNull { !used[it] && closeEnough(window[it], word) } ?: return false
            used[index] = true
        }
        return true
    }

    companion object {
        /**
         * How many letters a spoken word may carry beyond the stored name and still be taken
         * for it. Three would let a derived adjective through („pomarańczowy" ↔ „pomarańcze").
         */
        private const val STRICT_ENDING = 2

        /** An index of names as they are written („mleko kokosowe", „chleb wiejski"). */
        fun of(names: Collection<String>): NameIndex =
            NameIndex(names.map { Categorizer.meaningful(TextKey.words(it)) })

        /** An index of names already folded to their [TextKey.fold] form, as Room stores them. */
        fun ofFolded(keys: Collection<String>): NameIndex =
            NameIndex(keys.map { key -> Categorizer.meaningful(key.split(' ').filter { it.isNotEmpty() }) })

        /** Whether a word says *how much* rather than *what*, and so names nothing. */
        private fun saysHowMuch(word: String): Boolean =
            word.any(Char::isDigit) || Categorizer.saysHowMuch(word)

        /** [Categorizer.matchLength], with the tighter ending a cut between items asks for. */
        private fun closeEnough(spoken: String, name: String): Boolean {
            if (spoken == name) return true
            val matched = Categorizer.matchLength(spoken, name)
            return matched > 0 && spoken.length - matched <= STRICT_ENDING
        }
    }
}
