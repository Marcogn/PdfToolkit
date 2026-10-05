package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.text.GlyphRange
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.TextGlyph
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection a page shows and its handles move (spec §7.4, phase 7b). */
class TextSelectionStateTest {

    /** "ciao mondo" as ten 6-wide glyphs from x 10 on one line, the word break before the 5th glyph (the space is not a glyph). */
    private val text = TextSelection(
        PageText(
            0,
            "ciaomondo".mapIndexed { i, c ->
                val x = 10f + 6f * i + if (i >= 4) 6f else 0f
                TextGlyph(c.toString(), Offset(x, 20f), Offset(x + 6f, 20f), Offset(0f, -8f), Offset(0f, 2f), spaceBefore = i == 4)
            },
        ),
    )

    private fun selected(range: GlyphRange) = TextSelectionState().also { it.select("3", text, range) }

    @Test
    fun `selecting stores the page, the range and its text`() {
        val state = selected(GlyphRange(4, 9))
        assertTrue(state.isActive)
        assertEquals("3", state.key)
        assertEquals("mondo", state.clipboardText)
        assertEquals(1, state.runs.size)
    }

    @Test
    fun `clearing leaves nothing selected`() {
        val state = selected(GlyphRange(4, 9))
        state.clear()
        assertFalse(state.isActive)
        assertEquals("", state.clipboardText)
        assertTrue(state.runs.isEmpty())
    }

    @Test
    fun `dragging the end handle stretches the selection to the glyph boundary under it`() {
        val state = selected(GlyphRange(0, 4))
        // The point in the middle of the line, at the right edge of the 9th glyph (x 10 + 9*6 + 6 = 70).
        state.drag(SelectionHandle.END, Offset(70f, 17f))
        assertEquals(GlyphRange(0, 9), state.range)
        assertEquals("ciao mondo", state.clipboardText)
    }

    @Test
    fun `dragging the start handle shrinks the selection from the left`() {
        val state = selected(GlyphRange(0, 9))
        state.drag(SelectionHandle.START, Offset(52f, 17f))
        assertEquals(GlyphRange(6, 9), state.range)
    }

    @Test
    fun `the handles never cross`() {
        val state = selected(GlyphRange(2, 4))
        state.drag(SelectionHandle.START, Offset(500f, 17f))
        assertEquals(GlyphRange(3, 4), state.range)
        state.drag(SelectionHandle.END, Offset(-500f, 17f))
        assertEquals(GlyphRange(3, 4), state.range)
    }

    @Test
    fun `a restored selection has its range but no text until the page is read again`() {
        val state = TextSelectionState("3", GlyphRange(4, 9))
        assertTrue(state.isActive)
        assertNull(state.text)
        assertTrue(state.runs.isEmpty())
        state.attach(text)
        assertEquals("mondo", state.clipboardText)
    }

    @Test
    fun `a restored selection whose page can't be read is dropped`() {
        val state = TextSelectionState("3", GlyphRange(4, 9))
        state.attach(null)
        assertFalse(state.isActive)
    }
}
