package dev.gorny.buymyway.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * Material's [DropdownMenu], giving its popup's focus back before the popup goes (STATE.md
 * decision 122): closed by „Wstecz" or a tap outside, or by a choice that sets [expanded] to
 * false. Otherwise, on Android 16, the moment the popup is removed the system shows the keyboard
 * for a flash. The popup stays for its fade-out, so the hand-over has time to land.
 */
@Composable
fun FocusSafeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = rememberWindowFocusHandle()
    LaunchedEffect(expanded) { if (!expanded) focus.letGo() }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            focus.letGo()
            onDismissRequest()
        },
        modifier = modifier,
    ) {
        focus.Bind()
        content()
    }
}
