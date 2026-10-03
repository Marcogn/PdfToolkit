package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search on synthetic glyphs: every character 5 pt wide, line from 8 pt above to 2 pt below the baseline. */
class PageTextIndexTest {

    private val ascent = Offset(0f, -8f)
    private val descent = Offset(0f, 2f)

    /**
     * Upright glyphs for [text] starting at ([x], [baseline]); a space in [text] is a PdfBox word
     * separator (no glyph, the next one gets `spaceBefore`), and the first glyph starts a new word
     * when [newWord].
     */
    private fun line(text: String, x: Float, baseline: Float, newWord: Boolean = false): List<TextGlyph> {
        val glyphs = mutableListOf<TextGlyph>()
        var pen = x
        var spaceBefore = newWord
        for (char in text) {
            if (char == ' ') {
                spaceBefore = true
            } else {
                glyphs += TextGlyph(char.toString(), Offset(pen, baseline), Offset(pen + 5f, baseline), ascent, descent, spaceBefore)
                spaceBefore = false
            }
            pen += 5f
        }
        return glyphs
    }

    private fun index(vararg glyphs: List<TextGlyph>) = PageTextIndex.build(PageText(3, glyphs.toList().flatten()))

    private fun find(index: PageTextIndex, query: String) = index.find(SearchQuery.of(query)!!)

