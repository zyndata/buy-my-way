package dev.gorny.buymyway.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a GitHub Release means to this app (PLAN.md Phase 10, task 3): a version to compare
 * with the installed one, and one APK to download. Pure, so the JVM tests own it — the phone
 * only has to fetch a document and hand the text over.
 */
object Updates {
    /** The repository the app updates itself from. Public, and the only place an APK is taken from. */
    const val REPOSITORY = "zyndata/buy-my-way"

    /** `releases/latest` skips drafts and pre-releases by itself, which is what this wants. */
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    /** How often the app looks (decision 108). Foreground only: nothing schedules this. */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** A release worth offering: a version, a name to show, and the APK to fetch. */
    data class Release(val version: Version, val tag: String, val apkUrl: String, val apkName: String)

    /**
     * A `X.Y.Z` version, which is all this project ever tags. Anything else — `0.0.0-dev`, a
     * hash, a build straight off a branch — parses to null, and a null never compares as
     * older, so a development build is never told to update itself into a release.
     */
    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        override fun toString(): String = "$major.$minor.$patch"
    }

    private val VERSION = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")

    /** `v1.2.3`, `1.2.3` → a version; anything else → null. */
    fun version(text: String?): Version? = text?.trim()?.let { VERSION.matchEntire(it) }?.destructured
        ?.let { (major, minor, patch) -> Version(major.toInt(), minor.toInt(), patch.toInt()) }

    /**
     * The release to offer, or null when there is nothing to offer: the document did not
     * parse, it carries no `X.Y.Z` tag, it has no APK attached, or [installed] is already
     * that version or newer. An unparseable [installed] („0.0.0-dev") is never offered
     * anything either — a build made from a working tree has no business installing over
     * itself.
     */
    fun offer(document: String, installed: String?): Release? {
        val latest = parse(document) ?: return null
        val current = version(installed) ?: return null
        return latest.takeIf { it.version > current }
    }

    /** The parts of a `releases/latest` document this app reads. Null when it is not one. */
    fun parse(document: String): Release? {
        val json = runCatching { Json.parseToJsonElement(document) }.getOrNull() as? JsonObject ?: return null
        val tag = json.text("tag_name") ?: return null
        val version = version(tag) ?: return null
        // A draft is never returned by `releases/latest`, but a hand-made release can still be
        // marked so; nothing is offered from one.
        if (json.text("draft") == "true" || json.text("prerelease") == "true") return null

        val assets = json["assets"] as? JsonArray ?: return null
        val apk = assets.filterIsInstance<JsonObject>().firstOrNull { it.text("name")?.endsWith(".apk") == true }
            ?: return null
        val url = apk.text("browser_download_url") ?: return null
        // Only this repository's own Releases, over HTTPS. A document that names anywhere else
        // is not one this app will download from, whatever else it says.
        if (!url.startsWith("https://github.com/$REPOSITORY/releases/")) return null
        val name = apk.text("name") ?: return null
        return Release(version = version, tag = tag, apkUrl = url, apkName = name)
    }

    /** One string field, or null when it is missing, empty, `null` or not a plain value. */
    private fun JsonObject.text(field: String): String? =
        (this[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
