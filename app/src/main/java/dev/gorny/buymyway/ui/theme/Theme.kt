package dev.gorny.buymyway.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.gorny.buymyway.data.prefs.ThemeChoice

private val Green = Color(0xFF2E7D32)
private val GreenLight = Color(0xFF81C784)

private val LightColors = lightColorScheme(primary = Green, secondary = Green)
private val DarkColors = darkColorScheme(primary = GreenLight, secondary = GreenLight)

/** What „Motyw" in Ustawienia means for the colours: the system's answer, or the user's own. */
@Composable
fun isDarkTheme(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}

/** Dark theme follows [isDarkTheme]; dynamic colour on Android 12+, the green brand below that. */
@Composable
fun BuyMyWayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