    private fun assertRect(expected: Rect, actual: Rect) {
        val tolerance = 0.001f
        assertEquals("left of $actual", expected.left, actual.left, tolerance)
        assertEquals("top of $actual", expected.top, actual.top, tolerance)
        assertEquals("right of $actual", expected.right, actual.right, tolerance)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, tolerance)
    }

    @Test
    fun `a word without its accent finds the accented word`() {
        val page = index(line("Ma perché no", 10f, 100f))
        val matches = find(page, "perche")
        assertEquals(1, matches.size)
        assertEquals(3, matches[0].pageIndex)
        // "perché" is characters 3..8 of the line: x 25..55.
        assertRect(Rect(25f, 92f, 55f, 102f), matches[0].rects.single())
    }

    @Test
    fun `a phrase across a line break is found, with one rectangle per line`() {
        val page = index(line("prima riga", 10f, 100f), line("seconda", 10f, 114f, newWord = true))
        val matches = find(page, "riga seconda")
        assertEquals(1, matches.size)
        val rects = matches[0].rects
        assertEquals(2, rects.size)
        assertRect(Rect(40f, 92f, 60f, 102f), rects[0])
        assertRect(Rect(10f, 106f, 45f, 116f), rects[1])
        assertRect(Rect(10f, 92f, 60f, 116f), matches[0].bounds)
    }

    @Test
    fun `several spaces in the query match one break`() {
        val page = index(line("prima riga", 10f, 100f), line("seconda", 10f, 114f, newWord = true))
        assertEquals(1, find(page, "RIGA    Seconda").size)
    }

    @Test
    fun `words on one line give one rectangle that covers the space between them`() {
        val page = index(line("uno due tre", 0f, 50f))
        assertRect(Rect(0f, 42f, 35f, 52f), find(page, "uno due").single().rects.single())
    }

    @Test
    fun `occurrences come in reading order and don't overlap`() {
        val page = index(line("aaaa", 0f, 50f))
        val matches = find(page, "aa")
        assertEquals(2, matches.size)
        assertRect(Rect(0f, 42f, 10f, 52f), matches[0].rects.single())
        assertRect(Rect(10f, 42f, 20f, 52f), matches[1].rects.single())
    }

    @Test
    fun `glued words don't match across a break, and a break doesn't need a space glyph`() {
        val page = index(line("fine", 0f, 50f), line("riga", 0f, 64f, newWord = true))
        assertTrue(find(page, "finer").isEmpty())
        assertEquals(1, find(page, "fine riga").size)
    }

    @Test
    fun `a match inside a ligature highlights the whole ligature glyph`() {
        val glyphs = listOf(
            TextGlyph("e", Offset(0f, 50f), Offset(5f, 50f), ascent, descent),
            TextGlyph("ﬁ", Offset(5f, 50f), Offset(13f, 50f), ascent, descent),
            TextGlyph("e", Offset(13f, 50f), Offset(18f, 50f), ascent, descent),
        )
        val page = PageTextIndex.build(PageText(0, glyphs))
        assertRect(Rect(0f, 42f, 13f, 52f), find(page, "ef").single().rects.single())
        assertRect(Rect(5f, 42f, 18f, 52f), find(page, "fie").single().rects.single())
    }

    @Test
    fun `a page with no glyphs or only blanks is empty`() {
        assertTrue(PageTextIndex.build(PageText(0, emptyList())).isEmpty)
        val blanks = listOf(TextGlyph(" ", Offset(0f, 0f), Offset(3f, 0f), ascent, descent))
        assertTrue(PageTextIndex.build(PageText(0, blanks)).isEmpty)
        assertTrue(!index(line("x", 0f, 10f)).isEmpty)
    }

    @Test
    fun `a superscript stays on its line`() {
        val glyphs = line("nota", 0f, 50f) +
            TextGlyph("1", Offset(20f, 47f), Offset(23f, 47f), ascent * 0.6f, descent * 0.6f) +
            line(" dopo", 23f, 50f)
        val page = PageTextIndex.build(PageText(0, glyphs))
        assertEquals(1, find(page, "nota1 dopo").single().rects.size)
    }

    @Test
    fun `text running down the page groups by line too`() {
        // A page turned by 90°: the baseline runs down (+y) and "up" points right (+x).
        fun column(text: String, x: Float, newWord: Boolean): List<TextGlyph> = text.mapIndexed { i, char ->
            val y = 10f + i * 5f
            TextGlyph(char.toString(), Offset(x, y), Offset(x, y + 5f), Offset(8f, 0f), Offset(-2f, 0f), newWord && i == 0)
        }
        val page = PageTextIndex.build(PageText(0, column("prima", 100f, false) + column("dopo", 86f, true)))
        val rects = find(page, "prima dopo").single().rects
        assertEquals(2, rects.size)
        assertRect(Rect(98f, 10f, 108f, 35f), rects[0])
        assertRect(Rect(84f, 10f, 94f, 30f), rects[1])
    }

    @Test
    fun `glyphs on the same baseline but going backwards start a new line`() {
        val first = TextGlyph("a", Offset(100f, 50f), Offset(105f, 50f), ascent, descent)
        assertTrue(!first.isFollowedOnLineBy(TextGlyph("b", Offset(10f, 50f), Offset(15f, 50f), ascent, descent)))
        assertTrue(first.isFollowedOnLineBy(TextGlyph("b", Offset(104f, 50f), Offset(109f, 50f), ascent, descent)))
        assertTrue(first.isFollowedOnLineBy(TextGlyph("b", Offset(160f, 50f), Offset(165f, 50f), ascent, descent)))
        assertTrue(!first.isFollowedOnLineBy(TextGlyph("b", Offset(105f, 62f), Offset(110f, 62f), ascent, descent)))
    }

    @Test
    fun `a gap wider than a letter gap separates words, in any direction`() {
        // Line height 10: up to 1.5 pt is a letter gap.
        val first = TextGlyph("a", Offset(100f, 50f), Offset(105f, 50f), ascent, descent)
        assertTrue(first.isFollowedInWordBy(TextGlyph("b", Offset(106f, 50f), Offset(111f, 50f), ascent, descent)))
        assertTrue(first.isFollowedInWordBy(TextGlyph("b", Offset(104.5f, 50f), Offset(109f, 50f), ascent, descent))) // kerning
        assertTrue(!first.isFollowedInWordBy(TextGlyph("b", Offset(107f, 50f), Offset(112f, 50f), ascent, descent)))
        assertTrue(!first.isFollowedInWordBy(TextGlyph("b", Offset(105f, 64f), Offset(110f, 64f), ascent, descent)))

        // The same on a page turned by 270°: text runs up the display, its top faces left.
        val up = Offset(-8f, 0f)
        val down = Offset(2f, 0f)
        val turned = TextGlyph("a", Offset(50f, 100f), Offset(50f, 95f), up, down)
        assertTrue(turned.isFollowedInWordBy(TextGlyph("b", Offset(50f, 94f), Offset(50f, 89f), up, down)))
        assertTrue(!turned.isFollowedInWordBy(TextGlyph("b", Offset(50f, 92f), Offset(50f, 87f), up, down)))
    }
}
