package dev.gorny.buymyway.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a GitHub Release means to the app (PLAN.md Phase 10, task 3). Everything the update
 * check decides is decided here, so these are the tests that own the feature's judgement: the
 * phone only fetches a document and shows a banner.
 */
class UpdatesTest {
    private fun release(
        tag: String = "v1.1.0",
        asset: String = "buy-my-way-v1.1.0.apk",
        url: String = "https://github.com/zyndata/buy-my-way/releases/download/v1.1.0/buy-my-way-v1.1.0.apk",
        extra: String = "",
    ) = """
        {
          "tag_name": "$tag",
          "name": "$tag",
          "draft": false,
          "prerelease": false,
          $extra
          "assets": [
            {"name": "$asset.sha256", "browser_download_url": "$url.sha256"},
            {"name": "$asset", "browser_download_url": "$url"}
          ]
        }
    """.trimIndent()

    @Test
    fun `a version is read from a tag with or without its v`() {
        assertEquals(Updates.Version(1, 2, 3), Updates.version("v1.2.3"))
        assertEquals(Updates.Version(1, 2, 3), Updates.version("1.2.3"))
        assertEquals(Updates.Version(0, 0, 1), Updates.version(" v0.0.1 "))
    }

    @Test
    fun `anything that is not X_Y_Z is no version at all`() {
        for (text in listOf("", "v1.2", "1.2.3.4", "0.0.0-dev", "v1.2.3-rc1", "main", "vX.Y.Z", null)) {
            assertNull("„$text" + "\" should not parse", Updates.version(text))
        }
    }

    @Test
    fun `versions compare by number, not as text`() {
        // The text comparison this guards against would put 1.10.0 before 1.9.0.
        assertTrue(Updates.version("v1.10.0")!! > Updates.version("v1.9.0")!!)
        assertTrue(Updates.version("v2.0.0")!! > Updates.version("v1.99.99")!!)
        assertTrue(Updates.version("v1.0.1")!! > Updates.version("v1.0.0")!!)
        assertEquals(0, Updates.version("v1.0.0")!!.compareTo(Updates.version("1.0.0")!!))
    }

    @Test
    fun `a newer release is offered, with the APK asset and not its checksum`() {
        val offer = Updates.offer(release(), installed = "1.0.0")
        assertEquals(Updates.Version(1, 1, 0), offer?.version)
        assertEquals("v1.1.0", offer?.tag)
        assertEquals("buy-my-way-v1.1.0.apk", offer?.apkName)
        assertTrue(offer?.apkUrl?.endsWith("/buy-my-way-v1.1.0.apk") == true)
    }

    @Test
    fun `the running version and an older one are not offered`() {
        assertNull(Updates.offer(release(), installed = "1.1.0"))
        assertNull(Updates.offer(release(tag = "v1.0.0"), installed = "1.1.0"))
    }

    @Test
    fun `a development build is never offered a release`() {
        // `0.0.0-dev` is what a build from a working tree calls itself; it has no business
        // installing anything over itself.
        assertNull(Updates.offer(release(), installed = "0.0.0-dev"))
        assertNull(Updates.offer(release(), installed = null))
    }

    @Test
    fun `a draft or a pre-release is not offered`() {
        assertNull(Updates.offer(release(extra = "\"draft\": true,"), installed = "1.0.0"))
        assertNull(Updates.offer(release(extra = "\"prerelease\": true,"), installed = "1.0.0"))
    }

    @Test
    fun `a release with no APK on it is not offered`() {
        val noApk = """{"tag_name": "v1.1.0", "assets": [{"name": "notes.txt", "browser_download_url": "x"}]}"""
        assertNull(Updates.offer(noApk, installed = "1.0.0"))
        assertNull(Updates.offer("""{"tag_name": "v1.1.0", "assets": []}""", installed = "1.0.0"))
    }

    @Test
    fun `an APK hosted anywhere but this repository's releases is refused`() {
        // The whole point of the check: the app installs what its own Releases page carries,
        // and a document that names somewhere else is not acted on, whatever it claims.
        for (url in listOf(
            "https://example.com/buy-my-way-v1.1.0.apk",
            "http://github.com/zyndata/buy-my-way/releases/download/v1.1.0/buy-my-way-v1.1.0.apk",
            "https://github.com/someone-else/buy-my-way/releases/download/v1.1.0/buy-my-way-v1.1.0.apk",
            "https://github.com.evil.example/zyndata/buy-my-way/releases/x.apk",
        )) {
            assertNull(url, Updates.offer(release(url = url), installed = "1.0.0"))
        }
    }

    @Test
    fun `junk is not a release and never throws`() {
        for (document in listOf("", "   ", "not json", "[]", "null", "{}", """{"tag_name": null}""", "{\"tag_name\": \"v1.1.0\"}")) {
            assertNull(document, Updates.offer(document, installed = "1.0.0"))
        }
    }
}
