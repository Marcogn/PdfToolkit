package com.marcogn.pdftoolkit.pdf.annotations

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * From a stroke drawn on screen (display points of the page as shown) to an Ink shape in user
 * space (spec §7.4, phase 8a), on pages turned by their `/Rotate` and by the user.
 */
class FreehandGeometryTest {

    /** A 300 x 400 media box cropped to x 10..210, y 20..320: the crop corner is not the origin. */
    private fun space(rotation: Int) = PdfPageSpace(left = 10f, bottom = 20f, width = 200f, height = 300f, rotation = rotation)

    private val rotations = listOf(0, 90, 180, 270)

    private fun stroke(centre: List<Offset>, outlines: List<List<Offset>> = emptyList(), width: Float = 2f, epsilon: Float = 0.05f) =
        FreehandStroke(centre, outlines, width, epsilon)

    private fun assertNear(expected: Offset, actual: Offset, tolerance: Float = 0.006f, message: String = "") {
        assertEquals("$message x", expected.x, actual.x, tolerance)
        assertEquals("$message y", expected.y, actual.y, tolerance)
    }

    private fun UserPoint.offset() = Offset(x, y)

    @Test
    fun `the top-left corner on screen is the right corner of the crop box at every page rotation`() {
        // pdfium: /Rotate turns the page clockwise for display.
        val expected = mapOf(0 to UserPoint(10f, 320f), 90 to UserPoint(10f, 20f), 180 to UserPoint(210f, 20f), 270 to UserPoint(210f, 320f))
        for (rotation in rotations) {
            val ink = FreehandGeometry.toInk(stroke(listOf(Offset.Zero)), space(rotation).displayToUser, highlighter = false)!!
            assertEquals("rotation $rotation", expected.getValue(rotation), ink.strokes.single().single())
        }
    }

    @Test
    fun `every point lands where it was drawn, on pages turned by their file and by the user`() {
        // A zig-zag across the page as shown, and a square outline around its start.
        val centre = (0..20).map { Offset(10f + it * 7f, 40f + if (it % 2 == 0) 0f else 15f) }
        val outline = listOf(Offset(5f, 35f), Offset(15f, 35f), Offset(15f, 45f), Offset(5f, 45f))
        for (fileRotation in rotations) {
            for (added in rotations) {
                // The pane shows the page with the user's turn added to the file's.
                val shown = space((fileRotation + added) % 360)
                val ink = FreehandGeometry.toInk(stroke(centre, listOf(outline)), shown.displayToUser, highlighter = false)!!
                val back = ink.strokes.single().map { shown.userToDisplay.map(it.offset()) }
                assertEquals(centre.size, back.size)
                centre.zip(back).forEach { (drawn, written) -> assertNear(drawn, written, message = "file $fileRotation + $added") }
                ink.outlines.single().map { shown.userToDisplay.map(it.offset()) }.zip(outline).forEach { (written, drawn) -> assertNear(drawn, written) }
            }
        }
    }

    @Test
    fun `width and highlighter carry over, coordinates are rounded to hundredths`() {
        val ink = FreehandGeometry.toInk(stroke(listOf(Offset(1.234f, 5.678f)), width = 7.5f), space(0).displayToUser, highlighter = true)!!
        assertEquals(7.5f, ink.width)
        assertTrue(ink.highlighter)
        val point = ink.strokes.single().single()
        assertEquals(11.23f, point.x)
        assertEquals(314.32f, point.y)
    }

    @Test
    fun `points on a straight line are dropped, bends stay within the epsilon`() {
        // 101 points on a line, then a bend at the end.
        val line = (0..100).map { Offset(it.toFloat(), 50f) } + Offset(100f, 80f)
        val ink = FreehandGeometry.toInk(stroke(line, epsilon = 0.05f), space(0).displayToUser, highlighter = false)!!
        val kept = ink.strokes.single().map { space(0).userToDisplay.map(it.offset()) }
        assertEquals(3, kept.size)
        assertNear(Offset(0f, 50f), kept[0])
        assertNear(Offset(100f, 50f), kept[1])
        assertNear(Offset(100f, 80f), kept[2])
    }

    @Test
    fun `a simplified curve never strays from the drawn one by more than the epsilon`() {
        val epsilon = 0.2f
        val circle = (0 until 720).map { i ->
            val angle = Math.toRadians(i / 2.0)
            Offset((100 + 30 * cos(angle)).toFloat(), (150 + 30 * sin(angle)).toFloat())
        }
        val simplified = FreehandGeometry.simplify(circle, epsilon, closed = true)
        assertTrue("${simplified.size} points", simplified.size in 10 until circle.size / 4)
        // Every drawn point is near the simplified polygon.
        val polygon = simplified + simplified.first()
        circle.forEach { p ->
            val distance = polygon.zipWithNext().minOf { (a, b) -> segmentDistance(p, a, b) }
            assertTrue("$p is $distance away", distance <= epsilon + 1e-3f)
        }
    }

    @Test
    fun `outlines too small to fill are dropped, the centre line stays`() {
        val ink = FreehandGeometry.toInk(
            stroke(listOf(Offset(1f, 1f), Offset(2f, 2f)), outlines = listOf(listOf(Offset(1f, 1f), Offset(1f, 1f), Offset(1.001f, 1f)))),
            space(0).displayToUser,
            highlighter = false,
        )!!
        assertTrue(ink.outlines.isEmpty())
        assertEquals(2, ink.strokes.single().size)
        assertNull(FreehandGeometry.toInk(stroke(emptyList()), space(0).displayToUser, highlighter = false))
    }

    @Test
    fun `a closed outline doesn't repeat its first point at the end`() {
        val square = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f), Offset(0f, 0f))
        val ink = FreehandGeometry.toInk(stroke(listOf(Offset(5f, 5f)), outlines = listOf(square)), space(0).displayToUser, highlighter = false)!!
        val outline = ink.outlines.single()
        assertEquals(4, outline.size)
        assertTrue(outline.first() != outline.last())
    }

    @Test
    fun `a stroke drawn only on the background around the page is not on the page`() {
        val page = space(90)
        fun inkAt(display: List<Offset>): AnnotationShape.Ink =
            FreehandGeometry.toInk(stroke(display), page.displayToUser, highlighter = false)!!
        // Shown 300 wide and 200 tall at 90°.
        assertTrue(FreehandGeometry.touchesPage(inkAt(listOf(Offset(150f, 100f))), page))
        assertTrue(FreehandGeometry.touchesPage(inkAt(listOf(Offset(-20f, 100f), Offset(5f, 100f))), page))
        assertFalse(FreehandGeometry.touchesPage(inkAt(listOf(Offset(-20f, 100f), Offset(-10f, 120f))), page))
        assertFalse(FreehandGeometry.touchesPage(inkAt(listOf(Offset(150f, 230f))), page))
    }

    private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
        val d = b - a
        val length = d.getDistanceSquared()
        val t = if (length == 0f) 0f else (((p - a).x * d.x + (p - a).y * d.y) / length).coerceIn(0f, 1f)
        val q = a + d * t
        return (p - q).getDistance().let { abs(it) }
    }
}
