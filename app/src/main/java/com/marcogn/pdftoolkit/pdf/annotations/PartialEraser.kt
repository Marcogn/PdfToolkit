package com.marcogn.pdftoolkit.pdf.annotations

import com.github.micycle1.clipper2.Clipper
import com.github.micycle1.clipper2.core.ClipType
import com.github.micycle1.clipper2.core.FillRule
import com.github.micycle1.clipper2.core.Path64
import com.github.micycle1.clipper2.core.Paths64
import com.github.micycle1.clipper2.core.Point64
import com.github.micycle1.clipper2.engine.Clipper64
import com.github.micycle1.clipper2.engine.PolyPath64
import com.github.micycle1.clipper2.engine.PolyTree64
import com.github.micycle1.clipper2.offset.EndType
import com.github.micycle1.clipper2.offset.JoinType
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.CompactPolylineSerializer
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.UserRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The area one move of the partial eraser swept over a page (plan T-a), in user space: the capsules of
 * [radius] points around each segment of [path] (a circle for a single point). Polygons are Clipper2's
 * integer paths in [PartialEraser.SCALE] units, the resolution the session stores
 * ([CompactPolylineSerializer.STEP]), so what the eraser leaves is stored exactly as computed.
 */
class EraserSweep private constructor(private val path: Path64, val radius: Float) {

    /** The box around the swept area. */
    val bounds: UserRect = path.fold(null as UserRect?) { box, p ->
        val x = (p.x / PartialEraser.SCALE).toFloat()
        val y = (p.y / PartialEraser.SCALE).toFloat()
        if (box == null) UserRect(x, y, x, y) else UserRect(min(box.left, x), min(box.bottom, y), max(box.right, x), max(box.top, y))
    }!!.let { UserRect(it.left - radius, it.bottom - radius, it.right + radius, it.top + radius) }

    private val grown = HashMap<Float, Paths64>()

    /**
     * The swept area grown by [extra] points: what a line stroked `2 * extra` wide loses when its centre line
     * is cut by it, so that its round caps end where the eraser passed.
     */
    internal fun area(extra: Float = 0f): Paths64 = grown.getOrPut(extra) {
        Clipper.inflatePaths(
            Paths64(path),
            (radius + extra) * PartialEraser.SCALE,
            JoinType.Round,
            EndType.Round,
            MITER_LIMIT,
            PartialEraser.ARC_TOLERANCE,
        )
    }

    companion object {
        private const val MITER_LIMIT = 2.0

        /** The area [radius] points around [path] (user space of one page); null without points or radius. */
        fun along(path: List<UserPoint>, radius: Float): EraserSweep? {
            if (path.isEmpty() || radius <= 0f) return null
            val points = Path64(path.size)
            for (p in path) {
                val point = PartialEraser.toClipper(p)
                if (points.isEmpty() || points.last() != point) points.add(point)
            }
            return EraserSweep(points, radius)
        }
    }
}

/**
 * What the partial eraser leaves of an annotation (plan T-a): only what the swept area covers goes, the rest
 * stays as annotations of the same kind, colour and width. Pure geometry in user space; the polygon
 * operations are Clipper2's (ADR 0004, "Partial eraser").
 *
 * - **Ink with outlines** (strokes drawn in the app): the swept area is cut out of the outlines (nonzero, as
 *   they are filled). Each connected part left is one piece, with the parts of the centre line that lie in
 *   it. The centre line is cut where the eraser crosses it and shortened by half the width at each cut, so a
 *   reader that draws `/InkList` (and this app, for ink read back from a file) ends the line where the eraser
 *   passed, while a nick in the edge leaves it whole.
 * - **Ink without outlines** (read from a file, drawn as its centre line stroked): the centre line is cut by the
 *   swept area grown by half the width, so wherever the eraser touches the line as drawn, it is cut.
 * - **Text markup**: each quad loses, along its text, the stretch the swept area crosses, at full line height:
 *   a quad is a line of text, so the rest of the line keeps its markup.
 *
 * An annotation of one stroke (the app's own) becomes one annotation per piece, so the stroke eraser still
 * works piece by piece; one of several strokes (other apps' handwriting) and text markup stay one annotation.
 * Leftovers smaller than [LEFTOVER] or the eraser's radius are dropped.
 */
object PartialEraser {

    /** Clipper2 units per point: hundredths, the resolution the session stores. */
    const val SCALE = 1.0 / CompactPolylineSerializer.STEP

    /** The largest distance, in Clipper units, between the eraser's round ends and the polygon drawn for them. */
    internal const val ARC_TOLERANCE = 2.0

    /** Pieces smaller than this, in points, are dropped whatever the eraser's size. */
    const val LEFTOVER = 0.3f

