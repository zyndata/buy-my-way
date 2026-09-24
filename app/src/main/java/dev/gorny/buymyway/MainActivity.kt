package dev.gorny.buymyway

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.core.share.InviteLinks
import dev.gorny.buymyway.data.prefs.ThemeChoice
import dev.gorny.buymyway.data.push.Notifications
import dev.gorny.buymyway.ui.BuyMyWayNavHost
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme
import dev.gorny.buymyway.ui.theme.isDarkTheme
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
        val themePrefs = (application as BuyMyWayApp).container.themePrefs
        setContent {
            val choice by themePrefs.choice.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)
            val dark = isDarkTheme(choice)
            // „Motyw" also decides the system bars: forcing the light theme on a phone in dark
            // mode would otherwise leave white icons on a white bar, and the other way round.
            LaunchedEffect(dark) { edgeToEdge(dark) }
            BuyMyWayTheme(darkTheme = dark) {
                BuyMyWayNavHost(invites, imports, opens)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * What `enableEdgeToEdge()` does by default, except that the theme the user chose decides
     * whether the bars are drawn for a dark background, rather than the system's own dark mode.
     * The scrims are only used below Android 10, where three-button navigation needs contrast.
     */
    private fun edgeToEdge(dark: Boolean) = enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
    )

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

    private companion object {
        /** androidx's own defaults, which are not public: a scrim behind the navigation bar. */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
