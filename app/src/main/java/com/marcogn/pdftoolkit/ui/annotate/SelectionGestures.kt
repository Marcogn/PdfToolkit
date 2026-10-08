package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells the page's own zoom and pan that the selection has the gesture. A plain flag, not state:
 * it is read from another pointer handler on every event, and nothing needs to redraw.
 */
internal class SelectionGrab {
    @Volatile
    var active = false
}

/**
 * Selecting text with the fingers (spec §7.4):
 * - a touch that starts on a **handle** drags it; the handle's anchor follows the finger at the
 *   distance it was grabbed, so the finger doesn't cover the text being selected;
 * - **press and hold** (without moving) calls [onLongPress], which starts a selection on the word
 *   there; the finger can then keep going to stretch it, which moves the end handle.
 * Any other touch is left to the page (zoom, pan, taps). While the selection owns a gesture,
 * [grab] is set, so the page's own detector stands down.
 *
 * [handleAt] gives the handle at a screen point, if any; [onDrag] receives the handle and the
 * screen point its anchor is now at; [onRelease] is called when the finger that held the selection lifts.
 */
internal suspend fun PointerInputScope.detectSelectionGestures(
    grab: SelectionGrab,
    handleAt: (screen: Offset) -> HandleGrab?,
    onLongPress: (screen: Offset) -> Unit,
    onDrag: (SelectionHandle, screen: Offset) -> Unit,
    onRelease: () -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val grabbed = handleAt(down.position)
        var handle = grabbed?.handle
        val toAnchor = grabbed?.toAnchor ?: Offset.Zero
        if (handle == null) {
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
            // Lifted or moved before the timeout: an ordinary tap or pan, not ours.
            if (aborted) return@awaitEachGesture
            onLongPress(down.position)
            handle = SelectionHandle.END
        }
        grab.active = true
        var lifted = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    lifted = true
                    break
                }
                onDrag(handle, change.position + toAnchor)
                change.consume()
            }
        } finally {
            grab.active = false
        }
        if (lifted) onRelease()
    }
}
