package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import kotlin.math.roundToInt

/**
 * The `androidx.ink` side of freehand drawing (spec §7.4): which brush each tool uses and how a
 * finished [Stroke] becomes a [FreehandStroke]. Strokes are made in **display points** of the page
 * as the pane shows it, so brush sizes are points of the page and the brush is oriented as the
 * page is on screen.
 *
 * Brush versions are pinned (`V1`): the outline written into the PDF must not change shape when
 * the library is upgraded.
 */
internal object FreehandInk {

    /** A tenth of a screen pixel, the fidelity the library suggests, at the current zoom. */
    private const val EPSILON_PX = 0.1f

    /** Alpha of the highlighter while drawing: the finished stroke multiplies instead (API 29+). */
    private const val HIGHLIGHTER_WET_ALPHA = 0.45f

    private fun family(kind: FreehandKind): BrushFamily = when (kind) {
        FreehandKind.PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        // DISCARD: the overlap of a stroke with itself paints once, as the PDF fill does.
        FreehandKind.HIGHLIGHTER -> StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1)
    }

    /** The brush for a new stroke of [kind] in [color], [width] points wide, when one point of the page is [pxPerPoint] screen pixels. */
    fun brush(kind: FreehandKind, color: AnnotationColor, width: Float, pxPerPoint: Float): Brush {
        val alpha = if (kind == FreehandKind.HIGHLIGHTER) HIGHLIGHTER_WET_ALPHA else 1f
        return Brush.createWithColorIntArgb(
            family = family(kind),
            colorIntArgb = argb(color, alpha),
            size = width,
            epsilon = EPSILON_PX / pxPerPoint,
        )
    }

    /** The centre line (the inputs) and the outlines of every render group of [this], in stroke space. */
    fun Stroke.toFreehandStroke(): FreehandStroke {
        val input = StrokeInput()
        val centre = (0 until inputs.size).map { i ->
            inputs.populate(i, input)
            Offset(input.x, input.y)
        }
        val vertex = MutableVec()
        val outlines = (0 until shape.getRenderGroupCount()).flatMap { group ->
            (0 until shape.getOutlineCount(group)).map { outline ->
                (0 until shape.getOutlineVertexCount(group, outline)).map { v ->
                    shape.populateOutlinePosition(group, outline, v, vertex)
                    Offset(vertex.x, vertex.y)
                }
            }
        }
        return FreehandStroke(centre, outlines, brush.size, brush.epsilon)
    }

    private fun argb(color: AnnotationColor, alpha: Float): Int =
        (channel(alpha) shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)

    private fun channel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
}
