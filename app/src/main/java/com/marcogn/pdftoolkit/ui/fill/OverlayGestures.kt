package com.marcogn.pdftoolkit.ui.fill

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.pdf.render.UserTransform

/**
 * Tells the page's own zoom and pan that an overlay has the gesture. A plain flag, not state: it
 * is read from another pointer handler on every event, and nothing needs to redraw when it changes.
 */
internal class OverlayGrab {
    @Volatile
    var active = false
}

/**
 * Moving, resizing and rotating an overlay with the fingers (spec §6.5):
 * - a touch that starts on the **selected** overlay (with a margin, so a tick is easy to hit) drags
 *   it with one finger and resizes and turns it with two;
 * - **press and hold** on any other overlay selects it and then drags it, so a placed item can be
 *   nudged without selecting first.
 * - the **corner handle** of the selected overlay ([handleHit]) scales and turns it with one finger.
 * A touch anywhere else is left to the page (zoom, pan, taps). Changes are shown live through
 * [onLive] and committed once when the fingers lift ([onCommit]), so one gesture is one undo step.
 *
 * [hit] gives the overlay a touch at a screen point can grab (any overlay when `anyOverlay`, only
 * the selected one otherwise); [toUser] maps a screen point to the user space of the overlay's page.
 */
internal suspend fun PointerInputScope.detectOverlayGestures(
    grab: OverlayGrab,
    hit: (screen: Offset, anyOverlay: Boolean) -> Overlay?,
    toUser: (Overlay, Offset) -> Offset?,
    handleHit: (screen: Offset) -> Overlay?,
    onGrabbed: (Overlay) -> Unit,
    apply: (Overlay, UserTransform) -> Overlay,
    onLive: (Overlay) -> Unit,
    onCommit: (Overlay) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The corner handle of the selected overlay: one finger scales and turns it about its centre (plan U11).
        val handled = handleHit(down.position)
        if (handled != null) {
            grab.active = true
            try {
                var current: Overlay = handled
                var moved = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    val to = toUser(current, change.position)
                    val from = toUser(current, change.previousPosition)
                    if (to == null || from == null || !change.positionChanged()) continue
                    val center = Offset(current.box.centerX, current.box.centerY)
                    current = apply(current, UserTransform.about(center, from, to))
                    moved = true
                    onLive(current)
                    change.consume()
                }
                if (moved) onCommit(current)
            } finally {
                grab.active = false
            }
            return@awaitEachGesture
        }
        var target = hit(down.position, false)
        var engaged = false
        if (target == null) {
            target = hit(down.position, true) ?: return@awaitEachGesture
            var aborted = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (!aborted) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || event.changes.count { it.pressed } > 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        aborted = true
                    }
                }
            }
            if (aborted) return@awaitEachGesture
            onGrabbed(target)
            engaged = true
        }
        grab.active = true
        try {
            var current: Overlay = target
            var travel = Offset.Zero
            val engageDistance = viewConfiguration.touchSlop / 2f
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                val a = pressed[0]
                val b = pressed.getOrNull(1)
                if (!engaged) {
                    travel += a.position - a.previousPosition
                    if (b != null || travel.getDistance() > engageDistance) engaged = true
                }
                if (!engaged) continue
                val toA = toUser(current, a.position)
                val fromA = toUser(current, a.previousPosition)
                if (toA == null || fromA == null) continue
                val transform = if (b == null) {
                    UserTransform.drag(fromA, toA)
                } else {
                    val toB = toUser(current, b.position)
                    val fromB = toUser(current, b.previousPosition)
                    if (toB == null || fromB == null) continue
                    UserTransform.pinch(fromA, fromB, toA, toB)
                }
                current = apply(current, transform)
                onLive(current)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (engaged) onCommit(current)
        } finally {
            grab.active = false
        }
    }
}
