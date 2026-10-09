package com.marcogn.pdftoolkit.pdf.ocr

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the geometry needs of the font the text layer is written with, in text space units (font size 1). */
interface OcrFontMetrics {
    /** Top of the font's line above the baseline (positive). */
    val ascent: Float

    /** Bottom of the font's line below the baseline (negative). */
    val descent: Float

    /** Width of [text] written at font size 1, with no scaling. */
    fun advance(text: String): Float
}

/**
 * One piece of the invisible text layer: [text] shown at [fontSize] with the text matrix
 * [matrix] (`Tm`, in user space: a rotation and a translation) and the horizontal scaling
 * [horizontalScaling] (`Tz`, percent).
 */
data class OcrRun(val text: String, val matrix: Affine, val fontSize: Float, val horizontalScaling: Float)

/**
 * A recognised line laid out in user space: the baseline frame of its box.
 *
 * [bottomLeft] is the box's bottom-left corner (the bottom of the descenders), [direction] the
 * unit vector along the line as it reads, [up] the unit vector across it towards the top,
 * [length] and [height] the box's size along them.
 */
data class OcrLineFrame(val bottomLeft: Offset, val direction: Offset, val up: Offset, val length: Float, val height: Float) {

    /** Where [box] starts and ends along the line, measured from [bottomLeft], within `0..length`. */
    fun span(box: OcrQuad): ClosedFloatingPointRange<Float> {
        val along = box.corners.map { dot(it - bottomLeft, direction) }
        return max(0f, along.min())..min(length, along.max())
    }
}

/**
 * Geometry of the invisible text layer (spec §7.2, plan 10a): recognised boxes in page points →
 * text runs in user space, through [PdfPageSpace] like every other write (CLAUDE.md, coordinates).
 *
 * One line = one font size and one angle, from the line's box: the size makes the font's line
 * (ascent to descent, the same extent the text extractor uses for a glyph's box) as tall as the
 * box, the angle is the box's bottom edge. Each word is then placed at its own position along the
 * baseline and stretched (`Tz`) to its own width, with a space stretched over the gap to the next
 * word: Noto Sans is not the scanned font, so one stretch for the whole line would drift the
 * words off their image and could close the gaps that separate words (decisions, 2026-10-09).
 */
object OcrGeometry {

    /**
     * Pixels per point the page is rendered at for recognition: about 200 dpi, where body text
     * (8–12 pt) gets the 16–24 px per character ML Kit asks for, but never more than [MAX_SIDE_PX]
     * on the long side (pages measured in large units, memory).
     */
    fun renderScale(page: PageSize): Float = min(TARGET_SCALE, MAX_SIDE_PX / max(page.width, page.height))

    /** The frame of [box], already in user space; null for a degenerate box. */
    fun lineFrame(box: OcrQuad): OcrLineFrame? {
        val bottom = box.bottomRight - box.bottomLeft
        val length = hypot(bottom.x, bottom.y)
        if (length < MIN_SIZE) return null
        val direction = bottom / length
        // A quarter turn counterclockwise in user space (y up): the reading direction's "up".
        val up = Offset(-direction.y, direction.x)
        val height = (dot(box.topLeft - box.bottomLeft, up) + dot(box.topRight - box.bottomRight, up)) / 2f
        if (height < MIN_SIZE) return null
        return OcrLineFrame(box.bottomLeft, direction, up, length, height)
    }

    /** The runs that write [line] (in page points) on a page with user space [space]; empty if nothing can be placed. */
    fun runs(line: OcrLine, space: PdfPageSpace, font: OcrFontMetrics): List<OcrRun> {
        val toUser = space.displayToUser
        val user = line.map(toUser::map)
        val frame = lineFrame(user.box) ?: return emptyList()
        val lineExtent = font.ascent - font.descent
        if (lineExtent <= 0f) return emptyList()
        val fontSize = frame.height / lineExtent
        // From the bottom of the box up to the baseline.
        val baseline = frame.up * (-font.descent * fontSize)
        val spaceAdvance = font.advance(" ") * fontSize

        fun runAt(text: String, start: Float, width: Float, advance: Float) = OcrRun(
            text = text,
            matrix = frame.matrixAt(frame.bottomLeft + frame.direction * start + baseline),
            fontSize = fontSize,
            horizontalScaling = PERCENT * width / advance,
        )

        val runs = mutableListOf<OcrRun>()
        var previousEnd = 0f
        for (word in user.wordsOrWhole) {
            if (word.text.isBlank()) continue
            val span = frame.span(word.box)
            // Words never overlap: an overlap would join them into one word for the extractor.
            val start = if (runs.isEmpty()) span.start else max(span.start, previousEnd)
            val end = span.endInclusive
            val advance = font.advance(word.text) * fontSize
            if (end - start < MIN_SIZE || advance <= 0f) continue
            if (runs.isNotEmpty() && spaceAdvance > 0f && start - previousEnd > MIN_SIZE) {
                runs += runAt(" ", previousEnd, start - previousEnd, spaceAdvance)
            }
            runs += runAt(word.text, start, end - start, advance)
            previousEnd = end
        }
        return runs
    }

    private fun OcrLineFrame.matrixAt(origin: Offset) = Affine(direction.x, direction.y, up.x, up.y, origin.x, origin.y)

    private const val TARGET_SCALE = 200f / 72f
    private const val MAX_SIDE_PX = 3000f

    /** Below this (in points) a box or a gap counts as empty. */
    private const val MIN_SIZE = 0.01f
    private const val PERCENT = 100f
}

private fun dot(a: Offset, b: Offset): Float = a.x * b.x + a.y * b.y