    /**
     * What is left of [shape] after [sweep]: null if the sweep doesn't touch it (nothing changes), an empty
     * list if nothing is left, else the pieces.
     */
    fun cut(shape: AnnotationShape, sweep: EraserSweep): List<AnnotationShape>? {
        if (!AnnotationGeometry.bounds(shape).overlaps(sweep.bounds)) return null
        return when (shape) {
            is AnnotationShape.Ink -> if (shape.outlines.isEmpty()) cutCentreLines(shape, sweep) else cutOutlined(shape, sweep)
            is AnnotationShape.TextMarkup -> cutMarkup(shape, sweep)
        }
    }

    /**
     * Whether the pieces of [ink] become annotations of their own: ink with outlines (only the app draws them,
     * one stroke each) and ink of one stroke. The answer is the same for the pieces as for the original, so a
     * later drag splits them the same way, even a piece whose two stretches of centre line a sliver still joins.
     */
    private fun separates(ink: AnnotationShape.Ink): Boolean = ink.outlines.isNotEmpty() || ink.strokes.size == 1

    // --- Ink ---

    private fun cutCentreLines(ink: AnnotationShape.Ink, sweep: EraserSweep): List<AnnotationShape>? {
        val area = sweep.area(ink.width / 2)
        var changed = false
        val pieces = ink.strokes.flatMap { stroke ->
            val left = clipLine(stroke, area, keepInside = false)
            if (left == null) listOf(stroke) else left.also { changed = true }
        }
        if (!changed) return null
        // A stroked piece shows its round caps too, so only a stub of nothing is dropped.
        val kept = pieces.filter { length(it) >= LEFTOVER }
        if (kept.isEmpty()) return emptyList()
        return if (separates(ink)) kept.map { ink.copy(strokes = listOf(it)) } else listOf(ink.copy(strokes = kept))
    }

    private fun cutOutlined(ink: AnnotationShape.Ink, sweep: EraserSweep): List<AnnotationShape>? {
        val outlines = Paths64(ink.outlines.map(::toPath))
        val erased = sweep.area()
        if (abs(Clipper.area(Clipper.intersect(outlines, erased, FillRule.NonZero))) < MIN_AREA) return null
        val tree = PolyTree64()
        Clipper64().apply {
            addSubject(outlines)
            addClip(erased)
            execute(ClipType.Difference, FillRule.NonZero, tree)
        }
        // A sliver beside the eraser (what is left past a stroke's end) is thinner than the line or the eraser.
        val minThickness = max(LEFTOVER, min(sweep.radius, ink.width)) / 2
        val parts = components(tree).filter { part ->
            abs(Clipper.area(part)) >= MIN_AREA && extent(part.flatten()) >= LEFTOVER && thickness(part) >= minThickness
        }
        if (parts.isEmpty()) return emptyList()

        // Cut where the eraser crosses the centre line, then shortened by half the width at each new end: a nick
        // in the edge of a wide stroke leaves the centre line whole, as it leaves the outline in one part.
        val centres = ink.strokes.flatMap { stroke ->
            clipLine(stroke, erased, keepInside = false)?.mapNotNull { trimNewEnds(it, stroke, ink.width / 2) } ?: listOf(stroke)
        }
        val linesOf = parts.map { mutableListOf<List<UserPoint>>() }
        for (centre in centres) {
            val owner = parts.indexOfFirst { part -> centre.any { contains(part, it) } }
            if (owner >= 0) linesOf[owner] += centre
        }
        val pieces = parts.mapIndexedNotNull { i, part ->
            // A part with none of the centre line in it (the eraser went along the middle of a wide marker, or
            // took a short stroke's centre): the stretch of the original centre line that drew it.
            val lines = linesOf[i].ifEmpty {
                ink.strokes.flatMap { clipLine(it, grow(part, ink.width / 2), keepInside = true) ?: listOf(it) }
            }
            if (lines.isEmpty()) null else ink.copy(strokes = lines, outlines = part.map(::fromPath))
        }
        if (pieces.isEmpty()) return emptyList()
        if (separates(ink)) return pieces
        return listOf(ink.copy(strokes = pieces.flatMap { it.strokes }, outlines = pieces.flatMap { it.outlines }))
    }

    /** The outer polygons of [tree], each with its holes; islands inside a hole are parts of their own. */
    private fun components(tree: PolyPath64): List<Paths64> {
        val parts = ArrayList<Paths64>()
        fun visit(node: PolyPath64) {
            for (i in 0 until node.count) {
                val outer = node.get(i)
                val part = Paths64(outer.polygon)
                for (j in 0 until outer.count) {
                    val hole = outer.get(j)
                    part.add(hole.polygon)
                    visit(hole)
                }
                parts += part
            }
        }
        visit(tree)
        return parts
    }

