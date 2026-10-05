package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Glyphs [start] until [end] of a page (indices into [PageText.glyphs]); never empty. */
data class GlyphRange(val start: Int, val end: Int) {
    init {
        require(start in 0 until end) { "Empty or negative range: $start..$end" }
    }

    val size: Int get() = end - start

    /** This range with its start moved to [boundary], kept before the end (a handle can't cross the other one). */
    fun withStart(boundary: Int): GlyphRange = GlyphRange(boundary.coerceIn(0, end - 1), end)

    /** This range with its end moved to [boundary], kept after the start and within [glyphCount] glyphs. */
    fun withEnd(boundary: Int, glyphCount: Int): GlyphRange = GlyphRange(start, boundary.coerceIn(start + 1, glyphCount.coerceAtLeast(start + 1)))
}

/**
 * The area covered by glyphs on one line, in page points (as displayed): a parallelogram with the
 * baseline from [origin] to [end], stretched by [ascent] and [descent] like a [TextGlyph].
 */
data class LineRun(val origin: Offset, val end: Offset, val ascent: Offset, val descent: Offset) {
    val upperLeft: Offset get() = origin + ascent
    val upperRight: Offset get() = end + ascent
    val lowerLeft: Offset get() = origin + descent
    val lowerRight: Offset get() = end + descent

    /**
     * The same area in the user space of [space]'s page, ready for `/QuadPoints`. Corners keep
     * their names: "upper" stays towards the top of the letters, so the quad reads the same way in
     * any rotation (the flip between the y-down display and the y-up user space keeps text upright).
     */
    fun toUser(space: PdfPageSpace): Quad {
        val toUser = space.displayToUser
        fun user(point: Offset) = toUser.map(point).let { UserPoint(it.x, it.y) }
        return Quad(user(upperLeft), user(upperRight), user(lowerLeft), user(lowerRight))
    }
}

/**
 * Text selection on one page (spec §7.4), on the glyphs of [PageText] in their reading order:
 * what is under a finger, the word there, the range between two handles, its text for the
 * clipboard and its area, one [LineRun] per line, for the highlight and for drawing the selection.
 *
 * Positions are page points as displayed (see [TextGlyph]): what the viewer's mapper gives for a
 * touch. A selection is a contiguous range in reading order, as in every PDF reader.
 */
class TextSelection(page: PageText) {

    private val glyphs = page.glyphs

    /** Whether the page has any text to select. */
    val isEmpty: Boolean get() = glyphs.isEmpty()

    val glyphCount: Int get() = glyphs.size

    /** The glyph whose box contains [point], or null. Later glyphs win, as they are drawn on top. */
    fun glyphAt(point: Offset): Int? = glyphs.indices.lastOrNull { contains(glyphs[it], point) }

    /** The glyph nearest to [point] (0 if inside it), or null on a page without text. */
    fun nearestGlyph(point: Offset): Int? = glyphs.indices.minByOrNull { distance(glyphs[it], point) }

    /**
     * The word under [point] (a long press): from the glyph there, out to the word breaks on
     * either side. Null when the point isn't on a glyph.
     */
    fun wordAt(point: Offset): GlyphRange? {
        val at = glyphAt(point)?.takeUnless { isSpace(it) } ?: return null
        var start = at
        while (start > 0 && !glyphs[start].spaceBefore && !isSpace(start - 1)) start--
        var end = at + 1
        while (end < glyphs.size && !glyphs[end].spaceBefore && !isSpace(end)) end++
        return GlyphRange(start, end)
    }

    /** PdfBox also extracts the space characters a PDF draws: they break words like a gap does. */
    private fun isSpace(index: Int): Boolean = glyphs[index].text.let { it.isNotEmpty() && it.isBlank() }

    /**
     * The boundary between glyphs nearest to [point], 0..glyph count: before the nearest glyph if
     * the point is on its first half along the baseline, after it otherwise. Where a dragged handle
     * lands.
     */
    fun boundaryAt(point: Offset): Int? {
        val index = nearestGlyph(point) ?: return null
        val glyph = glyphs[index]
        val along = glyph.end - glyph.origin
        val length = along.getDistance()
        if (length < EPSILON) return index
        val t = ((point - glyph.origin).dot(along)) / (length * length)
        return if (t < HALF) index else index + 1
    }

    /** The glyphs between boundaries [anchor] and [focus], in either order; null if they meet. */
    fun between(anchor: Int, focus: Int): GlyphRange? {
        val start = min(anchor, focus).coerceIn(0, glyphs.size)
        val end = max(anchor, focus).coerceIn(0, glyphs.size)
        return if (start < end) GlyphRange(start, end) else null
    }

