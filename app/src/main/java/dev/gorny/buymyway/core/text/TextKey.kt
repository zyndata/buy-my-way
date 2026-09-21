package dev.gorny.buymyway.core.text

import java.util.Locale

/**
 * The comparable form of a product name: lower case, Polish letters folded to ASCII
 * („żółty" → „zolty"), anything that is not a letter or a digit turned into a space, spaces
 * collapsed. Typing without diacritics, dictation and Eat My Way's headings all meet here.
 */
object TextKey {
    private val polish = Locale.forLanguageTag("pl-PL")

    private val folds = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
    )

    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        var space = true
        for (raw in text.lowercase(polish)) {
            val c = folds[raw] ?: raw
            if (c.isLetterOrDigit()) {
                out.append(c)
                space = false
            } else if (!space) {
                out.append(' ')
                space = true
            }
        }
        return out.toString().trimEnd()
    }

    fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }
}
