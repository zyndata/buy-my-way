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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a configuration change the link was already handled.
        if (savedInstanceState == null) handle(intent)
        setContent {
            BuyMyWayTheme {
                BuyMyWayNavHost(invites)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(InviteLinks::tokenOf)?.let { invites.value = it }
    }
}
