package dev.gorny.buymyway.ui.common

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.gorny.buymyway.R

/**
 * Drag-to-reorder for a `LazyColumn`, written in-house (STATE.md decisions 45 and 49): the lists
 * on the home screen, the items of a category and the category order editor.
 *
 * A drag starts on a row's [DragHandle]. While it moves, the row follows the finger; when its
 * centre crosses a row it may take the place of ([canMove]), [onMove] reorders the data at once
 * and the row stays under the finger. [onDrop] is called once at the end: that is where the
 * new order is saved, as one change. Keys are the `LazyColumn` item keys.
 */
class ReorderState(
    private val listState: LazyListState,
    private val canMove: (from: Any, to: Any) -> Boolean,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDrop: (key: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    var offset by mutableFloatStateOf(0f)
        private set

    /** Index of the dragged row when it last moved; no new move until the layout catches up. */
    private var movedFrom: Int? = null

    fun start(key: Any) {
        draggingKey = key
        offset = 0f
        movedFrom = null
    }

    fun drag(delta: Float) {
        val key = draggingKey ?: return
        offset += delta
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        if (current.index == movedFrom) return
        movedFrom = null
        val centre = current.offset + offset + current.size / 2f
        val target = visible.firstOrNull {
            it.key != key && centre >= it.offset && centre < it.offset + it.size && canMove(key, it.key)
        } ?: return
        onMove(key, target.key)
        // Where the row will be laid out after the move, so it stays under the finger.
        val newOffset = if (target.index > current.index) target.offset + target.size - current.size else target.offset
        offset -= newOffset - current.offset
        movedFrom = current.index
    }

    fun end() {
        val key = draggingKey ?: return
        draggingKey = null
        offset = 0f
        movedFrom = null
        onDrop(key)
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    canMove: (from: Any, to: Any) -> Boolean,
    onMove: (from: Any, to: Any) -> Unit,
    onDrop: (key: Any) -> Unit,
): ReorderState {
    val currentCanMove by rememberUpdatedState(canMove)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDrop by rememberUpdatedState(onDrop)
    return remember(listState) {
        ReorderState(
            listState,
            canMove = { a, b -> currentCanMove(a, b) },
            onMove = { a, b -> currentOnMove(a, b) },
            onDrop = { currentOnDrop(it) },
        )
    }
}

/** The row being dragged floats above the others and follows the finger; the rest animate. */
fun Modifier.reorderableItem(scope: LazyItemScope, state: ReorderState, key: Any): Modifier =
    if (state.draggingKey == key) {
        zIndex(1f).graphicsLayer {
            translationY = state.offset
            shadowElevation = 8.dp.toPx()
        }
    } else {
        with(scope) { animateItem() }
    }

/**
 * The ⋮⋮ handle a drag starts on. Invisible to TalkBack: the row offers „Przesuń wyżej" and
 * „Przesuń niżej" instead ([moveActions]).
 */
@Composable
fun DragHandle(state: ReorderState, key: Any, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clearAndSetSemantics { testTag = "drag:$key" }
            .pointerInput(state, key) {
                detectVerticalDragGestures(
                    onDragStart = { state.start(key) },
                    onDragEnd = { state.end() },
                    onDragCancel = { state.end() },
                ) { change, amount ->
                    change.consume()
                    state.drag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null)
    }
}

/** TalkBack's way to reorder a row: the actions that move it one place up or down. */
fun moveActions(upLabel: String, downLabel: String, onUp: (() -> Unit)?, onDown: (() -> Unit)?): List<CustomAccessibilityAction> =
    listOfNotNull(
        onUp?.let { CustomAccessibilityAction(upLabel) { it(); true } },
        onDown?.let { CustomAccessibilityAction(downLabel) { it(); true } },
    )
