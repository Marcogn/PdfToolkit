package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.text.LineRun

/** A handle the finger is on: which one, and from the touch to the handle's anchor on the text, in screen pixels. */
internal data class HandleGrab(val handle: SelectionHandle, val toAnchor: Offset)

/** Sizes of the selection handles, in screen pixels. */
internal data class HandleMetrics(val radius: Float, val slop: Float)

/**
 * Where the handles of [runs] sit and how to pick them: the start handle hangs from the lower-left
 * corner of the first line, the end handle from the lower-right corner of the last one. [pageToScreen]
 * maps the page points the runs are in to screen pixels.
 */
internal object SelectionHandles {

    /** The corner a handle hangs from, drawn below it. */
    fun hangPoint(runs: List<LineRun>, handle: SelectionHandle): Offset? = when (handle) {
        SelectionHandle.START -> runs.firstOrNull()?.lowerLeft
        SelectionHandle.END -> runs.lastOrNull()?.lowerRight
    }

    /** The middle of the line at the handle's end: the point that is moved to a glyph boundary while dragging. */
    fun anchorPoint(runs: List<LineRun>, handle: SelectionHandle): Offset? {
        val run = when (handle) {
            SelectionHandle.START -> runs.firstOrNull()
            SelectionHandle.END -> runs.lastOrNull()
        } ?: return null
        val middle = (run.ascent + run.descent) / 2f
        return (if (handle == SelectionHandle.START) run.origin else run.end) + middle
    }

    /**
     * The handle under [screen], if any: within the handle's disc (hanging below its corner, in
     * screen space) plus [HandleMetrics.slop], or just around the corner. The nearer one wins when
     * the two overlap (a one-letter selection).
     */
    fun grabAt(runs: List<LineRun>, pageToScreen: Affine, screen: Offset, metrics: HandleMetrics): HandleGrab? {
        var best: HandleGrab? = null
        var bestDistance = Float.MAX_VALUE
        for (handle in SelectionHandle.entries) {
            val hang = hangPoint(runs, handle)?.let(pageToScreen::map) ?: continue
            val anchor = anchorPoint(runs, handle)?.let(pageToScreen::map) ?: continue
            val disc = hang + Offset(0f, metrics.radius)
            val distance = minOf((screen - disc).getDistance(), (screen - hang).getDistance())
            if (distance <= metrics.radius + metrics.slop && distance < bestDistance) {
                bestDistance = distance
                best = HandleGrab(handle, anchor - screen)
            }
        }
        return best
    }
}

/** Draws the selection: a translucent parallelogram per line and the two handles. */
internal fun DrawScope.drawTextSelection(runs: List<LineRun>, pageToScreen: Affine, fill: Color, handleColor: Color, metrics: HandleMetrics) {
    for (run in runs) {
        val path = Path().apply {
            val corners = listOf(run.upperLeft, run.upperRight, run.lowerRight, run.lowerLeft).map(pageToScreen::map)
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
            close()
        }
        drawPath(path, fill)
    }
    for (handle in SelectionHandle.entries) {
        val hang = SelectionHandles.hangPoint(runs, handle)?.let(pageToScreen::map) ?: continue
        val disc = hang + Offset(0f, metrics.radius)
        drawLine(handleColor, hang, disc, strokeWidth = metrics.radius / HANDLE_STEM_DIVISOR)
        drawCircle(handleColor, metrics.radius, disc)
        drawCircle(Color.White, metrics.radius, disc, style = Stroke(width = metrics.radius / HANDLE_RIM_DIVISOR))
    }
}

private const val HANDLE_STEM_DIVISOR = 4f
private const val HANDLE_RIM_DIVISOR = 8f
