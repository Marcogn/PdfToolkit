package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.core.graphics.withMatrix
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.StrokeInput
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.pdf.annotations.DocumentStrokes
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.ui.annotate.FreehandInk.toFreehandStroke
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState

/**
 * The ink layer of a viewport (spec §7.4, plan V-a). Strokes are made in **document points**
 * ([DocumentStrokes]; [pointerToStroke] is set by the viewport as each stroke starts) and handed to
 * [onStroke] with the page they belong to (index in the viewport's layout) in that page's display
 * points, from where they go to user space.
 *
 * The stroke being drawn is a [WetStroke] drawn here, in Compose, clipped to page [clipPage] (where
 * the stroke started), as the finished annotation is clipped to its page. Not `androidx.ink`'s
 * `InProgressStrokes`: its front-buffered layer showed nothing until the pen lifted on a Galaxy Tab
 * S7+ (`docs/decisions.md`); this costs about a frame of latency.
 *
 * Takes one pointer at a time. Like `InProgressStrokes`, it never starts on a consumed down and
 * cancels the stroke when its events arrive consumed: [detectFreehandGestures] decides draw versus
 * zoom by consuming in the initial pass.
 */
@Composable
internal fun ViewportInkLayer(
    state: PdfViewportState,
    kind: FreehandKind,
    color: AnnotationColor,
    width: Float,
    pointerToStroke: Matrix,
    clipPage: Int,
    onStroke: (pageIndex: Int, stroke: FreehandStroke) -> Unit,
) {
    val currentKind by rememberUpdatedState(kind)
    val currentColor by rememberUpdatedState(color)
    val currentWidth by rememberUpdatedState(width)
    val currentOnStroke by rememberUpdatedState(onStroke)
    val wet = remember { WetStroke() }
    val renderer = remember { CanvasStrokeRenderer.create() }
    // Stroke space → screen, fixed for a stroke (zoom and pan don't move while drawing).
    val strokeToScreen = remember { android.graphics.Matrix() }
    // Bumped on every change of the wet stroke: the Canvas reads it, so only drawing is invalidated.
    val revision = remember { mutableIntStateOf(0) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val brush = FreehandInk.brush(currentKind, currentColor, currentWidth, state.mapper?.screenPxPerPoint ?: 1f)
                    val inverse = Matrix(pointerToStroke.values.copyOf()).apply { invert() }
                    strokeToScreen.setFrom(inverse)
                    wet.start(brush, down.sample(down.position, down.uptimeMillis, pointerToStroke))
                    down.consume()
                    revision.intValue++
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (change.isConsumed) {
                                wet.cancel()
                                break
                            }
                            if (!change.pressed) {
                                if (change.positionChanged()) wet.add(change.samples(pointerToStroke))
                                change.consume()
                                val stroke = wet.finish() ?: break
                                val layout = state.layout ?: break
                                DocumentStrokes.onPage(layout, stroke.toFreehandStroke())?.let { (index, onPage) -> currentOnStroke(index, onPage) }
                                break
                            }
                            if (change.positionChanged()) {
                                wet.add(change.samples(pointerToStroke))
                                change.consume()
                                revision.intValue++
                            }
                        }
                    } finally {
                        // A gesture cut short (the layer leaving, a cancelled pointer) draws nothing.
                        if (wet.isActive) wet.cancel()
                        revision.intValue++
                    }
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        revision.intValue
        val stroke = wet.current ?: return@Canvas
        val mapper = state.mapper ?: return@Canvas
        if (clipPage !in mapper.layout.pageRects.indices) return@Canvas
        val page = mapper.pageBoundsOnScreen(clipPage)
        clipRect(page.left, page.top, page.right, page.bottom) {
            drawIntoCanvas { canvas ->
                // The renderer expects the transform on the canvas already; it uses its own copy for detail.
                canvas.nativeCanvas.withMatrix(strokeToScreen) { renderer.draw(this, stroke, strokeToScreen) }
            }
        }
    }
}

private fun PointerInputChange.sample(position: Offset, timeMillis: Long, pointerToStroke: Matrix): InkSample {
    val p = pointerToStroke.map(position)
    val tool = when (type) {
        PointerType.Stylus, PointerType.Eraser -> InputToolType.STYLUS
        PointerType.Mouse -> InputToolType.MOUSE
        else -> InputToolType.TOUCH
    }
    // Pressure only from a stylus, as androidx.ink does: a finger's is noise.
    val pressure = if (tool == InputToolType.STYLUS) this.pressure.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE
    return InkSample(p.x, p.y, timeMillis, tool, pressure)
}

/** The batched history of this change, then the change itself (the history has no pressure of its own). */
private fun PointerInputChange.samples(pointerToStroke: Matrix): List<InkSample> =
    historical.map { sample(it.position, it.uptimeMillis, pointerToStroke) } + sample(position, uptimeMillis, pointerToStroke)

/** Sets this matrix to the 2D [affine] transform, in place (the ink layer reads it at each stroke start). */
internal fun Matrix.setAffine(affine: Affine) {
    reset()
    values[Matrix.ScaleX] = affine.a
    values[Matrix.SkewY] = affine.b
    values[Matrix.SkewX] = affine.c
    values[Matrix.ScaleY] = affine.d
    values[Matrix.TranslateX] = affine.e
    values[Matrix.TranslateY] = affine.f
}
