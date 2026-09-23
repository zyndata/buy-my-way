package dev.gorny.buymyway.core.voice

import dev.gorny.buymyway.core.parse.ItemParser
import dev.gorny.buymyway.core.parse.ParsedItem
import dev.gorny.buymyway.core.text.TextKey

/**
 * Turns one spoken utterance into the things it names (PLAN.md *Voice input*, Phase 7 task 2):
 * „dwa kilo ziemniaków, mleko, masło i chleb" is four items. Pure, so it is tested without a
 * phone and without a recognizer.
 *
 * What it adds over [ItemParser], which reads the add bar, is what speech brings and typing
 * does not: „i" / „oraz" / „jeszcze" between items instead of a comma, the sentence a person
 * starts with („kup jeszcze…", „potrzebuję…"), „dwa razy mleko", and the full stop the
 * recognizer puts at the end. The quantity, the unit and the name are then read by
 * [ItemParser], so what is dictated and what is typed land on the same fields.
 */
object Dictation {
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Words that separate two items when they stand between them. Longest first, so that
     * „a także" is one separator rather than the opener „a" followed by „także".
     */
    private val separators = longestFirst("i", "oraz", "jeszcze", "plus", "jak rowniez", "a takze", "takze", "tudziez")

    /** What a person says before the list itself; dropped at the start of an item. */
    private val openers = longestFirst(
        "kup", "kupic", "kupimy", "kupuje", "dokup", "dodaj", "dodac", "dopisz", "wpisz",
        "potrzebuje", "potrzebujemy", "potrzeba", "potrzebne", "potrzebny", "potrzebna",
        "prosze", "poprosze", "wez", "wezmy", "mamy kupic", "trzeba", "trzeba kupic",
        "brakuje", "brakuje nam", "nam", "no", "jeszcze", "a", "i",
    )

    /** What a person says at the end of an item; dropped there. */
    private val closers = longestFirst("prosze", "tez", "takze")

    private fun longestFirst(vararg phrases: String): List<String> =
        phrases.sortedByDescending { phrase -> phrase.count { it == ' ' } }

    /** „dwa razy mleko" is two of one thing, not a thing called „razy mleko". */
    private val multiplier = setOf("razy", "raz")

    /** Commas separate items, except inside a number („1,5 kg"), as in the add bar. */
    private val commas = Regex("""(?<!\d),|,(?!\d)|[;\n]""")

    /**
     * How many words, from the given one on, name one thing — the dictionary's answer
     * ([dev.gorny.buymyway.core.categorize.Categorizer.knownNameLength]). Without it dictation
     * cuts only where a word says so, which is all a phone gives when it writes no commas.
     */
    fun interface KnownNames {
        fun lengthAt(foldedWords: List<String>, from: Int): Int

        companion object {
            /** Knows nothing: „mleko masło" stays one item. */
            val NONE = KnownNames { _, _ -> 0 }
        }
    }

    /**
     * Everything [utterance] names, in the order it was said. An empty utterance, or one that
     * names nothing („dwa kilo" alone), gives an empty list. [known] tells the parser where one
     * item ends and the next begins when nothing was said between them.
     */
    fun parse(utterance: String, known: KnownNames = KnownNames.NONE): List<ParsedItem> =
        segments(utterance)
            .map { clean(it) }
            .flatMap { segment -> cut(segment, known) }
            .mapNotNull(ItemParser::parse)

    /**
     * One cleaned segment as the things it names. A phone's recognizer writes „dwa kilo
     * ziemniaków mleko masło" with nothing between the three, so the dictionary is asked where
     * a name starts: a known name after a name already read begins a new item, and so does a
     * quantity that is followed by one („mleko dwa chleby"). Words it does not know stay where
     * they are, which keeps „ser żółty" and „papier toaletowy" whole.
     */
    private fun cut(segment: String, known: KnownNames): List<String> {
        val words = segment.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < 2) return listOf(segment)
        val folded = words.map { TextKey.fold(it) }

