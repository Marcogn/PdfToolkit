package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paths, bounds and hit tests of annotations in user space (spec §7.4, phase 7a). */
class AnnotationGeometryTest {

    /** Upright text from x 100 to 160, lines from y 200 (bottom) to 214 (top). */
    private val upright = Quad(UserPoint(100f, 214f), UserPoint(160f, 214f), UserPoint(100f, 200f), UserPoint(160f, 200f))

    /** The same line running up the page (+y), top towards -x: bottom edge at x 314, top at x 300. */
    private val turned = Quad(UserPoint(300f, 100f), UserPoint(300f, 160f), UserPoint(314f, 100f), UserPoint(314f, 160f))

    private fun markup(kind: MarkupKind, vararg quads: Quad) = AnnotationShape.TextMarkup(kind, quads.toList())

    private fun assertPoint(x: Float, y: Float, actual: UserPoint, message: String = "") {
        assertEquals("$message x of $actual", x, actual.x, 0.001f)
        assertEquals("$message y of $actual", y, actual.y, 0.001f)
    }

    @Test
    fun `a highlight fills each quad, corners in drawing order`() {
        val paths = AnnotationGeometry.paths(markup(MarkupKind.HIGHLIGHT, upright, turned))
        assertEquals(2, paths.size)
        val polygon = paths[0].fills.single()
        assertEquals(listOf(upright.upperLeft, upright.upperRight, upright.lowerRight, upright.lowerLeft), polygon)
        assertTrue(paths[0].strokes.isEmpty())
    }

    @Test
    fun `an underline runs along the bottom edge of the text, inside the quad`() {
        val path = AnnotationGeometry.paths(markup(MarkupKind.UNDERLINE, upright)).single()
        val thickness = 14f / 14f
        assertEquals(thickness, path.strokeWidth, 0.001f)
        val (start, end) = path.strokes.single()
        assertPoint(100f, 200f + thickness / 2, start)
        assertPoint(160f, 200f + thickness / 2, end)
    }

    @Test
    fun `underline and strikeout follow the text's own axes when it is turned`() {
        val underline = AnnotationGeometry.paths(markup(MarkupKind.UNDERLINE, turned)).single().strokes.single()
        // The bottom of the letters is the x = 314 side: the line sits just inside it, running up.
        assertPoint(313.5f, 100f, underline[0], "underline start")
        assertPoint(313.5f, 160f, underline[1], "underline end")
        val strike = AnnotationGeometry.paths(markup(MarkupKind.STRIKEOUT, turned)).single().strokes.single()
        assertPoint(307f, 100f, strike[0], "strikeout start")
        assertPoint(307f, 160f, strike[1], "strikeout end")
    }

    @Test
    fun `a squiggly waves along the bottom of the line, end to end`() {
        val wave = AnnotationGeometry.paths(markup(MarkupKind.SQUIGGLY, upright)).single().strokes.single()
        assertPoint(100f, 200.5f, wave.first())
        assertEquals(160f, wave.last().x, 0.001f)
        // 60 long, half waves of 3.5 (a quarter line height): 17 of them.
        assertEquals(18, wave.size)
        assertTrue(wave.all { it.y in 200f..203f })
        assertTrue(wave.zipWithNext().all { (a, b) -> b.x > a.x })
    }

    @Test
    fun `bounds hold every path plus the stroke and some padding`() {
        val ink = AnnotationShape.Ink(listOf(listOf(UserPoint(10f, 20f), UserPoint(30f, 50f))), width = 4f)
        val box = AnnotationGeometry.bounds(ink)
        assertEquals(10f - 2f - AnnotationGeometry.PADDING, box.left, 0.001f)
        assertEquals(20f - 2f - AnnotationGeometry.PADDING, box.bottom, 0.001f)
        assertEquals(30f + 2f + AnnotationGeometry.PADDING, box.right, 0.001f)
        assertEquals(50f + 2f + AnnotationGeometry.PADDING, box.top, 0.001f)
        val highlight = AnnotationGeometry.bounds(markup(MarkupKind.HIGHLIGHT, upright, turned))
        assertEquals(100f - AnnotationGeometry.PADDING, highlight.left, 0.001f)
        assertEquals(314f + AnnotationGeometry.PADDING, highlight.right, 0.001f)
        assertEquals(100f - AnnotationGeometry.PADDING, highlight.bottom, 0.001f)
        assertEquals(214f + AnnotationGeometry.PADDING, highlight.top, 0.001f)
    }

    @Test
    fun `a touch hits a markup inside its quads and an ink near its strokes`() {
        val highlight = markup(MarkupKind.HIGHLIGHT, turned)
        assertTrue(AnnotationGeometry.hits(highlight, UserPoint(307f, 130f), tolerance = 0f))
        assertFalse(AnnotationGeometry.hits(highlight, UserPoint(330f, 130f), tolerance = 5f))
        assertTrue(AnnotationGeometry.hits(highlight, UserPoint(318f, 130f), tolerance = 5f))
        val ink = AnnotationShape.Ink(listOf(listOf(UserPoint(0f, 0f), UserPoint(100f, 0f))), width = 2f)
        assertTrue(AnnotationGeometry.hits(ink, UserPoint(50f, 3f), tolerance = 2f))
        assertFalse(AnnotationGeometry.hits(ink, UserPoint(50f, 4f), tolerance = 2f))
        val dot = AnnotationShape.Ink(listOf(listOf(UserPoint(5f, 5f))), width = 2f)
        assertTrue(AnnotationGeometry.hits(dot, UserPoint(6f, 5f), tolerance = 0f))
    }

    @Test
    fun `quad points keep Acrobat's order both ways`() {
        val values = upright.toQuadPoints()
        assertEquals(listOf(100f, 214f, 160f, 214f, 100f, 200f, 160f, 200f), values.toList())
        assertEquals(listOf(upright, turned), Quad.fromQuadPoints(values + turned.toQuadPoints() + floatArrayOf(1f, 2f)))
    }
}
