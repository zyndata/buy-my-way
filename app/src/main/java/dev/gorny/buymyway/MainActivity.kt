package dev.gorny.buymyway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.gorny.buymyway.ui.BuyMyWayNavHost
import dev.gorny.buymyway.ui.theme.BuyMyWayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BuyMyWayTheme {
                BuyMyWayNavHost()
            }
        }
    }
}
