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
 * The partial eraser's touch (plan T-a), with the freehand rules (ADR 0004, "Gestures"): one finger or a
 * stylus erases along its path, a tap erases a dot; a **second finger** cancels the drag (what it cut comes
 * back) and the fingers zoom and pan until they all lift; while a **stylus** erases, other touches (a palm)
 * are ignored. Runs in the initial pass and consumes everything, so taps, double taps and the page's own zoom
 * and pan don't act; [grab] tells the zoom/pan detector to stand down, as for freehand.
 *
 * Positions are on screen. [onStart] gets the down and says whether the drag erases (false: it is consumed and
 * does nothing; the viewport says false only without a layout, and a drag between pages erases on the one it
 * belongs to, as a stroke does); [onMove] gets every position since the last event, historical ones included, so
 * a fast stroke leaves no gaps; [onEnd] runs when the erasing pointer lifts, [onCancel] when the drag is abandoned.
 */
internal suspend fun PointerInputScope.detectEraserGestures(
    viewport: PdfViewportState,
    grab: FreehandGrab,
    onStart: (Offset) -> Boolean,
    onMove: (List<Offset>) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down.consume()
        viewport.stopAnimation()
        grab.active = true
        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        var erasing = onStart(down.position)
        var navigating = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) {
                    // The lift may carry the end of a fast swipe.
                    val last = event.changes.firstOrNull { it.id == down.id }
                    if (erasing && !navigating && last != null) onMove(last.historical.map { it.position } + last.position)
                    event.changes.forEach { it.consume() }
                    break
                }
                if (!navigating) {
                    val others = event.changes.filter { it.id != down.id && it.pressed }
                    if (others.isNotEmpty() && !stylus) {
                        navigating = true
                        if (erasing) onCancel()
                        erasing = false
                    } else {
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (erasing && change != null) {
                            if (change.pressed) {
                                onMove(change.historical.map { it.position } + change.position)
                            } else {
                                erasing = false
                                onEnd()
                            }
                        }
                        event.changes.forEach { it.consume() }
                        continue
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
            if (erasing) {
                erasing = false
                onEnd()
            }
        } finally {
            // Cancelled (the pointer input restarted, the viewport left): nothing is committed.
            if (erasing) onCancel()
            grab.active = false
            if (navigating) viewport.isInteracting = false
        }
    }
}
