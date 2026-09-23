package dev.gorny.buymyway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.gorny.buymyway.core.share.InviteLinks
import dev.gorny.buymyway.ui.BuyMyWayNavHost
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** An invite link the app was opened with (PLAN.md *Sharing & permissions*), until shown. */
    private val invites = MutableStateFlow<String?>(null)

    /** Text shared into the app (PLAN.md Phase 8, task 2), until the import screen reads it. */
    private val imports = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a configuration change the link was already handled.
        if (savedInstanceState == null) handle(intent)
        setContent {
            BuyMyWayTheme {
                BuyMyWayNavHost(invites, imports)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(InviteLinks::tokenOf)?.let { invites.value = it }
            // Whatever the share sheet sent: the parser makes a list of any text (Phase 8).
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { imports.value = it }
        }
    }
}
