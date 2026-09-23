package dev.gorny.buymyway.core.voice

import dev.gorny.buymyway.core.parse.ParsedItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dictation parser (PLAN.md Phase 7, task 2: „60+ unit test cases from real utterances").
 * Every case is written the way Android's Polish recognizer hands text over: lower case except
 * the first word, numbers as digits or as words, a full stop at the end now and then, and no
 * commas at all in a spoken sentence.
 */
class DictationTest {

    private fun item(name: String, quantity: Double? = null, unit: String? = null) = ParsedItem(name, quantity, unit)

    private fun check(utterance: String, vararg expected: ParsedItem) {
        assertEquals("„$utterance”", expected.toList(), Dictation.parse(utterance))
    }

    // --- The acceptance criterion ---------------------------------------------------------

    @Test
    fun theSentenceFromThePlanBecomesFourItems() {
        check(
            "dwa kilo ziemniaków, mleko, masło i chleb",
            item("ziemniaków", 2.0, "kg"),
            item("mleko"),
            item("masło"),
            item("chleb"),
        )
    }

    @Test
    fun theSameSentenceWithoutAnyComma() {
        check(
            "dwa kilo ziemniaków mleko masło i chleb",
            // With nothing between them the middle words are one name, which the sheet is for.
            item("ziemniaków mleko masło", 2.0, "kg"),
            item("chleb"),
        )
    }

    // --- Separators -----------------------------------------------------------------------

    @Test
    fun separators() {
        check("mleko i chleb", item("mleko"), item("chleb"))
        check("mleko oraz chleb", item("mleko"), item("chleb"))
        check("mleko, chleb", item("mleko"), item("chleb"))
        check("mleko; chleb", item("mleko"), item("chleb"))
        check("mleko\nchleb", item("mleko"), item("chleb"))
        check("mleko jeszcze chleb", item("mleko"), item("chleb"))
        check("mleko plus chleb", item("mleko"), item("chleb"))
        check("mleko, chleb i masło", item("mleko"), item("chleb"), item("masło"))
        check("mleko a także chleb", item("mleko"), item("chleb"))
        check("mleko, chleb, masło, ser", item("mleko"), item("chleb"), item("masło"), item("ser"))
    }

    @Test
    fun aSeparatorInsideANameIsStillASeparator() {
        // „sól i pieprz" really is two things, and that is the useful reading here.
        check("sól i pieprz", item("sól"), item("pieprz"))
    }

    @Test
    fun aTrailingSeparatorIsNotPartOfTheName() {
        check("mleko i", item("mleko"))
        check("mleko oraz", item("mleko"))
    }

    // --- Openers and closers ---------------------------------------------------------------

    @Test
    fun whatAPersonSaysBeforeTheList() {
        check("kup mleko", item("mleko"))
        check("kup jeszcze mleko", item("mleko"))
        check("dodaj mleko", item("mleko"))
        check("dopisz chleb", item("chleb"))
        check("potrzebuję mleka", item("mleka"))
        check("potrzebujemy chleba", item("chleba"))
        check("poproszę mleko", item("mleko"))
        check("trzeba kupić masło", item("masło"))
        check("weź dwa mleka", item("mleka", 2.0))
        check("brakuje nam cukru", item("cukru"))
        check("i jeszcze chleb", item("chleb"))
        check("no i mleko", item("mleko"))
    }

    @Test
    fun whatAPersonSaysAfterIt() {
        check("mleko proszę", item("mleko"))
        check("chleb też", item("chleb"))
        check("kup mleko i chleb proszę", item("mleko"), item("chleb"))
    }

    @Test
    fun anOpenerAloneNamesNothing() {
        check("kup")
        check("dodaj")
        check("")
        check("   ")
    }

    // --- Quantities: a number before the name ----------------------------------------------

    @Test
    fun numberWords() {
        check("dwa mleka", item("mleka", 2.0))
        check("trzy bułki", item("bułki", 3.0))
        check("cztery jajka", item("jajka", 4.0))
        check("pięć jabłek", item("jabłek", 5.0))
        check("sześć piw", item("piw", 6.0))
        check("dziesięć jajek", item("jajek", 10.0))
        check("dwanaście jajek", item("jajek", 12.0))
        check("dwadzieścia deko szynki", item("szynki", 20.0, "dag"))
        check("jedna cebula", item("cebula", 1.0))
        check("pół kilo cebuli", item("cebuli", 0.5, "kg"))
        check("półtora litra mleka", item("mleka", 1.5, "l"))
    }

    @Test
    fun digitsTheRecognizerWrites() {
        check("2 mleka", item("mleka", 2.0))
        check("2 kg ziemniaków", item("ziemniaków", 2.0, "kg"))
        check("500 g sera", item("sera", 500.0, "g"))
        check("500g sera", item("sera", 500.0, "g"))
        check("1,5 l mleka", item("mleka", 1.5, "l"))
        check("1.5 l mleka", item("mleka", 1.5, "l"))
        check("250 ml śmietany", item("śmietany", 250.0, "ml"))
        check("3 sztuki bułek", item("bułek", 3.0, "szt."))
        check("2 opakowania makaronu", item("makaronu", 2.0, "op."))
    }

    @Test
    fun unitsInEveryFormAreWrittenTheShortWay() {
        check("kilo ziemniaków", item("ziemniaków", 1.0, "kg"))
        check("dwa kilogramy mąki", item("mąki", 2.0, "kg"))
        check("trzy kilograma jabłek", item("jabłek", 3.0, "kg"))
        check("litr mleka", item("mleka", 1.0, "l"))
        check("dwa litry soku", item("soku", 2.0, "l"))
        check("dziesięć deka sera", item("sera", 10.0, "dag"))
        check("pięć gramów drożdży", item("drożdży", 5.0, "g"))
    }

    // --- Quantities: a number after the name -----------------------------------------------

    @Test
    fun aQuantityAfterTheName() {
        check("ziemniaki 2 kg", item("ziemniaki", 2.0, "kg"))
        check("mleko 2", item("mleko", 2.0))
        check("masło x2", item("masło", 2.0))
        check("ser 200 g", item("ser", 200.0, "g"))
        check("mleko dwa litry i chleb", item("mleko dwa litry"), item("chleb"))
    }

    @Test
    fun timesSpokenAsAWord() {
        check("dwa razy mleko", item("mleko", 2.0))
        check("3 razy bułka", item("bułka", 3.0))
        check("dwa razy mleko i chleb", item("mleko", 2.0), item("chleb"))
    }

    // --- What speech puts around the words --------------------------------------------------

    @Test
    fun sentencePunctuation() {
        check("mleko.", item("mleko"))
        check("Kup mleko i chleb.", item("mleko"), item("chleb"))
        check("mleko!", item("mleko"))
        check("mleko?", item("mleko"))
        check("mleko , chleb", item("mleko"), item("chleb"))
        check("  mleko  ,  chleb  ", item("mleko"), item("chleb"))
    }

    @Test
    fun theFirstWordKeepsItsCapitalLetter() {
        check("Mleko i chleb", item("Mleko"), item("chleb"))
    }

    @Test
    fun namesOfSeveralWordsStayWhole() {
        check("mleko kokosowe i ser żółty", item("mleko kokosowe"), item("ser żółty"))
        check("dwa opakowania mąki tortowej", item("mąki tortowej", 2.0, "op."))
        check("papier toaletowy", item("papier toaletowy"))
    }

    @Test
    fun aQuantityWithNoNameIsNotAnItem() {
        check("dwa kilo")
        check("2 kg")
        check("500 g")
    }

    @Test
    fun aRealShoppingSentence() {
        check(
            "kup proszę dwa kilo ziemniaków, pół kilo cebuli, mleko, sześć jajek i chleb",
            item("ziemniaków", 2.0, "kg"),
            item("cebuli", 0.5, "kg"),
            item("mleko"),
            item("jajek", 6.0),
            item("chleb"),
        )
    }

    @Test
    fun anotherRealShoppingSentence() {
        check(
            "potrzebujemy masło, 200 g szynki, dwa jogurty naturalne oraz papier toaletowy",
            item("masło"),
            item("szynki", 200.0, "g"),
            item("jogurty naturalne", 2.0),
            item("papier toaletowy"),
        )
    }

    @Test
    fun segmentsKeepTheOrderTheyWereSaidIn() {
        assertEquals(
            listOf("mleko", "chleb", "masło"),
            Dictation.segments("mleko, chleb i masło"),
        )
    }
}
