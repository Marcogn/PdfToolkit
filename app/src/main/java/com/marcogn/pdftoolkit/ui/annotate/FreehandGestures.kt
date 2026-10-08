package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState

/**
 * Tells the page's own zoom, pan and taps that a freehand gesture has the touch, as
 * [SelectionGrab] does for text selection. A plain flag: read from other pointer handlers, nothing
 * redraws on it.
 */
internal class FreehandGrab {
    @Volatile
    var active = false
}

/**
 * Draw versus zoom and pan while a freehand tool is armed (spec §7.4). Runs in the **initial**
 * pass, before the ink layer (a child of the page) sees the events, and decides by consuming:
 * the ink layer cancels a stroke whose events arrive consumed, and never starts one on a consumed
 * down.
 * - One finger, or a stylus, **draws**: the events are left to the ink layer.
 * - A **second finger** while a finger draws: the stroke is cancelled and the fingers zoom and pan
 *   the page until they all lift (no fling, nothing is drawn).
 * - While a **stylus** draws, other touches (a resting palm) are ignored.
 *
 * [onStrokeStart] runs on the first down (its position on screen), before the ink layer starts the
 * stroke, so it can set the transforms of the stroke from the current zoom. [grab] is set for the
 * whole gesture.
 */
internal suspend fun PointerInputScope.detectFreehandGestures(
    viewport: PdfViewportState,
    grab: FreehandGrab,
    onStrokeStart: (Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        viewport.stopAnimation()
        onStrokeStart(down.position)
        grab.active = true
        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        var navigating = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
                if (!navigating) {
                    val others = event.changes.filter { it.id != down.id }
                    when {
                        others.none { it.pressed } -> continue
                        stylus -> {
                            others.forEach { it.consume() }
                            continue
                        }
                        else -> navigating = true
                    }
                }
                // Every time: the page's own detector, standing down, clears it after this pass.
                viewport.isInteracting = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f) viewport.zoomBy(zoom, event.calculateCentroid(useCurrent = false))
                if (pan != Offset.Zero) viewport.panBy(pan)
                event.changes.forEach { it.consume() }
            }
        } finally {
            grab.active = false
            if (navigating) viewport.isInteracting = false
        }
    }
}
