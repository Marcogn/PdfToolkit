package com.marcogn.pdftoolkit.data.signatures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.marcogn.pdftoolkit.domain.signature.BackgroundRemoval
import com.marcogn.pdftoolkit.domain.signature.InkStroke
import com.marcogn.pdftoolkit.domain.signature.InkWidth
import com.marcogn.pdftoolkit.domain.signature.PixelRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Turns what the user drew or picked into the transparent PNG that goes into the archive. */
object SignatureRendering {

    /** Longest side of a saved signature in pixels: more would only cost memory when it is placed. */
    const val MAX_SIDE_PX = 1600

    /** Margin kept around a signature trimmed to its content, in pixels. */
    private const val TRIM_MARGIN_PX = 4

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Draws [strokes] on [canvas] as they are on the drawing surface, with a [basePx] line width. */
    fun draw(canvas: Canvas, strokes: List<InkStroke>, basePx: Float) {
        for (stroke in strokes) {
            paint.color = stroke.color.argb
            val points = stroke.points
            val widths = InkWidth.widths(points, basePx)
            if (points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(points[0].x, points[0].y, widths[0] / 2f, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            for (i in 1 until points.size) {
                paint.strokeWidth = (widths[i - 1] + widths[i]) / 2f
                canvas.drawLine(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, paint)
            }
        }
    }

    /** The strokes on a transparent bitmap cropped to them; null if there is nothing drawn. */
    fun render(strokes: List<InkStroke>, basePx: Float): Bitmap? {
        val points = strokes.flatMap { it.points }
        if (points.isEmpty()) return null
        val pad = basePx * InkWidth.MAX_FACTOR
        val minX = points.minOf { it.x } - pad
        val minY = points.minOf { it.y } - pad
        val width = points.maxOf { it.x } + pad - minX
        val height = points.maxOf { it.y } + pad - minY
        val scale = min(1f, MAX_SIDE_PX / max(width, height))
        val bitmap = createBitmap(max(1, ceil(width * scale).toInt()), max(1, ceil(height * scale).toInt()))
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-minX, -minY)
        draw(canvas, strokes, basePx)
        return bitmap
    }

    /**
     * The part of [source] inside [crop] (fractions of its size), with the background made transparent
     * when [removeBackground] and then trimmed to what is left. Null if the crop is empty or nothing
     * but background is in it.
     */
    fun processImport(source: Bitmap, crop: CropFractions, removeBackground: Boolean, threshold: Float): Bitmap? {
        val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (crop.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (crop.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        var width = right - left
        var height = bottom - top
        var pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, left, top, width, height)
        if (removeBackground) {
            pixels = BackgroundRemoval.removeBackground(pixels, threshold)
            val bounds = BackgroundRemoval.contentBounds(pixels, width, height, TRIM_MARGIN_PX) ?: return null
            pixels = trimmed(pixels, width, bounds)
            width = bounds.width
            height = bounds.height
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun trimmed(pixels: IntArray, width: Int, bounds: PixelRect): IntArray {
        if (bounds.width == width && bounds.left == 0 && bounds.top == 0) return pixels.copyOf(bounds.width * bounds.height)
        val out = IntArray(bounds.width * bounds.height)
        for (y in 0 until bounds.height) {
            System.arraycopy(pixels, (bounds.top + y) * width + bounds.left, out, y * bounds.width, bounds.width)
        }
        return out
    }
}

/** A crop rectangle as fractions (0..1) of the picture's width and height. */
data class CropFractions(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val Full = CropFractions(0f, 0f, 1f, 1f)
    }
}
