package com.marcogn.pdftoolkit.pdf.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Moving the ends of a selection (spec §7.4, phase 7b): the handles never cross or leave the text. */
class GlyphRangeTest {

    private val range = GlyphRange(4, 9)

    @Test
    fun `the start handle moves freely before the end`() {
        assertEquals(GlyphRange(1, 9), range.withStart(1))
        assertEquals(GlyphRange(8, 9), range.withStart(8))
    }

    @Test
    fun `the start handle stops one glyph before the end and at the first glyph`() {
        assertEquals(GlyphRange(8, 9), range.withStart(12))
        assertEquals(GlyphRange(0, 9), range.withStart(-3))
    }

    @Test
    fun `the end handle moves freely after the start`() {
        assertEquals(GlyphRange(4, 15), range.withEnd(15, glyphCount = 20))
        assertEquals(GlyphRange(4, 5), range.withEnd(5, glyphCount = 20))
    }

    @Test
    fun `the end handle stops one glyph after the start and at the last glyph`() {
        assertEquals(GlyphRange(4, 5), range.withEnd(2, glyphCount = 20))
        assertEquals(GlyphRange(4, 20), range.withEnd(99, glyphCount = 20))
    }
}
