package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.UserRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What an annotation is drawn with, in user space: filled polygons and stroked polylines. The
 * screen (`ui/annotate/AnnotationPainter`) and the PDF appearance stream (`pdf/edit/AnnotationWriter`)
 * both draw exactly this, so an annotation looks the same in the app and in other readers.
 */
data class AnnotationPaths(
    /**
     * Closed polygons, filled together as one path with the nonzero winding rule: where they
     * overlap the paint goes on once (the outlines of one freehand stroke overlap).
     */
    val fills: List<List<UserPoint>> = emptyList(),
    /** Open polylines, stroked [strokeWidth] wide with round caps and joins. */
    val strokes: List<List<UserPoint>> = emptyList(),
    val strokeWidth: Float = 0f,
)

/**
 * Geometry of annotations in user space. A quad is handled through its own axes (along the text and
 * towards the top of the letters), so the same code works for text at any angle and on pages
 * turned any way.
 */
object AnnotationGeometry {

    /**
     * The paths of [shape]. Text markup follows the text's axes: a highlight fills the quad; an
     * underline sits on the bottom edge, a strikeout across the middle (pdfium's choice), a squiggly
     * is a wave along the bottom, each as thick as [LINE_FRACTION] of the line height.
     */
    fun paths(shape: AnnotationShape): List<AnnotationPaths> = when (shape) {
        is AnnotationShape.Ink ->
            if (shape.outlines.isNotEmpty()) {
                listOf(AnnotationPaths(fills = shape.outlines))
            } else {
                listOf(AnnotationPaths(strokes = shape.strokes, strokeWidth = shape.width))
            }
        is AnnotationShape.TextMarkup -> shape.quads.map { quad ->
            when (shape.kind) {
                MarkupKind.HIGHLIGHT -> AnnotationPaths(fills = listOf(listOf(quad.upperLeft, quad.upperRight, quad.lowerRight, quad.lowerLeft)))
                MarkupKind.UNDERLINE -> lineAt(quad, thickness(quad) / 2 / max(height(quad), EPSILON))
                MarkupKind.STRIKEOUT -> lineAt(quad, STRIKE_POSITION)
                MarkupKind.SQUIGGLY -> squiggly(quad)
            }
        }
    }

    /**
     * Whether [shape] multiplies with the page instead of covering it: a text highlight and a
     * freehand highlighter, so the text under them stays readable (`/BM /Multiply`, as Acrobat).
     */
    fun multiplies(shape: AnnotationShape): Boolean = when (shape) {
        is AnnotationShape.TextMarkup -> shape.kind == MarkupKind.HIGHLIGHT
        is AnnotationShape.Ink -> shape.highlighter
    }

    /** Line height of [quad]: the distance between its lower and upper edge. */
    fun height(quad: Quad): Float = distance(quad.lowerLeft, quad.upperLeft)

    private fun thickness(quad: Quad): Float = max(height(quad) * LINE_FRACTION, MIN_LINE)

    /** A line across [quad] parallel to the text, [fraction] of the way from the bottom to the top. */
    private fun lineAt(quad: Quad, fraction: Float): AnnotationPaths = AnnotationPaths(
        strokes = listOf(listOf(at(quad, 0f, fraction), at(quad, 1f, fraction))),
        strokeWidth = thickness(quad),
    )

    /** Zig-zag along the bottom of [quad], one full wave per [WAVE_LENGTH] line heights. */
    private fun squiggly(quad: Quad): AnnotationPaths {
        val height = max(height(quad), EPSILON)
        val length = distance(quad.lowerLeft, quad.lowerRight)
        val halfWaves = max(1, (length / (height * WAVE_LENGTH / 2)).roundToInt())
        // Fractions of the line height, on the quad's own axes.
        val bottom = thickness(quad) / 2 / height
        val points = (0..halfWaves).map { i ->
            at(quad, i.toFloat() / halfWaves, bottom + if (i % 2 == 0) 0f else WAVE_AMPLITUDE)
        }
        return AnnotationPaths(strokes = listOf(points), strokeWidth = thickness(quad))
    }

