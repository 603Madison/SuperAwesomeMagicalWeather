package app.northvane.weather.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex

/**
 * A short list (inside a scrolling page) whose rows can be dragged into a new
 * order after a long press. The new order is handed to [onReorder] when the
 * finger lifts. TalkBack users get "Move up" and "Move down" actions instead.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    var local by remember(items) { mutableStateOf(items) }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val haptics = LocalHapticFeedback.current

    fun move(from: Int, to: Int): List<T> = local.toMutableList().apply { add(to, removeAt(from)) }

    Column(modifier) {
        local.forEachIndexed { index, item ->
            val k = key(item)
            val dragging = draggingKey == k
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .onSizeChanged { heights[k] = it.height }
                    .graphicsLayer { translationY = if (dragging) offset else 0f }
                    .semantics {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") { onReorder(move(index, index - 1)); true })
                            if (index < local.lastIndex) add(CustomAccessibilityAction("Move down") { onReorder(move(index, index + 1)); true })
                        }
                    }
                    // Keyed on the list too: the gesture must see the current order, not the one from its first composition.
                    .pointerInput(k, items) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = k
                                offset = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.y
                                val i = local.indexOfFirst { key(it) == k }
                                // Swap with a neighbour once dragged past half of its height.
                                val next = local.getOrNull(i + 1)
                                val prev = local.getOrNull(i - 1)
                                if (next != null && offset > (heights[key(next)] ?: Int.MAX_VALUE) / 2f) {
                                    offset -= heights[key(next)] ?: 0
                                    local = move(i, i + 1)
                                } else if (prev != null && offset < -(heights[key(prev)] ?: Int.MAX_VALUE) / 2f) {
                                    offset += heights[key(prev)] ?: 0
                                    local = move(i, i - 1)
                                }
                            },
                            onDragEnd = {
                                draggingKey = null
                                offset = 0f
                                if (local != items) onReorder(local)
                            },
                            onDragCancel = {
                                draggingKey = null
                                offset = 0f
                                local = items
                            },
                        )
                    },
            ) { row(item, dragging) }
        }
    }
}
