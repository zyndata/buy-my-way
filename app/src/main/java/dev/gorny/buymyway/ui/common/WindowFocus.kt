package dev.gorny.buymyway.ui.common

import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * STATE.md decision 122. On the S23 (Android 16, One UI), removing a focused window — a bottom
 * sheet, a menu — leaves a moment with no focused window at all, and in that moment the system's
 * own insets target takes over the keyboard and shows it (`ImeTracker`: `onRequestShow at
 * ORIGIN_SERVER reason IME_REQUESTED_CHANGED_LISTENER`); a moment later the list's window gets
 * the focus back and the keyboard goes down again. Seen as a second slide after every sheet.
 *
 * So a window over the list gives its focus back *before* it goes: marked not focusable, it hands
 * the focus to the list's window at once, and its removal then leaves no gap.
 *
 * [rememberWindowFocusHandle] outside the window, [Bind] inside its content, [letGo] just before
 * closing it — or [thenClose] where the window goes in the same frame, as a dialog does.
 */
class WindowFocusHandle {
    internal var view: View? = null

    fun letGo() {
        val view = view ?: return
        val dialogWindow = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        if (dialogWindow != null) {
            // Its content has already slid away, but its window stays until the focus has moved:
            // unseen and letting touches through meanwhile.
            dialogWindow.decorView.alpha = 0f
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            return
        }
        // A popup's root view carries the very params object its window was added with.
        val root = view.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        root.context.getSystemService(WindowManager::class.java)?.updateViewLayout(root, params)
    }
}

/**
 * [WindowFocusHandle.letGo], then [close] once the window underneath has the focus. Call it from
 * that window's composition (outside the dialog). A dialog is removed at once, and the keyboard
 * stays with it until the window underneath has taken the focus and started its input (~50 ms on
 * the S23), so closing any sooner leaves the gap anyway. Never waits longer than [MAX_WAIT_MS].
 */
@Composable
fun WindowFocusHandle.thenClose(): (close: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val underneath = LocalView.current
    return { close ->
        letGo()
        scope.launch {
            withTimeoutOrNull(MAX_WAIT_MS) {
                while (!underneath.hasWindowFocus()) withFrameNanos { }
                withFrameNanos { }
            }
            close()
        }
    }
}

private const val MAX_WAIT_MS = 300L

@Composable
fun rememberWindowFocusHandle(): WindowFocusHandle = remember { WindowFocusHandle() }

/** Inside the window's content: tells the handle which window it is. */
@Composable
fun WindowFocusHandle.Bind() {
    val view = LocalView.current
    SideEffect { this.view = view }
}