        val parts = mutableListOf<String>()
        var current = mutableListOf<String>()
        var named = false
        var index = 0
        while (index < words.size) {
            val length = known.lengthAt(folded, index)
            when {
                length > 0 -> {
                    if (named) {
                        parts += current.joinToString(" ")
                        current = mutableListOf()
                    }
                    repeat(length) { current += words[index++] }
                    named = true
                }
                // „mleko dwa chleby": the quantity belongs to what comes after it, not before.
                named && ItemParser.isQuantityToken(words[index]) && startsName(words, folded, index, known) -> {
                    parts += current.joinToString(" ")
                    current = mutableListOf(words[index])
                    named = false
                    index++
                }
                else -> current += words[index++]
            }
        }
        if (current.isNotEmpty()) parts += current.joinToString(" ")
        return parts
    }

    /** Whether the quantity at [index] is followed by a name, rather than ending the item. */
    private fun startsName(words: List<String>, folded: List<String>, index: Int, known: KnownNames): Boolean {
        var next = index
        while (next < words.size && ItemParser.isQuantityToken(words[next])) next++
        return next < words.size && known.lengthAt(folded, next) > 0
    }

    /** The utterance cut into one part per item, still as they were said. */
    fun segments(utterance: String): List<String> =
        utterance.split(commas)
            .flatMap { part -> splitOnWords(part) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Splits „masło i chleb" on the separator words, which a comma would otherwise be. */
    private fun splitOnWords(part: String): List<String> {
        val words = part.split(WHITESPACE).filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        var current = mutableListOf<String>()
        var index = 0
        while (index < words.size) {
            val taken = separatorAt(words, index)
            // A separator only separates: „i mleko" at the very start is an opener, not a cut.
            if (taken > 0 && current.isNotEmpty() && index + taken < words.size) {
                parts += current.joinToString(" ")
                current = mutableListOf()
                index += taken
            } else {
                current += words[index]
                index++
            }
        }
        if (current.isNotEmpty()) parts += current.joinToString(" ")
        return parts
    }

    /** How many words at [index] make a separator, or 0 when none does. */
    private fun separatorAt(words: List<String>, index: Int): Int {
        for (separator in separators) {
            val length = separator.count { it == ' ' } + 1
            if (index + length > words.size) continue
            val said = words.subList(index, index + length).joinToString(" ") { TextKey.fold(it) }
            if (said == separator) return length
        }
        return 0
    }

    /** One segment as [ItemParser] should see it: no opener, no closer, no „razy", no full stop. */
    private fun clean(segment: String): String {
        var words = segment.trim().trimEnd('.', '!', '?', ',').split(WHITESPACE).filter { it.isNotEmpty() }
        words = dropWhile(words, openers)
        // A „i" or „prosze" left at the end („mleko i") is not part of the name.
        words = dropWhile(words.asReversed(), closers + separators).asReversed()
        // „dwa razy mleko" → „dwa mleko"; „razy" alone is left, so it stays a name and is dropped.
        if (words.size > 1) words = words.filterIndexed { index, word -> index == 0 || TextKey.fold(word) !in multiplier }
        return words.joinToString(" ").trim().trimEnd('.', '!', '?')
    }

    /**
     * [words] without the leading ones (or leading pairs) that [phrases] names. Everything may
     * go: „kup" on its own names nothing, and then the segment is not an item at all.
     */
    private fun dropWhile(words: List<String>, phrases: List<String>): List<String> {
        var rest = words
        var cut = true
        while (cut && rest.isNotEmpty()) {
            cut = false
            for (phrase in phrases) {
                val length = phrase.count { it == ' ' } + 1
                if (length > rest.size) continue
                val said = rest.take(length).joinToString(" ") { TextKey.fold(it) }
                if (said == phrase) {
                    rest = rest.drop(length)
                    cut = true
                    break
                }
            }
        }
        return rest
    }
}
