package com.marcogn.pdftoolkit.ui.fill

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkShape
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.pdf.render.Affine
import kotlin.math.min

/**
 * Draws overlays on screen the way the PDF writer puts them in the page: same font file, same
 * [TextBlock] layout, same [MarkShape] strokes. Kerning and ligatures are off, because the PDF
 * writer places the font's plain advance widths.
 */
internal class OverlayPainter(typeface: Typeface) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        this.typeface = typeface
        color = android.graphics.Color.BLACK
        fontFeatureSettings = NO_SHAPING
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.BLACK
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val values = FloatArray(MATRIX_VALUES)

    /** Width of one line in points at [fontSize]. */
    fun lineWidth(line: String, fontSize: Float): Float {
        textPaint.textSize = MEASURE_SIZE
        return textPaint.measureText(line) * fontSize / MEASURE_SIZE
    }

    /** Size in points of the box of [text] at [fontSize] (see [TextBlock.boxSize]). */
    fun textBoxSize(text: String, fontSize: Float): Pair<Float, Float> =
        TextBlock.boxSize(TextBlock.lines(text).map { lineWidth(it, fontSize) }, fontSize)

    /**
     * Draws [overlay]. [toScreen] maps its local space to screen pixels and scales by
     * [pxPerPoint]; [image] is the picture of an image overlay, if loaded.
     */
    fun draw(canvas: Canvas, overlay: Overlay, toScreen: Affine, pxPerPoint: Float, image: Bitmap?, selectionColor: Int?) {
        canvas.save()
        canvas.concat(toScreen.toMatrix())
        // From here on, units are screen pixels along the overlay's own axes.
        canvas.scale(1f / pxPerPoint, 1f / pxPerPoint)
        val width = overlay.box.width * pxPerPoint
        val height = overlay.box.height * pxPerPoint
        when (overlay) {
            is TextOverlay -> {
                textPaint.textSize = overlay.fontSize * pxPerPoint
                TextBlock.lines(overlay.text).forEachIndexed { index, line ->
                    val (x, baseline) = TextBlock.lineOrigin(index, overlay.fontSize)
                    canvas.drawText(line, x * pxPerPoint, baseline * pxPerPoint, textPaint)
                }
            }
            is MarkOverlay -> {
                strokePaint.strokeWidth = MarkShape.STROKE * min(width, height)
                val path = Path()
                MarkShape.strokes(overlay.kind).forEach { points ->
                    points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * width, y * height) else path.lineTo(x * width, y * height) }
                }
                canvas.drawPath(path, strokePaint)
            }
            is ImageOverlay -> image?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width, height), bitmapPaint) }
        }
        if (selectionColor != null) {
            selectionPaint.color = selectionColor
            selectionPaint.strokeWidth = SELECTION_STROKE_PX
            selectionPaint.pathEffect = DashPathEffect(floatArrayOf(DASH_PX, DASH_PX), 0f)
            canvas.drawRect(-SELECTION_INSET_PX, -SELECTION_INSET_PX, width + SELECTION_INSET_PX, height + SELECTION_INSET_PX, selectionPaint)
        }
        canvas.restore()
    }

    /** Draws [bitmap] with [toScreen] mapping its pixels to the screen. */
    fun drawBitmap(canvas: Canvas, bitmap: Bitmap, toScreen: Affine) {
        canvas.drawBitmap(bitmap, toScreen.toMatrix(), bitmapPaint)
    }

    private fun Affine.toMatrix(): Matrix {
        values[Matrix.MSCALE_X] = a
        values[Matrix.MSKEW_X] = c
        values[Matrix.MTRANS_X] = e
        values[Matrix.MSKEW_Y] = b
        values[Matrix.MSCALE_Y] = d
        values[Matrix.MTRANS_Y] = f
        values[Matrix.MPERSP_0] = 0f
        values[Matrix.MPERSP_1] = 0f
        values[Matrix.MPERSP_2] = 1f
        matrix.setValues(values)
        return matrix
    }

    private companion object {
        const val NO_SHAPING = "'kern' 0, 'liga' 0, 'clig' 0"
        /** Measuring at a large size, then scaling, avoids rounding at tiny sizes. */
        const val MEASURE_SIZE = 100f
        const val MATRIX_VALUES = 9
        const val SELECTION_STROKE_PX = 3f
        const val SELECTION_INSET_PX = 6f
        const val DASH_PX = 10f
    }
}
