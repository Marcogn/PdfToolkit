package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalViewConfiguration
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.QuickSlots
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tool being dragged toward the quick slots (plan T-b): which tool, where it came from (a slot, or the drawer
 * when [from] is null) and where the finger is, in root coordinates. Drawn by [ToolDragGhost].
 */
@Stable
class ToolDragState {
    var tool by mutableStateOf<DrawerTool?>(null)
        private set
    var from by mutableStateOf<Int?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set

    val active: Boolean get() = tool != null

    fun start(tool: DrawerTool, from: Int?, position: Offset) {
        this.tool = tool
        this.from = from
        this.position = position
    }

    fun move(position: Offset) {
        this.position = position
    }

    fun end() {
        tool = null
        from = null
    }
}

/** Where the quick slots and the bar are on screen, so a drop can be told apart (root coordinates). */
class ToolBarGeometry {
    val slots: Array<Rect?> = arrayOfNulls(QuickSlots.SLOT_COUNT)
    var bar: Rect? = null

    fun slotAt(position: Offset): Int? = slots.indexOfFirst { it?.contains(position) == true }.takeIf { it >= 0 }

    fun overBar(position: Offset): Boolean = bar?.contains(position) == true
}

/**
 * The three touches of a tool in the drawer or in a quick slot (plan T-b): a **tap** ([onTap]); **hold still**
 * past the long-press time and let go ([onHold]); **hold and move** past the touch slop, which drags the tool
 * ([onDragStart] .. [onDrag] .. [onDragEnd], positions in root coordinates). Moving before the hold time is up
 * is neither: it is a scroll of the bar, and nothing happens. [holdAndDrag] false makes it a plain tap.
 */
@Composable
fun Modifier.toolGestures(
    holdAndDrag: Boolean = true,
    onTap: () -> Unit,
    onHold: () -> Unit = {},
    onDragStart: (Offset) -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: (Offset) -> Unit = {},
    onDragCancel: () -> Unit = {},
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val configuration = LocalViewConfiguration.current
    return this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(holdAndDrag) {
            fun root(local: Offset): Offset = coordinates?.let { it.positionInRoot() + local } ?: local
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = configuration.touchSlop
                // Until the hold time is up: a lift is a tap, a move is not ours.
                val early = withTimeoutOrNull(if (holdAndDrag) configuration.longPressTimeoutMillis else Long.MAX_VALUE) {
                    var result: Early? = null
                    while (result == null) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        result = when {
                            change == null || change.isConsumed -> Early.CANCELLED
                            change.changedToUp() -> Early.LIFTED
                            (change.position - down.position).getDistance() > slop -> Early.CANCELLED
                            else -> null
                        }
                        if (result == Early.LIFTED) change?.consume()
                    }
                    result
                }
                when (early) {
                    Early.LIFTED -> tap()
                    Early.CANCELLED -> Unit
                    null -> {
                        // Held. Let go without moving: the bubble; move on: the drag.
                        var dragging = false
                        var finished = false
                        try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || (change.isConsumed && !dragging)) break
                            if (change.changedToUp()) {
                                change.consume()
                                finished = true
                                if (dragging) dragEnd(root(change.position)) else hold()
                                break
                            }
                            if (!dragging && (change.position - down.position).getDistance() > slop) {
                                dragging = true
                                dragStart(root(change.position))
                            } else if (dragging) {
                                drag(root(change.position))
                            }
                            if (dragging) change.consume()
                        }
                        } finally {
                            // Cancelled mid-drag (the drawer closed, Back...): the drag must not stay on screen.
                            if (dragging && !finished) dragCancel()
                        }
                    }
                }
            }
        }
}

private enum class Early { LIFTED, CANCELLED }
