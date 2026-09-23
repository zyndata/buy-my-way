package dev.gorny.buymyway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.gorny.buymyway.core.share.InviteLinks
import dev.gorny.buymyway.data.push.Notifications
import dev.gorny.buymyway.ui.BuyMyWayNavHost
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** An invite link the app was opened with (PLAN.md *Sharing & permissions*), until shown. */
    private val invites = MutableStateFlow<String?>(null)

    /** Text shared into the app (PLAN.md Phase 8, task 2), until the import screen reads it. */
    private val imports = MutableStateFlow<String?>(null)

    /** The list a tapped notification asks for (PLAN.md Phase 9, task 3), until it is opened. */
    private val opens = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a configuration change the link was already handled.
        if (savedInstanceState == null) handle(intent)
        setContent {
            BuyMyWayTheme {
                BuyMyWayNavHost(invites, imports, opens)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val link = intent.dataString
                // An invite link, or a notification this app posted for one of its own lists.
                link?.let(InviteLinks::tokenOf)?.let { invites.value = it }
                    ?: link?.let(Notifications::listIdOf)?.let { opens.value = it }
            }
            // Whatever the share sheet sent: the parser makes a list of any text (Phase 8).
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { imports.value = it }
        }
    }
}
