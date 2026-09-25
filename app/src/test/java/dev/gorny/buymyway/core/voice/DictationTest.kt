package dev.gorny.buymyway.core.voice

import dev.gorny.buymyway.core.categorize.Categorizer
import dev.gorny.buymyway.core.categorize.NameIndex
import dev.gorny.buymyway.core.parse.ParsedItem
import dev.gorny.buymyway.core.text.TextKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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

    /** Unit tests run with the module directory as the working directory. */
    private val categorizer = Categorizer.fromJson(File("src/main/assets/products-pl.json").readText())

    private val known = Dictation.KnownNames { words, from -> categorizer.knownNameLength(words, from) }

    /** As a phone hands it over: no commas, and the dictionary to tell the things apart. */
    private fun checkSpoken(utterance: String, vararg expected: ParsedItem) {
        assertEquals("„$utterance”", expected.toList(), Dictation.parse(utterance, known))
    }

    @Test
    fun theDictionaryKnowsWhereANameEnds() {
        fun lengthOf(text: String) = categorizer.knownNameLength(TextKey.words(text), 0)

        assertEquals(1, lengthOf("mleko"))
        assertEquals(1, lengthOf("ziemniaków"))
        assertEquals(2, lengthOf("mleko kokosowe"))
        assertEquals(2, lengthOf("papier toaletowy"))
        assertEquals("a word it does not know", 0, lengthOf("wiejski"))
        assertEquals("a derived adjective is not the fruit", 0, lengthOf("pomarańczowy"))
        assertEquals("a quantity is not a name", 0, lengthOf("dwa"))
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

    /**
     * What a phone actually hands over: Google's Polish recognizer writes no commas at all
     * (seen on an S10e, 2026-09-23). The dictionary says where one thing ends and the next
     * begins, so the same sentence still becomes four items.
     */
    @Test
    fun theSameSentenceWithoutAnyComma() {
        checkSpoken(
            "dwa kilo ziemniaków mleko masło i chleb",
            item("ziemniaków", 2.0, "kg"),
            item("mleko"),
            item("masło"),
            item("chleb"),
        )
    }

    @Test
    fun withoutTheDictionaryTheWordsStayTogether() {
        // The parser alone cuts only where a word says so; the sheet is there to be corrected.
        check(
            "dwa kilo ziemniaków mleko masło i chleb",
            item("ziemniaków mleko masło", 2.0, "kg"),
            item("chleb"),
        )
    }

    @Test
    fun spokenSentencesWithNoPunctuationAtAll() {
        checkSpoken("mleko masło chleb", item("mleko"), item("masło"), item("chleb"))
        checkSpoken("kup mleko chleb", item("mleko"), item("chleb"))
        checkSpoken("pół kilo cebuli dwa ogórki", item("cebuli", 0.5, "kg"), item("ogórki", 2.0))
        checkSpoken("mleko dwa chleby", item("mleko"), item("chleby", 2.0))
        checkSpoken("jajka mleko masło ser chleb", item("jajka"), item("mleko"), item("masło"), item("ser"), item("chleb"))
        checkSpoken("dwa litry mleka sześć jajek", item("mleka", 2.0, "l"), item("jajek", 6.0))
    }

    /** A name of several words is one thing, not two: the longest dictionary entry wins. */
    @Test
    fun namesOfSeveralWordsAreNotCutInHalf() {
        checkSpoken("mleko kokosowe", item("mleko kokosowe"))
        checkSpoken("papier toaletowy", item("papier toaletowy"))
        checkSpoken("sok pomarańczowy", item("sok pomarańczowy"))
        checkSpoken("ser żółty chleb", item("ser żółty"), item("chleb"))
        checkSpoken("mleko kokosowe chleb", item("mleko kokosowe"), item("chleb"))
        checkSpoken("papier toaletowy mleko", item("papier toaletowy"), item("mleko"))
    }

    /** A word the dictionary does not know stays with the name before it. */
    @Test
    fun anUnknownWordDoesNotStartAnItem() {
        checkSpoken("chleb wiejski", item("chleb wiejski"))
        checkSpoken("mleko od Zosi chleb", item("mleko od Zosi"), item("chleb"))
    }

    /**
     * Besides the dictionary, dictation cuts at the names this phone has already seen
     * (STATE.md decision 80), so what someone buys keeps working even if it was never in
     * `products-pl.json`.
     */
    @Test
    fun theNamesThisPhoneHasSeenAreCutAtToo() {
        // As Room holds them: folded, most used first.
        val mine = NameIndex.ofFolded(listOf("chleb wiejski", "kefir malinowy", "dropsy owsiane"))
        val both = Dictation.KnownNames { words, from ->
            maxOf(categorizer.knownNameLength(words, from), mine.lengthAt(words, from))
        }

        assertEquals(
            listOf(item("chleb wiejski"), item("mleko")),
            Dictation.parse("chleb wiejski mleko", both),
        )
        assertEquals(
            listOf(item("dropsy owsiane", 2.0), item("kefir malinowy")),
            Dictation.parse("dwa dropsy owsiane kefir malinowy", both),
        )
        // Two names the dictionary has never heard of are one line without the history.
        assertEquals(
            listOf(item("dropsy owsiane kefir malinowy", 2.0)),
            Dictation.parse("dwa dropsy owsiane kefir malinowy", known),
        )
    }

    /**
     * „Moje produkty" (PLAN.md Phase 8b): the same cut, from the list the user curates on
     * purpose. This is the parser half of the phase's first acceptance criterion — a name the
     * dictionary has never heard of is one item of its own the next time it is dictated.
     */
    @Test
    fun aCuratedNameIsOneItemOfItsOwn() {
        // Before it is curated, two things the dictionary has never heard of are one line.
        assertEquals(
            listOf(item("dropsy owsiane kefir malinowy")),
            Dictation.parse("dropsy owsiane kefir malinowy", known),
        )

        // As Room holds „Moje produkty": the folded name is the key.
        val curated = NameIndex.ofFolded(listOf("dropsy owsiane"))
        val withMine = Dictation.KnownNames { words, from ->
            maxOf(categorizer.knownNameLength(words, from), curated.lengthAt(words, from))
        }
        assertEquals(
            listOf(item("dropsy owsiane"), item("kefir malinowy")),
            Dictation.parse("dropsy owsiane kefir malinowy", withMine),
        )
        // In the middle of a sentence, and with a quantity of its own.
        assertEquals(
            listOf(item("masło"), item("dropsy owsiane", 2.0), item("ziemniaków", 3.0, "kg")),
            Dictation.parse("masło dwa dropsy owsiane trzy kilo ziemniaków", withMine),
        )
    }

    /**
     * What dictation glued together does not become a name (STATE.md decision 120). Two words
     * the dictionary has never heard of are one line; that line is added, and `name_history`
     * remembers it. Adding the two by hand must then be enough to tell them apart — before
     * this, the glued name was the longest match and kept winning.
     */
    @Test
    fun aGluedNameGivesWayToTheTwoNamesItIsMadeOf() {
        val dictionary = { word: String -> categorizer.knownNameLength(listOf(word), 0) > 0 }
        val utterance = "alantan polopiryna"

        // The first dictation: neither word is known, so it is one line.
        assertEquals(listOf(item("alantan polopiryna")), Dictation.parse(utterance, known))

        // That line was added, and then both were added by hand: this is what Room now holds.
        val seen = NameIndex.ofSeen(listOf("alantan polopiryna", "alantan", "polopiryna"), dictionary)
        val both = Dictation.KnownNames { words, from ->
            maxOf(categorizer.knownNameLength(words, from), seen.lengthAt(words, from))
        }
        assertEquals(listOf(item("alantan"), item("polopiryna")), Dictation.parse(utterance, both))

        // A seen name whose second word names nothing is still one thing of its own.
        val mine = NameIndex.ofSeen(listOf("chleb wiejski", "dropsy owsiane"), dictionary)
        val withMine = Dictation.KnownNames { words, from ->
            maxOf(categorizer.knownNameLength(words, from), mine.lengthAt(words, from))
        }
        assertEquals(
            listOf(item("chleb wiejski"), item("dropsy owsiane")),
            Dictation.parse("chleb wiejski dropsy owsiane", withMine),
        )
    }

    /** A name stored with a typo still meets the word as it is said: the stem is what matches. */
    @Test
    fun aTypoInTheHistoryDoesNotBreakTheCut() {
        val mine = NameIndex.ofFolded(listOf("mlekoo", "chlebek"))

        assertEquals(1, mine.lengthAt(listOf("mleko"), 0))
        assertEquals(1, mine.lengthAt(listOf("chleb"), 0))
    }

    /** A quantity at the end belongs to the name before it, not to what follows. */
    @Test
    fun aQuantityThatEndsTheSentenceStaysWithItsName() {
        checkSpoken("mleko dwa litry", item("mleko dwa litry"))
        checkSpoken("ziemniaki 2 kg", item("ziemniaki", 2.0, "kg"))
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
