package com.marcogn.pdftoolkit.pdf.annotations

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.CompactPolylineSerializer
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import kotlin.math.hypot
import kotlin.math.max

/**
 * A finished freehand stroke as the ink library made it (spec §7.4), in **display points** of the
 * page as the pane shows it (origin top-left, y down, every rotation applied): the [centre] line
 * the finger or stylus followed, the brush [outlines] (closed polygons, nonzero winding), the
 * brush [width] and its [epsilon] (the smallest distance it tells apart), both in points.
 */
data class FreehandStroke(
    val centre: List<Offset>,
    val outlines: List<List<Offset>>,
    val width: Float,
    val epsilon: Float,
)

/**
 * From a drawn stroke to an Ink annotation shape in user space, the space the session and the
 * writer use. Display points and user space have the same scale (a page's rotation and flip
 * only), so widths carry over as they are.
 */
object FreehandGeometry {

    /**
     * [stroke] in user space through [displayToUser] (`PdfPageSpace.displayToUser` of the page as
     * shown, so a page turned by the user is handled too). Points closer to a straight line than
     * the brush epsilon are dropped and coordinates are rounded as the session stores them
     * ([CompactPolylineSerializer.STEP]). Outlines left with fewer than three points are dropped;
     * the stroke is then drawn from its centre line. Null without any point.
     */
    fun toInk(stroke: FreehandStroke, displayToUser: Affine, highlighter: Boolean): AnnotationShape.Ink? {
        val tolerance = max(stroke.epsilon, CompactPolylineSerializer.STEP)
        val centre = tidy(simplify(stroke.centre.map(displayToUser::map), tolerance, closed = false))
        if (centre.isEmpty()) return null
        val outlines = stroke.outlines
            .map { outline -> tidy(simplify(outline.map(displayToUser::map), tolerance, closed = true), closed = true) }
            .filter { it.size >= AnnotationShape.Ink.MIN_OUTLINE_POINTS }
        return AnnotationShape.Ink(listOf(centre), stroke.width, outlines, highlighter)
    }

    /**
     * Whether [shape] shows on the page at all: its drawing touches the visible box of [space]. A
     * stroke drawn entirely on the background around the page is not kept.
     */
    fun touchesPage(shape: AnnotationShape.Ink, space: PdfPageSpace): Boolean {
        val bounds = AnnotationGeometry.bounds(shape)
        return bounds.right >= space.left && bounds.left <= space.left + space.width &&
            bounds.top >= space.bottom && bounds.bottom <= space.bottom + space.height
    }

    /**
     * Ramer–Douglas–Peucker: the points of [points] that keep the polyline within [tolerance] of
     * the original. A [closed] polygon is simplified as the open path that returns to its start.
     */
    internal fun simplify(points: List<Offset>, tolerance: Float, closed: Boolean): List<Offset> {
        if (points.size < 3) return points
        val path = if (closed && points.first() != points.last()) points + points.first() else points
        val keep = BooleanArray(path.size)
        keep[0] = true
        keep[path.size - 1] = true
        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.addLast(0 to path.size - 1)
        while (ranges.isNotEmpty()) {
            val (from, to) = ranges.removeLast()
            var farthest = -1
            var distance = tolerance
            for (i in from + 1 until to) {
                val d = distanceToSegment(path[i], path[from], path[to])
                if (d > distance) {
                    distance = d
                    farthest = i
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                ranges.addLast(from to farthest)
                ranges.addLast(farthest to to)
            }
        }
        val kept = path.filterIndexed { i, _ -> keep[i] }
        return if (closed && path !== points) kept.dropLast(1) else kept
    }

    /** Rounded to the stored resolution, without points repeated after rounding. */
    private fun tidy(points: List<Offset>, closed: Boolean = false): List<UserPoint> {
        val rounded = ArrayList<UserPoint>(points.size)
        for (p in points) {
            val point = UserPoint(CompactPolylineSerializer.round(p.x), CompactPolylineSerializer.round(p.y))
            if (rounded.lastOrNull() != point) rounded += point
        }
        if (closed && rounded.size > 1 && rounded.first() == rounded.last()) rounded.removeAt(rounded.size - 1)
        return rounded
    }

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
