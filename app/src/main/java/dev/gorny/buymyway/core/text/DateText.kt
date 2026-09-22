package dev.gorny.buymyway.core.text

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates as the edit sheet prints them (PLAN.md *Screens*): „22.09.2026, 08:56". */
object DateText {
    private val format = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))

    /** [at] in milliseconds since the epoch, in [zone] (the phone's by default). */
    fun format(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = format.format(Instant.ofEpochMilli(at).atZone(zone))
}
