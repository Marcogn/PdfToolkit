package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One glyph as extracted, placed in **page points** (the page as displayed: origin top-left,
 * y down, `/Rotate` applied; see [com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper]).
 *
 * The glyph is a parallelogram: the baseline from [origin] to [end] (where the next glyph would
 * start), stretched by [ascent] and [descent], the vectors from the baseline to the top and the
 * bottom of the font's line. On a turned page, or for turned text, these vectors are turned too.
 *
 * [text] is the glyph's Unicode text as PdfBox reads it: usually one character, sometimes more
 * (a ligature), sometimes none (no Unicode mapping). [spaceBefore]: a word or line break comes
 * before this glyph ([isFollowedInWordBy] false for the previous one); a line break counts as a
 * space (spec §5.1).
 */
data class TextGlyph(
    val text: String,
    val origin: Offset,
    val end: Offset,
    val ascent: Offset,
    val descent: Offset,
    val spaceBefore: Boolean = false,
) {
    /** The axis-aligned box around the glyph, in page points. */
    val bounds: Rect
        get() {
            val corners = arrayOf(origin + ascent, origin + descent, end + ascent, end + descent)
            return Rect(
                left = corners.minOf { it.x },
                top = corners.minOf { it.y },
                right = corners.maxOf { it.x },
                bottom = corners.maxOf { it.y },
            )
        }

    /** From the bottom to the top of the line, in page points. */
    internal val up: Offset get() = ascent - descent

    /** Unit vector along the baseline, from the glyph's "up" (robust for zero-width glyphs). */
    internal val direction: Offset
        get() {
            val u = up
            val length = hypot(u.x, u.y)
            return if (length < EPSILON) Offset(1f, 0f) else Offset(-u.y / length, u.x / length)
        }

    /**
     * Whether [next] continues this glyph's line: same writing direction, baseline close enough
     * (a superscript stays on its line) and not further back than a little overlap.
     */
    fun isFollowedOnLineBy(next: TextGlyph): Boolean = gapTo(next)?.let { it >= -LINE_TOLERANCE } ?: false

    /** Whether [next] continues the same word: on this line, with no more than a letter gap. */
    fun isFollowedInWordBy(next: TextGlyph): Boolean = gapTo(next)?.let { it >= -LINE_TOLERANCE && it <= WORD_GAP } ?: false

    /**
     * How far [next] starts after this glyph's end along the baseline, in line heights; null if
     * it is on another line (different direction, or baseline too far across).
     */
    private fun gapTo(next: TextGlyph): Float? {
        val dir = direction
        val nextDir = next.direction
        if (dir.x * nextDir.x + dir.y * nextDir.y < SAME_DIRECTION) return null
        val height = max(max(up.length(), next.up.length()), EPSILON)
        val delta = next.origin - origin
        val across = abs(dir.x * delta.y - dir.y * delta.x)
        if (across > height * LINE_TOLERANCE) return null
        val fromEnd = next.origin - end
        return (dir.x * fromEnd.x + dir.y * fromEnd.y) / height
    }

    private fun Offset.length() = hypot(x, y)

    private companion object {
        const val EPSILON = 1e-4f
        const val SAME_DIRECTION = 0.95f // cos ~18°
        const val LINE_TOLERANCE = 0.5f

        /**
         * Above this gap (in line heights, ~0.14 em) two glyphs are two words. PdfBox's default
         * is half a space width (`PDFTextStripper.spacingTolerance`), ~0.14 em in Helvetica.
         */
        const val WORD_GAP = 0.15f
    }
}

/** The text of one page, glyph by glyph in PdfBox's extraction order. */
data class PageText(val pageIndex: Int, val glyphs: List<TextGlyph>)

/** A query folded like the index ([TextNormalizer]); null from [of] when there is nothing to look for. */
class SearchQuery private constructor(val text: String) {
    override fun equals(other: Any?): Boolean = other is SearchQuery && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = "SearchQuery($text)"