    /** The point of [quad] at [along] (0 left .. 1 right) and [up] (0 bottom .. 1 top), on the quad's own axes. */
    fun at(quad: Quad, along: Float, up: Float): UserPoint {
        val bottom = lerp(quad.lowerLeft, quad.lowerRight, along)
        val top = lerp(quad.upperLeft, quad.upperRight, along)
        return lerp(bottom, top, up)
    }

    /**
     * The `/Rect` for [shape]: the box around everything it draws, plus half the stroke width and
     * [PADDING], so no reader clips the edges.
     */
    fun bounds(shape: AnnotationShape): UserRect {
        val all = paths(shape)
        val points = all.flatMap { it.fills.flatten() + it.strokes.flatten() }
        val pad = (all.maxOfOrNull { it.strokeWidth } ?: 0f) / 2 + PADDING
        return UserRect(
            left = points.minOf { it.x } - pad,
            bottom = points.minOf { it.y } - pad,
            right = points.maxOf { it.x } + pad,
            top = points.maxOf { it.y } + pad,
        )
    }

    /**
     * Whether [point] touches [shape], within [tolerance] points: inside a quad of a text markup,
     * or near a stroke of an ink annotation. For the eraser.
     */
    fun hits(shape: AnnotationShape, point: UserPoint, tolerance: Float): Boolean = when (shape) {
        is AnnotationShape.TextMarkup -> shape.quads.any { quad ->
            val polygon = listOf(quad.lowerLeft, quad.lowerRight, quad.upperRight, quad.upperLeft)
            insideConvex(polygon, point) || distanceToPolyline(polygon + polygon.first(), point) <= tolerance
        }
        is AnnotationShape.Ink ->
            if (shape.outlines.isNotEmpty()) {
                windingNumber(shape.outlines, point) != 0 ||
                    shape.outlines.any { distanceToPolyline(it + it.first(), point) <= tolerance }
            } else {
                shape.strokes.any { distanceToPolyline(it, point) <= shape.width / 2 + tolerance }
            }
    }

    /** Sum of the winding numbers of the closed [polygons] around [point]: inside for the nonzero rule when not 0. */
    private fun windingNumber(polygons: List<List<UserPoint>>, point: UserPoint): Int {
        var winding = 0
        for (polygon in polygons) {
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
                if (a.y <= point.y) {
                    if (b.y > point.y && cross > 0) winding++
                } else if (b.y <= point.y && cross < 0) {
                    winding--
                }
            }
        }
        return winding
    }

    /** Whether [point] is inside the convex polygon [polygon] (either winding). */
    private fun insideConvex(polygon: List<UserPoint>, point: UserPoint): Boolean {
        var sign = 0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            if (abs(cross) < EPSILON) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return sign != 0
    }

    private fun distanceToPolyline(points: List<UserPoint>, point: UserPoint): Float {
        if (points.size == 1) return distance(points[0], point)
        var best = Float.MAX_VALUE
        for (i in 0 until points.size - 1) best = min(best, distanceToSegment(points[i], points[i + 1], point))
        return best
    }

    private fun distanceToSegment(a: UserPoint, b: UserPoint, p: UserPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < EPSILON) return distance(a, p)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distance(UserPoint(a.x + t * dx, a.y + t * dy), p)
    }

    private fun lerp(a: UserPoint, b: UserPoint, t: Float) = UserPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun distance(a: UserPoint, b: UserPoint): Float = hypot(b.x - a.x, b.y - a.y)

    /** Underline, strikeout and squiggly thickness, as a fraction of the line height. */
    const val LINE_FRACTION = 1f / 14f

    /** Thinnest line drawn, in points. */
    private const val MIN_LINE = 0.5f

    /** Strikeout height, as a fraction of the line height from the bottom: pdfium strikes through the middle. */
    private const val STRIKE_POSITION = 0.5f

    /** Squiggly: wave length and amplitude, as fractions of the line height. */
    private const val WAVE_LENGTH = 0.5f
    private const val WAVE_AMPLITUDE = 0.08f

    /** Extra room around the drawing in `/Rect`, in points. */
    const val PADDING = 1f

    private const val EPSILON = 1e-4f
}
