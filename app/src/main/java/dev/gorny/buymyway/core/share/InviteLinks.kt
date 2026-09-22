package dev.gorny.buymyway.core.share

import java.net.URI
import java.util.Base64

/**
 * Invite links (PLAN.md *Sharing & permissions*, STATE.md decisions 35 and 63): the App Link
 * `https://eatmyway.gorny.dev/bmw/i/<token>` and its fallback `buymyway://i/<token>`, which
 * the host's page opens. The token is 128 random bits in URL-safe base64 without padding.
 */
object InviteLinks {
    const val HOST = "eatmyway.gorny.dev"
    const val PATH = "/bmw/i/"
    const val SCHEME = "buymyway"
    private const val TOKEN_BYTES = 16
    private val tokenShape = Regex("^[A-Za-z0-9_-]{22}$")

    fun link(token: String): String = "https://$HOST$PATH$token"

    /** A new token from [random] bytes (a `SecureRandom` in the app). */
    fun newToken(random: (ByteArray) -> Unit): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isToken(text: String): Boolean = tokenShape.matches(text)

    /** The token in an invite link, or null for anything else. */
    fun tokenOf(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val candidate = when (uri.scheme?.lowercase()) {
            "https" -> uri.path?.takeIf { uri.host.equals(HOST, ignoreCase = true) && it.startsWith(PATH) }?.removePrefix(PATH)
            SCHEME -> uri.path?.takeIf { uri.host.equals("i", ignoreCase = true) }?.removePrefix("/")
            else -> null
        }
        return candidate?.trimEnd('/')?.takeIf(::isToken)
    }
}
