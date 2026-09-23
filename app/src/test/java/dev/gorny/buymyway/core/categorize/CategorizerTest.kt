package dev.gorny.buymyway.core.categorize

import dev.gorny.buymyway.core.model.BuiltinCategories
import dev.gorny.buymyway.core.text.TextKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CategorizerTest {

    /** Unit tests run with the module directory as the working directory. */
    private val dictionaryText = File("src/main/assets/products-pl.json").readText()
    private val categorizer = Categorizer.fromJson(dictionaryText)

    @Test
    fun theDictionaryIsSound() {
        val dictionary = Json.decodeFromString(Categorizer.Dictionary.serializer(), dictionaryText)
        assertEquals(1, dictionary.version)
        assertEquals(BuiltinCategories.IDS.toSet(), dictionary.categories.keys)
        val names = dictionary.categories.values.flatten()
        assertTrue("~600 names, found ${names.size}", names.size in 550..800)
        val folded = names.map(TextKey::fold)
        assertEquals("names that fold to the same key", emptyList<String>(), folded.groupBy { it }.filter { it.value.size > 1 }.keys.toList())
    }

    /** No entry is shadowed by another department's entry. */
    @Test
    fun everyDictionaryNameIsFiledUnderItsOwnCategory() {
        val dictionary = Json.decodeFromString(Categorizer.Dictionary.serializer(), dictionaryText)
        val wrong = dictionary.categories.flatMap { (category, names) ->
            names.mapNotNull { name ->
                val got = categorizer.categorize(name)
                "$name: $got (expected $category)".takeIf { got != category }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun inflectedFormsFindTheirStem() {
        assertEquals("warzywa", categorizer.categorize("ziemniaków"))
        assertEquals("warzywa", categorizer.categorize("cebulę"))
        assertEquals("nabial", categorizer.categorize("jajek"))
        assertEquals("pieczywo", categorizer.categorize("bułek"))
        assertEquals("mieso", categorizer.categorize("piersi"))
        assertEquals("przyprawy", categorizer.categorize("pieprzu"))
    }

    @Test
    fun theLongestMatchWins() {
        assertEquals("sypkie", categorizer.categorize("Mleko kokosowe z puszki"))
        assertEquals("nabial", categorizer.categorize("Mleko"))
        assertEquals("przyprawy", categorizer.categorize("Masło orzechowe"))
        assertEquals("przyprawy", categorizer.categorize("Papryka słodka mielona"))
        assertEquals("warzywa", categorizer.categorize("Papryka czerwona"))
    }

    @Test
    fun frozenMeansFrozen() {
        assertEquals("mrozonki", categorizer.categorize("truskawki mrożone"))
        assertEquals("mrozonki", categorizer.categorize("mrożony szpinak"))
    }

    @Test
    fun unknownOrEmptyGoesToInne() {
        assertEquals("inne", categorizer.categorize("gwoździe"))
        assertEquals("inne", categorizer.categorize("   "))
        assertEquals("inne", categorizer.categorize("2 kg"))
    }

    @Test
    fun aTieBetweenDepartmentsGoesToInne() {
        val tie = Categorizer(Categorizer.Dictionary(1, mapOf("warzywa" to listOf("abcde"), "napoje" to listOf("abcdf"))))
        assertEquals("inne", tie.categorize("abcd"))
    }

    /**
     * The dictionary answers from the dictionary alone. The user's own corrections — „Moje
     * produkty" and the category memory — are applied by `ListRepository.proposeCategory`
     * (STATE.md decision 90), where `OwnProductsTest` covers them.
     */
    @Test
    fun theDictionaryAnswersFromItself() {
        assertEquals("nabial", categorizer.categorize("mleko  OWSIANE"))
    }

    /**
     * The acceptance criterion: at least 90 of the 100 sample names in the right department,
     * and every miss in „Inne", never in a wrong department.
     */
    @Test
    fun theSampleListIsCategorisedWithoutAWrongDepartment() {
        val sample = javaClass.getResource("/categorizer-sample.tsv")!!.readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { it[0] to it[1] } }
        assertEquals(100, sample.size)

        val results = sample.map { (name, expected) -> Triple(name, expected, categorizer.categorize(name)) }
        val wrong = results.filter { (_, expected, got) -> got != expected && got != BuiltinCategories.FALLBACK }
        val right = results.count { (_, expected, got) -> got == expected }
        assertEquals("wrong department", emptyList<Triple<String, String, String>>(), wrong)
        assertTrue("$right / 100 right; misses: ${results.filter { it.second != it.third }}", right >= 90)
        println("Categorizer sample: $right / 100 right, misses: ${results.filter { it.second != it.third }}")
    }

    @Test
    fun suggestionsStartWithWhatWasTypedEvenWithoutPolishLetters() {
        val ziem = categorizer.suggest("ziem", 5)
        assertEquals("ziemniaki", ziem.first())
        assertTrue(ziem.toString(), ziem.all { TextKey.fold(it).contains("ziem") })
        val zolt = categorizer.suggest("zolt", 5)
        assertTrue(zolt.toString(), zolt.isNotEmpty() && zolt.all { TextKey.fold(it).startsWith("zolt") || TextKey.fold(it).contains(" zolt") })
        // A later word matches too, after the names that start with it.
        assertTrue("cebula czerwona" in categorizer.suggest("czerwona", 20))
        assertEquals(emptyList<String>(), categorizer.suggest("z", 5))
        assertEquals(3, categorizer.suggest("ma", 3).size)
    }
}