    /** Everything on the page. */
    fun all(): GlyphRange? = if (glyphs.isEmpty()) null else GlyphRange(0, glyphs.size)

    /**
     * The text of [range] for the clipboard: a word break is a space, a line break a newline
     * (unlike search, where both are spaces).
     */
    fun text(range: GlyphRange): String = buildString {
        for (i in range.start until range.end) {
            val glyph = glyphs[i]
            if (i > range.start && glyph.spaceBefore) append(if (glyphs[i - 1].isFollowedOnLineBy(glyph)) ' ' else '\n')
            append(glyph.text)
        }
    }

    /**
     * The area of [range], one run per line in reading order. Each run lies on its first glyph's
     * baseline and is as tall as the tallest of its glyphs, so a superscript or a bigger letter is
     * covered and the run stays a parallelogram (one quad).
     */
    fun runs(range: GlyphRange): List<LineRun> {
        val runs = mutableListOf<LineRun>()
        var first = range.start
        for (i in range.start + 1..range.end) {
            if (i == range.end || !glyphs[i - 1].isFollowedOnLineBy(glyphs[i])) {
                runs += run(first, i)
                first = i
            }
        }
        return runs
    }

    /** One line's glyphs [from] until [to] as one parallelogram on the axes of the first glyph. */
    private fun run(from: Int, to: Int): LineRun {
        val base = glyphs[from]
        val direction = base.direction
        // "Up" on the display, perpendicular to the baseline, from the descent towards the ascent.
        val up = Offset(direction.y, -direction.x).let { if (it.dot(base.up) < 0) -it else it }
        var minAlong = Float.MAX_VALUE
        var maxAlong = -Float.MAX_VALUE
        var minUp = Float.MAX_VALUE
        var maxUp = -Float.MAX_VALUE
        for (i in from until to) {
            val g = glyphs[i]
            for (corner in arrayOf(g.origin + g.ascent, g.origin + g.descent, g.end + g.ascent, g.end + g.descent)) {
                val v = corner - base.origin
                val along = v.dot(direction)
                val across = v.dot(up)
                minAlong = min(minAlong, along)
                maxAlong = max(maxAlong, along)
                minUp = min(minUp, across)
                maxUp = max(maxUp, across)
            }
        }
        return LineRun(
            origin = base.origin + direction * minAlong,
            end = base.origin + direction * maxAlong,
            ascent = up * maxUp,
            descent = up * minUp,
        )
    }

    /** Whether [point] is inside [glyph]'s parallelogram. */
    private fun contains(glyph: TextGlyph, point: Offset): Boolean {
        val local = local(glyph, point) ?: return false
        return local.x in 0f..1f && local.y in 0f..1f
    }

    /**
     * [point] in the glyph's own coordinates: x from 0 (origin) to 1 (end) along the baseline, y
     * from 0 (descent) to 1 (ascent). Null for a glyph with no width or no height.
     */
    private fun local(glyph: TextGlyph, point: Offset): Offset? {
        val e1 = glyph.end - glyph.origin
        val e2 = glyph.up
        val det = e1.x * e2.y - e1.y * e2.x
        if (abs(det) < EPSILON) return null
        val v = point - (glyph.origin + glyph.descent)
        return Offset((v.x * e2.y - v.y * e2.x) / det, (e1.x * v.y - e1.y * v.x) / det)
    }

    /** Distance from [point] to [glyph]'s parallelogram, 0 inside (a glyph with no width is a segment). */
    private fun distance(glyph: TextGlyph, point: Offset): Float {
        if (contains(glyph, point)) return 0f
        val corners = arrayOf(glyph.origin + glyph.descent, glyph.end + glyph.descent, glyph.end + glyph.ascent, glyph.origin + glyph.ascent)
        var best = Float.MAX_VALUE
        for (i in corners.indices) best = min(best, segmentDistance(corners[i], corners[(i + 1) % corners.size], point))
        return best
    }

    private fun segmentDistance(a: Offset, b: Offset, p: Offset): Float {
        val ab = b - a
        val lengthSquared = ab.dot(ab)
        if (lengthSquared < EPSILON) return (p - a).getDistance()
        val t = ((p - a).dot(ab) / lengthSquared).coerceIn(0f, 1f)
        return (p - (a + ab * t)).getDistance()
    }

    private fun Offset.dot(other: Offset): Float = x * other.x + y * other.y

    private companion object {
        const val EPSILON = 1e-6f
        const val HALF = 0.5f
    }
}
