package com.marcogn.pdftoolkit.domain.fill

import java.text.Normalizer

/**
 * Layout of a [TextOverlay], shared by the screen and by the PDF writer so the text lands in the
 * same place in both: lines split on `\n`, left aligned, the first baseline at a fixed distance
 * from the top of the box, a fixed line height. Distances are in points, measured from the box's
 * top-left corner with y down.
 *
 * The metrics are those of the bundled Noto Sans Regular (`assets/fonts`, 1000 units per em,
 * `hhea` ascender 1069, descender -293, no line gap; the `OS/2` typo metrics are the same). Using
 * our own numbers rather than what each side reads from the font keeps the two in step.
 */
object TextBlock {
    const val ASCENT = 1.069f
    const val DESCENT = 0.293f
    const val LINE_HEIGHT = ASCENT + DESCENT

    /** Space between the text and the edge of its box, in ems. */
    const val PADDING = 0.15f

    /** Width of a box with no text in it, in ems, so it can still be seen and tapped. */
    const val EMPTY_WIDTH = 1f

    const val DEFAULT_FONT_SIZE = 12f
    const val MIN_FONT_SIZE = 6f
    const val MAX_FONT_SIZE = 72f

    fun lines(text: String): List<String> = text.split('\n')

    /** Size of the box for lines that are [lineWidths] points wide at [fontSize]. */
    fun boxSize(lineWidths: List<Float>, fontSize: Float): Pair<Float, Float> {
        val widest = lineWidths.maxOrNull()?.takeIf { it > 0f } ?: (EMPTY_WIDTH * fontSize)
        val lines = lineWidths.size.coerceAtLeast(1)
        return (widest + 2 * PADDING * fontSize) to (lines * LINE_HEIGHT * fontSize + 2 * PADDING * fontSize)
    }

    /** Where line [index] starts: x of its left edge and y of its baseline, from the box's top-left. */
    fun lineOrigin(index: Int, fontSize: Float): Pair<Float, Float> =
        PADDING * fontSize to (PADDING + ASCENT + index * LINE_HEIGHT) * fontSize

    /**
     * The text as it can be written: composed form (NFC, so "e" + combining accent becomes "è",
     * which the font has), tabs as spaces, `\r` dropped, and no characters the font can't draw
     * ([supports] answers for one code point). Both sides apply it, so what the screen shows is
     * what gets written.
     */
    fun sanitize(text: String, supports: (Int) -> Boolean): String {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC).replace('\t', ' ').replace("\r", "")
        val out = StringBuilder(composed.length)
        var i = 0
        while (i < composed.length) {
            val codePoint = composed.codePointAt(i)
            if (codePoint == '\n'.code || supports(codePoint)) out.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }
}

/**
 * The strokes of a [MarkOverlay] in a unit box (0..1, origin top-left, y down), scaled to the box
 * when drawn; [STROKE] is the line width as a fraction of the box's smaller side.
 */
object MarkShape {
    const val STROKE = 0.12f

    /** Default side of a new mark, in points. */
    const val DEFAULT_SIZE = 14f

    fun strokes(kind: MarkKind): List<List<Pair<Float, Float>>> = when (kind) {
        MarkKind.CHECK -> listOf(listOf(0.12f to 0.55f, 0.4f to 0.85f, 0.9f to 0.15f))
        MarkKind.CROSS -> listOf(listOf(0.15f to 0.15f, 0.85f to 0.85f), listOf(0.85f to 0.15f, 0.15f to 0.85f))
    }
}