    companion object {
        fun of(raw: String): SearchQuery? = TextNormalizer.normalize(raw).text.trim().takeIf { it.isNotEmpty() }?.let(::SearchQuery)
    }
}

/**
 * One occurrence on [pageIndex]: one rectangle per line it covers, in page points, ready for
 * [com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper.pageRectToScreen].
 */
data class TextMatch(val pageIndex: Int, val rects: List<Rect>) {
    /** Everything the match covers, to scroll to it and centre it. */
    val bounds: Rect
        get() = rects.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
}

/**
 * Searchable form of one page, kept in memory while the document is open (spec §5.1): the folded
 * text, the glyph behind each of its characters and, per glyph, only its box and its line. A
 * few bytes per character, whatever the number of glyphs.
 */
class PageTextIndex private constructor(
    val pageIndex: Int,
    private val text: String,
    private val glyphOf: IntArray,
    private val boxes: FloatArray,
    private val lineOf: IntArray,
) {
    /** No searchable text on the page (a scan, or only drawings). */
    val isEmpty: Boolean get() = text.isBlank()

    /** The occurrences of [query] in reading order, not overlapping one another. */
    fun find(query: SearchQuery): List<TextMatch> {
        val needle = query.text
        val matches = mutableListOf<TextMatch>()
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) break
            rectsOf(at, at + needle.length).takeIf { it.isNotEmpty() }?.let { matches += TextMatch(pageIndex, it) }
            from = at + needle.length
        }
        return matches
    }

    /** The glyphs behind `text[start until end]`, as one box per line. */
    private fun rectsOf(start: Int, end: Int): List<Rect> {
        val rects = mutableListOf<Rect>()
        var line = -1
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var lastGlyph = -1
        for (i in start until end) {
            val glyph = glyphOf[i]
            if (glyph < 0 || glyph == lastGlyph) continue
            lastGlyph = glyph
            val b = glyph * BOX_SIZE
            if (lineOf[glyph] != line) {
                if (line >= 0) rects += Rect(left, top, right, bottom)
                line = lineOf[glyph]
                left = boxes[b]
                top = boxes[b + 1]
                right = boxes[b + 2]
                bottom = boxes[b + 3]
            } else {
                left = min(left, boxes[b])
                top = min(top, boxes[b + 1])
                right = max(right, boxes[b + 2])
                bottom = max(bottom, boxes[b + 3])
            }
        }
        if (line >= 0) rects += Rect(left, top, right, bottom)
        return rects
    }

    companion object {
        private const val BOX_SIZE = 4

        fun build(page: PageText): PageTextIndex {
            val glyphs = page.glyphs
            val raw = StringBuilder()
            val rawGlyph = IntArrayBuilder(glyphs.size + glyphs.size / 4)
            val boxes = FloatArray(glyphs.size * BOX_SIZE)
            val lineOf = IntArray(glyphs.size)
            var line = 0
            glyphs.forEachIndexed { index, glyph ->
                if (glyph.spaceBefore && raw.isNotEmpty()) {
                    raw.append(' ')
                    rawGlyph.add(-1)
                }
                glyph.text.forEach { char ->
                    raw.append(char)
                    rawGlyph.add(index)
                }
                val bounds = glyph.bounds
                val b = index * BOX_SIZE
                boxes[b] = bounds.left
                boxes[b + 1] = bounds.top
                boxes[b + 2] = bounds.right
                boxes[b + 3] = bounds.bottom
                if (index > 0 && !glyphs[index - 1].isFollowedOnLineBy(glyph)) line++
                lineOf[index] = line
            }
            val normalized = TextNormalizer.normalize(raw)
            val rawGlyphs = rawGlyph.toArray()
            val glyphOf = IntArray(normalized.text.length) { rawGlyphs[normalized.sourceIndex[it]] }
            return PageTextIndex(page.pageIndex, normalized.text, glyphOf, boxes, lineOf)
        }
    }
}
