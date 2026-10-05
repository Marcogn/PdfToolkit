package com.marcogn.pdftoolkit.pdf.annotations

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.TextGlyph
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From a selection to the annotation that gets written (spec §7.4, phase 7b): the runs are in the
 * display points of the source page, the quads in its user space, on pages of any `/Rotate`.
 */
class MarkupFactoryTest {

    /** Two lines of 5 glyphs, 6 wide, ascent 8, descent 2, on the display: line 1 at y 40, line 2 at y 54. */
    private fun twoLines(): PageText {
        val glyphs = mutableListOf<TextGlyph>()
        for ((line, y) in listOf(40f, 54f).withIndex()) {
            for (i in 0 until 5) {
                val origin = Offset(20f + 6f * i, y)
                glyphs += TextGlyph("a", origin, origin + Offset(6f, 0f), Offset(0f, -8f), Offset(0f, 2f), spaceBefore = line == 1 && i == 0)
            }
        }
        return PageText(0, glyphs)
    }

    private fun build(space: PdfPageSpace, from: Int = 2, to: Int = 8): AnnotationShape.TextMarkup {
        val selection = TextSelection(twoLines())
        val range = selection.between(from, to)!!
        val annotation = MarkupFactory.build("a1", "p3", MarkupKind.UNDERLINE, AnnotationColor.RED, selection.runs(range), space)!!
        assertEquals("a1", annotation.id)
        assertEquals("p3", annotation.pageId)
        assertEquals(AnnotationColor.RED, annotation.style.color)
        return annotation.shape as AnnotationShape.TextMarkup
    }

    @Test
    fun `a selection over two lines gives one quad per line`() {
        val markup = build(PdfPageSpace.ofSize(200f, 300f))
        assertEquals(MarkupKind.UNDERLINE, markup.kind)
        assertEquals(2, markup.quads.size)
    }

    @Test
    fun `on an upright page the quads are the runs flipped to y up`() {
        // Page 200 x 300: user y = 300 - display y. First run: glyphs 2..4 of line 1, display x 32..50, y 32..42.
        val quad = build(PdfPageSpace.ofSize(200f, 300f)).quads[0]
        assertEquals(32f, quad.upperLeft.x, 0.001f)
        assertEquals(268f, quad.upperLeft.y, 0.001f)
        assertEquals(50f, quad.upperRight.x, 0.001f)
        assertEquals(268f, quad.upperRight.y, 0.001f)
        assertEquals(258f, quad.lowerLeft.y, 0.001f)
        assertEquals(258f, quad.lowerRight.y, 0.001f)
    }

    @Test
    fun `on a page turned by 90 the quads keep upper towards the top of the letters`() {
        // /Rotate 90 on a 200 x 300 page: display (x, y) is user (y, x). Text runs towards +y of the
        // user space and its top points to -x.
        val quad = build(PdfPageSpace(0f, 0f, 200f, 300f, rotation = 90)).quads[0]
        assertTrue("upper is at smaller x", quad.upperLeft.x < quad.lowerLeft.x)
        assertTrue("text runs towards +y", quad.upperRight.y > quad.upperLeft.y)
        assertEquals(8f + 2f, quad.lowerLeft.x - quad.upperLeft.x, 0.001f)
        assertEquals(18f, quad.upperRight.y - quad.upperLeft.y, 0.001f)
    }

    @Test
    fun `on a page turned by 180 the quad is upside down in user space but upper stays at the top of the letters`() {
        val quad = build(PdfPageSpace.ofSize(200f, 300f).withAddedRotation(180)).quads[0]
        // Text runs towards -x of the user space, with its top towards -y.
        assertTrue(quad.upperRight.x < quad.upperLeft.x)
        assertTrue(quad.upperLeft.y < quad.lowerLeft.y)
    }

    @Test
    fun `no runs gives no annotation`() {
        assertNull(MarkupFactory.build("a", "p", MarkupKind.HIGHLIGHT, AnnotationColor.YELLOW, emptyList(), PdfPageSpace.ofSize(100f, 100f)))
    }
}
