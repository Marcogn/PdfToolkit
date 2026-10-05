package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text selection on synthetic glyphs, upright and turned (spec §7.4, phase 7a). Positions are page
 * points as displayed; every glyph is 6 wide, ascent 8 and descent 2.
 */
class TextSelectionTest {

    private val width = 6f
    private val ascent = 8f
    private val descent = 2f

    /**
     * [lines] of text laid out from [origin], each line [lineGap] below the previous, running along
     * [direction] (a unit vector on the display); "up" is [direction] turned a quarter anticlockwise
     * on screen. A space in a line is a word break, not a glyph.
     */
    private fun page(vararg lines: String, origin: Offset = Offset(10f, 20f), direction: Offset = Offset(1f, 0f), lineGap: Float = 14f): PageText {
        val up = Offset(direction.y, -direction.x)
        val glyphs = mutableListOf<TextGlyph>()
        lines.forEachIndexed { lineIndex, line ->
            var pen = origin - up * (lineGap * lineIndex)
            var spaceBefore = lineIndex > 0
            for (char in line) {
                if (char == ' ') {
                    pen += direction * width
                    spaceBefore = true
                    continue
                }
                glyphs += TextGlyph(char.toString(), pen, pen + direction * width, up * ascent, up * -descent, spaceBefore)
                pen += direction * width
                spaceBefore = false
            }
        }
        return PageText(0, glyphs)
    }

    /** Centre of glyph [index] of [page]. */
    private fun centre(page: PageText, index: Int): Offset = page.glyphs[index].let { (it.origin + it.end) / 2f + (it.ascent + it.descent) / 2f }

    private fun assertNear(expected: Offset, actual: Offset, message: String = "") {
        assertEquals("$message x of $actual", expected.x, actual.x, 0.001f)
        assertEquals("$message y of $actual", expected.y, actual.y, 0.001f)
    }

    @Test
    fun `a long press selects the word under the finger`() {
        val text = page("ciao mondo bello")
        val selection = TextSelection(text)
        // "mondo" is glyphs 4..8.
        val word = selection.wordAt(centre(text, 6))!!
        assertEquals(GlyphRange(4, 9), word)
        assertEquals("mondo", selection.text(word))
        assertEquals(GlyphRange(0, 4), selection.wordAt(centre(text, 0)))
        assertEquals(GlyphRange(9, 14), selection.wordAt(centre(text, 13)))
    }

    @Test
    fun `a space drawn as a glyph breaks words too`() {
        // PdfBox extracts the spaces a PDF draws: "ab cd" as five glyphs, none flagged as a break.
        val text = page("abxcd").let { p -> PageText(0, p.glyphs.mapIndexed { i, g -> if (i == 2) g.copy(text = " ") else g }) }
        val selection = TextSelection(text)
        assertEquals(GlyphRange(0, 2), selection.wordAt(centre(text, 1)))
        assertEquals(GlyphRange(3, 5), selection.wordAt(centre(text, 3)))
        assertNull(selection.wordAt(centre(text, 2)))
        assertEquals("ab cd", selection.text(selection.all()!!))
    }

    @Test
    fun `nothing is selected between words or off the text`() {
        val text = page("ciao mondo")
        val selection = TextSelection(text)
        // The gap between "ciao" and "mondo" (the space at x 34..40).
        assertNull(selection.wordAt(Offset(37f, 18f)))
        assertNull(selection.wordAt(Offset(10f, 200f)))
        assertTrue(TextSelection(PageText(0, emptyList())).isEmpty)
    }

    @Test
    fun `a handle lands before or after the glyph it is nearest to`() {
        val text = page("abc")
        val selection = TextSelection(text)
        // Glyph 1 spans x 16..22: its first half puts the handle before it, its second half after.
        assertEquals(1, selection.boundaryAt(Offset(17f, 17f)))
        assertEquals(2, selection.boundaryAt(Offset(21f, 17f)))
        // Past the end of the line, below it: after the last glyph.
        assertEquals(3, selection.boundaryAt(Offset(60f, 40f)))
        assertEquals(0, selection.boundaryAt(Offset(0f, 0f)))
    }

    @Test
    fun `handles in either order give the same range`() {
        val selection = TextSelection(page("abcdef"))
        assertEquals(GlyphRange(1, 4), selection.between(1, 4))
        assertEquals(GlyphRange(1, 4), selection.between(4, 1))
        assertNull(selection.between(3, 3))
        assertEquals(GlyphRange(0, 6), selection.between(-5, 99))
    }

    @Test
    fun `text across lines keeps a newline at the line break`() {
        val text = page("prima riga", "seconda")
        val selection = TextSelection(text)
        assertEquals("prima riga\nseconda", selection.text(selection.all()!!))
        // From "riga" to "sec".
        assertEquals("riga\nsec", selection.text(GlyphRange(5, 12)))
    }

    @Test
    fun `a selection over two lines is one run per line`() {
        val text = page("prima riga", "seconda")
        val runs = TextSelection(text).runs(GlyphRange(5, 12))
        assertEquals(2, runs.size)
        val (first, second) = runs
        // "riga": x 46..70 on the baseline y 20 ("prima" is 10..40, then a space).
        assertNear(Offset(46f, 20f), first.origin, "first")
        assertNear(Offset(70f, 20f), first.end, "first")
        assertNear(Offset(0f, -ascent), first.ascent, "first")
        assertNear(Offset(0f, descent), first.descent, "first")
        // "sec": the next line, 14 lower, from the left margin.
        assertNear(Offset(10f, 34f), second.origin, "second")
        assertNear(Offset(28f, 34f), second.end, "second")
    }

    @Test
    fun `a run covers the tallest glyph of its line`() {
        val base = page("ab")
        // A superscript "2" after "ab": raised 4, smaller.
        val raised = TextGlyph("2", Offset(22f, 16f), Offset(26f, 16f), Offset(0f, -5f), Offset(0f, 1f))
        val text = PageText(0, base.glyphs + raised)
        val run = TextSelection(text).runs(GlyphRange(0, 3)).single()
        assertNear(Offset(10f, 20f), run.origin)
        assertNear(Offset(26f, 20f), run.end)
        // Top of the superscript: 16 - 5 = 11, that is 9 above the baseline, more than the ascent of 8.
        assertNear(Offset(0f, -9f), run.ascent)
        assertNear(Offset(0f, descent), run.descent)
    }

    @Test
    fun `selection works the same on text running down the display`() {
        // A page turned by 90°: text runs down, its top faces right.
        val down = Offset(0f, 1f)
        val text = page("ciao mondo", origin = Offset(100f, 10f), direction = down)
        val selection = TextSelection(text)
        val word = selection.wordAt(centre(text, 5))!!
        assertEquals("mondo", selection.text(word))
        val run = selection.runs(word).single()
        // "mondo" starts at 10 + 5 * 6 = 40 down the baseline x = 100, and is 30 long.
        assertNear(Offset(100f, 40f), run.origin)
        assertNear(Offset(100f, 70f), run.end)
        assertNear(Offset(ascent, 0f), run.ascent)
        assertNear(Offset(-descent, 0f), run.descent)
        // The handle at the far end of the word goes after it.
        assertEquals(word.end, selection.boundaryAt(Offset(103f, 69f)))
    }

    @Test
    fun `glyphs without text still take part in ranges`() {
        val base = page("ab")
        val unmapped = base.glyphs[1].copy(text = "")
        val selection = TextSelection(PageText(0, listOf(base.glyphs[0], unmapped)))
        assertEquals("a", selection.text(selection.all()!!))
        assertEquals(1, selection.runs(selection.all()!!).size)
    }
}