    /**
     * [line] clipped by [area]: the parts outside it (or inside, with [keepInside]). Null when the line doesn't
     * cross the area's edge and is wholly kept, so an untouched stroke stays exactly as it was; empty when none
     * of it is kept.
     */
    private fun clipLine(line: List<UserPoint>, area: Paths64, keepInside: Boolean): List<List<UserPoint>>? {
        if (line.size == 1) {
            val inside = contains(area, line[0])
            return when {
                inside == keepInside -> null
                else -> emptyList()
            }
        }
        val open = Paths64()
        Clipper64().apply {
            addOpenSubject(toPath(line))
            addClip(area)
            execute(if (keepInside) ClipType.Intersection else ClipType.Difference, FillRule.NonZero, Paths64(), open)
        }
        val pieces = open.map(::fromPath).filter { it.size >= 2 }
        if (pieces.size == 1 && abs(length(pieces[0]) - length(line)) < CompactPolylineSerializer.STEP) return null
        return pieces
    }

    /**
     * [piece] of [original] shortened by [by] points at each end the cut made (an end that isn't one of the
     * original's), so a line stroked from it ends where the eraser passed; null if nothing is left.
     */
    private fun trimNewEnds(piece: List<UserPoint>, original: List<UserPoint>, by: Float): List<UserPoint>? {
        fun isOriginalEnd(p: UserPoint) = toClipper(p) == toClipper(original.first()) || toClipper(p) == toClipper(original.last())
        var line = piece
        if (!isOriginalEnd(line.first())) line = trimStart(line, by) ?: return null
        if (!isOriginalEnd(line.last())) line = trimStart(line.asReversed(), by)?.asReversed() ?: return null
        return line
    }

    /** [line] without its first [by] points of length; null if it isn't longer than that. */
    private fun trimStart(line: List<UserPoint>, by: Float): List<UserPoint>? {
        var left = by
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val step = hypot(b.x - a.x, b.y - a.y)
            if (step > left) {
                val t = left / step
                val start = UserPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t).let { fromClipper(toClipper(it)) }
                return listOf(start) + line.subList(i, line.size)
            }
            left -= step
        }
        return null
    }

    /** [paths] grown by [by] points (round corners). */
    private fun grow(paths: Paths64, by: Float): Paths64 =
        Clipper.inflatePaths(paths, by * SCALE, JoinType.Round, EndType.Polygon, 2.0, ARC_TOLERANCE)

    // --- Text markup ---

    private fun cutMarkup(markup: AnnotationShape.TextMarkup, sweep: EraserSweep): List<AnnotationShape>? {
        val erased = sweep.area()
        var changed = false
        val quads = markup.quads.flatMap { quad ->
            val left = cutQuad(quad, erased, sweep)
            if (left == null) listOf(quad) else left.also { changed = true }
        }
        if (!changed) return null
        return if (quads.isEmpty()) emptyList() else listOf(markup.copy(quads = quads))
    }

    /** What is left of [quad] along its text, or null if [erased] doesn't touch it. */
    private fun cutQuad(quad: Quad, erased: Paths64, sweep: EraserSweep): List<Quad>? {
        val length = hypot(quad.lowerRight.x - quad.lowerLeft.x, quad.lowerRight.y - quad.lowerLeft.y)
        if (length < LEFTOVER) return null
        val polygon = Paths64(toPath(listOf(quad.lowerLeft, quad.lowerRight, quad.upperRight, quad.upperLeft)))
        val hit = Clipper.intersect(polygon, erased, FillRule.NonZero)
        if (abs(Clipper.area(hit)) < MIN_AREA) return null
        // The stretches along the text (0 = left .. 1 = right) the swept area covers, merged.
        val covered = hit.map { path ->
            val along = path.map { alongText(quad, fromClipper(it)) }
            along.min().coerceIn(0f, 1f) to along.max().coerceIn(0f, 1f)
        }.sortedBy { it.first }
        val kept = ArrayList<Pair<Float, Float>>()
        var start = 0f
        for ((from, to) in covered) {
            if (from > start) kept += start to from
            start = max(start, to)
        }
        if (start < 1f) kept += start to 1f
        val minLength = leftover(sweep)
        return kept.filter { (from, to) -> (to - from) * length >= minLength }.map { (from, to) ->
            Quad(
                upperLeft = AnnotationGeometry.at(quad, from, 1f),
                upperRight = AnnotationGeometry.at(quad, to, 1f),
                lowerLeft = AnnotationGeometry.at(quad, from, 0f),
                lowerRight = AnnotationGeometry.at(quad, to, 0f),
            )
        }
    }

    /**
     * Where [point] lies along the text of [quad], 0 at its left edge and 1 at its right: its coordinate on the
     * quad's own axes (along the bottom edge, up the left edge), exact for the parallelograms text makes.
     */
    internal fun alongText(quad: Quad, point: UserPoint): Float {
        val ax = quad.lowerRight.x - quad.lowerLeft.x
        val ay = quad.lowerRight.y - quad.lowerLeft.y
        val bx = quad.upperLeft.x - quad.lowerLeft.x
        val by = quad.upperLeft.y - quad.lowerLeft.y
        val dx = point.x - quad.lowerLeft.x
        val dy = point.y - quad.lowerLeft.y
        val det = ax * by - ay * bx
        if (abs(det) < EPSILON) return (dx * ax + dy * ay) / max(ax * ax + ay * ay, EPSILON)
        return (dx * by - dy * bx) / det
    }

    // --- Helpers ---

    private fun leftover(sweep: EraserSweep) = max(LEFTOVER, sweep.radius)

    /** Whether [point] is inside [paths], filled with the nonzero rule. */
    private fun contains(paths: Paths64, point: UserPoint): Boolean {
        val px = point.x * SCALE
        val py = point.y * SCALE
        var winding = 0
        for (path in paths) {
            for (i in path.indices) {
                val a = path[i]
                val b = path[(i + 1) % path.size]
                val cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x)
                if (a.y <= py) {
                    if (b.y > py && cross > 0) winding++
                } else if (b.y <= py && cross < 0) {
                    winding--
                }
            }
        }
        return winding != 0
    }

    /** About how thick [part] is, in points: twice its area over its perimeter (the width of a long strip). */
    private fun thickness(part: Paths64): Float {
        val perimeter = part.sumOf { path ->
            path.indices.sumOf { i ->
                val a = path[i]
                val b = path[(i + 1) % path.size]
                hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            }
        }
        if (perimeter == 0.0) return 0f
        return (2 * abs(Clipper.area(part)) / perimeter / SCALE).toFloat()
    }

    private fun extent(points: List<Point64>): Float {
        val width = (points.maxOf { it.x } - points.minOf { it.x }) / SCALE
        val height = (points.maxOf { it.y } - points.minOf { it.y }) / SCALE
        return hypot(width, height).toFloat()
    }

    private fun length(line: List<UserPoint>): Float =
        (1 until line.size).sumOf { hypot(line[it].x - line[it - 1].x, line[it].y - line[it - 1].y).toDouble() }.toFloat()

    private fun UserRect.overlaps(other: UserRect) =
        left <= other.right && other.left <= right && bottom <= other.top && other.bottom <= top

    internal fun toClipper(point: UserPoint) = Point64((point.x * SCALE).roundToLong(), (point.y * SCALE).roundToLong())

    private fun fromClipper(point: Point64) = UserPoint((point.x / SCALE).toFloat(), (point.y / SCALE).toFloat())

    private fun toPath(points: List<UserPoint>): Path64 = Path64(points.size).apply { points.forEach { add(toClipper(it)) } }

    private fun fromPath(path: Path64): List<UserPoint> = path.map(::fromClipper)

    /** Areas below this, in square Clipper units (0.05 square points), are nothing. */
    private const val MIN_AREA = 500.0

    private const val EPSILON = 1e-6f
}

