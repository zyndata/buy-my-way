package dev.gorny.buymyway.core.share

import dev.gorny.buymyway.core.text.DateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

class InviteLinksTest {
    private val token = "AbCdEfGhIjKlMnOpQrSt_-"

    @Test
    fun theAppLinkAndItsFallbackBothCarryTheToken() {
        assertEquals("https://eatmyway.gorny.dev/bmw/i/$token", InviteLinks.link(token))
        assertEquals(token, InviteLinks.tokenOf(InviteLinks.link(token)))
        assertEquals(token, InviteLinks.tokenOf("https://EatMyWay.gorny.dev/bmw/i/$token/"))
        assertEquals(token, InviteLinks.tokenOf("buymyway://i/$token"))
    }

    @Test
    fun anythingElseIsNoInvite() {
        assertNull(InviteLinks.tokenOf("https://example.com/bmw/i/$token"))
        assertNull(InviteLinks.tokenOf("http://eatmyway.gorny.dev/bmw/i/$token"))
        assertNull(InviteLinks.tokenOf("https://eatmyway.gorny.dev/privacy.html"))
        assertNull(InviteLinks.tokenOf("https://eatmyway.gorny.dev/bmw/i/short"))
        assertNull(InviteLinks.tokenOf("buymyway://list/$token"))
        assertNull(InviteLinks.tokenOf("not a link at all"))
    }

    @Test
    fun aTokenIs128RandomBitsIn22Characters() {
        val random = Random(7)
        val a = InviteLinks.newToken { random.nextBytes(it) }
        val b = InviteLinks.newToken { random.nextBytes(it) }
        assertEquals(22, a.length)
        assertTrue(InviteLinks.isToken(a))
        assertNotEquals(a, b)
    }

    @Test
    fun datesArePrintedThePolishWayInThePhonesZone() {
        val at = 1_790_060_160_000L // 2026-09-22 06:56 UTC
        assertEquals("22.09.2026, 08:56", DateText.format(at, ZoneId.of("Europe/Warsaw")))
        assertEquals("22.09.2026, 06:56", DateText.format(at, ZoneId.of("UTC")))
    }
}
