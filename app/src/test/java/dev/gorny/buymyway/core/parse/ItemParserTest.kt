package dev.gorny.buymyway.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemParserTest {

    private fun one(text: String) = ItemParser.parse(text)

    @Test
    fun aLeadingNumberAndUnitAreTheQuantity() {
        assertEquals(ParsedItem("ziemniaki", 2.0, "kg"), one("2 kg ziemniaki"))
        assertEquals(ParsedItem("sera", 500.0, "g"), one("500g sera"))
        assertEquals(ParsedItem("mleko", 1.5, "l"), one("1,5 l mleko"))
        assertEquals(ParsedItem("jajka", 10.0, "szt."), one("10 szt. jajka"))
        assertEquals(ParsedItem("jajka", 3.0, null), one("3 jajka"))
    }

    @Test
    fun longUnitsAndNumberWordsAreRead() {
        assertEquals(ParsedItem("cebuli", 0.5, "kg"), one("pół kilo cebuli"))
        assertEquals(ParsedItem("mleka", 2.0, null), one("dwa mleka"))
        assertEquals(ParsedItem("wody", 6.0, "l"), one("sześć litrów wody"))
        assertEquals(ParsedItem("ziemniaków", 1.0, "kg"), one("kilo ziemniaków"))
        assertEquals(ParsedItem("ryżu", 2.0, "op."), one("dwa opakowania ryżu"))
    }

    @Test
    fun aTrailingQuantityIsReadToo() {
        assertEquals(ParsedItem("ziemniaki", 2.0, "kg"), one("ziemniaki 2 kg"))
        assertEquals(ParsedItem("masło", 2.0, null), one("masło x2"))
        assertEquals(ParsedItem("masło", 2.0, null), one("masło 2x"))
        assertEquals(ParsedItem("woda", 1.5, "l"), one("woda 1,5l"))
        assertEquals(ParsedItem("mleko", 2.0, null), one("mleko 2"))
    }

    @Test
    fun aNameWithoutQuantityStaysWhole() {
        assertEquals(ParsedItem("Chleb żytni"), one("  Chleb   żytni "))
        assertEquals(ParsedItem("7up"), one("7up"))
        assertEquals(ParsedItem("sos 1000 wysp"), one("sos 1000 wysp"))
    }

    @Test
    fun nothingToBuyIsNotAnItem() {
        assertNull(one(""))
        assertNull(one("   "))
        assertNull(one("2 kg"))
        assertNull(one("2"))
        assertNull(one(",.-"))
    }

    @Test
    fun commasSeparateItemsButNotDecimals() {
        assertEquals(
            listOf(ParsedItem("ziemniaki", 2.0, "kg"), ParsedItem("mleko"), ParsedItem("woda", 1.5, "l")),
            ItemParser.parseAll("2 kg ziemniaki, mleko,, woda 1,5 l"),
        )
        assertEquals(listOf(ParsedItem("chleb"), ParsedItem("masło")), ItemParser.parseAll("chleb;masło\n"))
        assertEquals(listOf(ParsedItem("mleko"), ParsedItem("jajka", 2.0, null)), ItemParser.parseAll("mleko,2 jajka"))
    }

    @Test
    fun anImpossibleQuantityIsPartOfTheName() {
        assertEquals(ParsedItem("0 bułek"), one("0 bułek"))
        assertEquals(ParsedItem("99999 rzeczy"), one("99999 rzeczy"))
    }

    @Test
    fun autocompleteLooksUpAndReplacesTheLastName() {
        assertEquals("ziem", ItemParser.lastName("mleko, 2 kg ziem"))
        assertEquals("", ItemParser.lastName("mleko, 2 kg"))
        assertEquals("mleko, 2 kg ziemniaki", ItemParser.replaceLastName("mleko, 2 kg ziem", "ziemniaki"))
        assertEquals("Mleko", ItemParser.replaceLastName("mle", "Mleko"))
        assertEquals("chleb, Masło", ItemParser.replaceLastName("chleb,ma", "Masło"))
    }
}
