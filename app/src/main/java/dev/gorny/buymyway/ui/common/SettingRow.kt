package dev.gorny.buymyway.ui.common

import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A row of Ustawienia, „O aplikacji" and „Moje produkty": Material3's list item with the
 * baseline look these screens were designed and photographed with. Material3 1.5 deprecated
 * that overload in favour of the expressive one, which changes paddings, shapes and type;
 * moving to it is a visual change of its own, so it is kept in this one place until then
 * (STATE.md decision 127).
 */
@Suppress("DEPRECATION")
@Composable
fun SettingRow(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = headlineContent,
        modifier = modifier,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
    )
}
