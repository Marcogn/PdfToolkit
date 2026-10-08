package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.text.LineRun

/**
 * Where the floating bar of a text selection (Copy, Highlight) goes, like the one Android's own text
 * selection shows (plan U21): centred over the selection, above its first line, or below its last one
 * when there is no room above; always inside the viewport. All values in screen pixels.
 */
object SelectionBarPlacement {

    /** The screen rectangle that contains all of [runs], drawn with [pageToScreen]; null for an empty selection. */
    fun screenBounds(runs: List<LineRun>, pageToScreen: Affine): Rect? {
        val corners = runs.flatMap { listOf(it.upperLeft, it.upperRight, it.lowerRight, it.lowerLeft) }.map(pageToScreen::map)
        if (corners.isEmpty()) return null
        return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    /**
     * The top-left corner for a bar of [bar] size, [gap] pixels from the selection, [margin] pixels
     * from the edges of [viewport]; null when the selection is not on screen at all (the bar would
     * float over unrelated text).
     */
    fun place(selection: Rect, bar: Size, viewport: Size, gap: Float, margin: Float): Offset? {
        if (selection.right < 0f || selection.left > viewport.width || selection.bottom < 0f || selection.top > viewport.height) return null
        val above = selection.top - gap - bar.height
        val y = if (above >= margin) above else selection.bottom + gap
        val x = selection.center.x - bar.width / 2f
        val maxX = (viewport.width - bar.width - margin).coerceAtLeast(margin)
        val maxY = (viewport.height - bar.height - margin).coerceAtLeast(margin)
        return Offset(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
    }
}