/** An annotation the partial eraser may cut: which one ([pick]) and what it looks like ([shape]). */
data class EraseTarget(val pick: ErasePick, val shape: AnnotationShape)

/**
 * One drag of the partial eraser over a page (plan T-a): each move of the pointer cuts the [targets] it
 * passes over (user space of the page, [radius] points around the path). [left] holds what is left of each
 * target it touched, for the live preview while dragging and for the one undo step on lift; a cut is the same
 * whether done move by move or at once, so the preview is what will be saved.
 */
class PartialEraseStroke(private val targets: List<EraseTarget>, private val radius: Float) {

    private val pieces = LinkedHashMap<ErasePick, List<AnnotationShape>>()
    private var last: UserPoint? = null

    /** The targets touched so far, in the order of [targets], with what is left of each (empty: all gone). */
    val left: Map<ErasePick, List<AnnotationShape>>
        get() = targets.mapNotNull { target -> pieces[target.pick]?.let { target.pick to it } }.toMap()

    val isEmpty: Boolean get() = pieces.isEmpty()

    /** The pointer moved through [points] (the first call: where it went down). True if something was cut. */
    fun moveTo(points: List<UserPoint>): Boolean {
        if (points.isEmpty()) return false
        val sweep = EraserSweep.along(listOfNotNull(last) + points, radius) ?: return false
        last = points.last()
        var changed = false
        for (target in targets) {
            var touched = false
            val next = (pieces[target.pick] ?: listOf(target.shape)).flatMap { shape ->
                PartialEraser.cut(shape, sweep)?.also { touched = true } ?: listOf(shape)
            }
            if (touched) {
                pieces[target.pick] = next
                changed = true
            }
        }
        return changed
    }
}
