package com.marcogn.pdftoolkit.pdf.ocr

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.TextGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Recognised boxes (page points, as displayed) → text runs in user space, on the page used by the
 * other geometry tests: crop box x 10..210, y 20..320, turned in every direction.
 */
class OcrGeometryTest {

    private val rotations = listOf(0, 90, 180, 270)

    private fun space(rotation: Int) = PdfPageSpace(left = 10f, bottom = 20f, width = 200f, height = 300f, rotation = rotation)

    /** Line extent 1 (0.8 up, 0.2 down), every character half an em wide. */
    private val font = object : OcrFontMetrics {
        override val ascent = 0.8f
        override val descent = -0.2f
        override fun advance(text: String) = 0.5f * text.length
    }

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) =
        OcrQuad(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom))

    private fun assertPoint(expected: Offset, actual: Offset, tolerance: Float = 1e-3f, message: String = "") {
        assertEquals("$message x of $actual", expected.x, actual.x, tolerance)
        assertEquals("$message y of $actual", expected.y, actual.y, tolerance)
    }

    /** An upright line on the display: x 20..120, y 50..62, words "Hello" (20..60) and "world" (70..120). */
    private val line = OcrLine(
        text = "Hello world",
        box = quad(20f, 50f, 120f, 62f),
        words = listOf(OcrWord("Hello", quad(20f, 50f, 60f, 62f)), OcrWord("world", quad(70f, 51f, 120f, 62f))),
    )

    @Test
    fun `line frame follows the displayed line on every rotation`() {
        rotations.forEach { rotation ->
            val s = space(rotation)
            val frame = OcrGeometry.lineFrame(line.box.map(s.displayToUser::map))
            assertNotNull(frame)
            frame!!
            assertPoint(s.displayToUser.map(Offset(20f, 62f)), frame.bottomLeft, message = "rotation $rotation")
            // Along the line = right on the display, up = up on the display (y down there).
            assertPoint(Offset(1f, 0f), s.userToDisplay.mapVector(frame.direction), message = "rotation $rotation")
            assertPoint(Offset(0f, -1f), s.userToDisplay.mapVector(frame.up), message = "rotation $rotation")
            assertEquals(100f, frame.length, 1e-3f)
            assertEquals(12f, frame.height, 1e-3f)
        }
    }

    @Test
    fun `words are placed on their own boxes and the baseline sits above the descent`() {
        rotations.forEach { rotation ->
            val s = space(rotation)
            val runs = OcrGeometry.runs(line, s, font)
            assertEquals(listOf("Hello", " ", "world"), runs.map { it.text })
            // Line extent 1 em: the font size is the box height.
            runs.forEach { assertEquals(12f, it.fontSize, 1e-3f) }
            // Baseline 0.2 em = 2.4 pt above the box's bottom (y 62 on the display).
            val origins = runs.map { s.userToDisplay.map(Offset(it.matrix.e, it.matrix.f)) }
            assertPoint(Offset(20f, 59.6f), origins[0], message = "rotation $rotation")
            assertPoint(Offset(60f, 59.6f), origins[1], message = "rotation $rotation")
            assertPoint(Offset(70f, 59.6f), origins[2], message = "rotation $rotation")
            // Stretched to the box: "Hello" is 5 × 0.5 × 12 = 30 pt wide unscaled, its box 40.
            assertEquals(100f * 40f / 30f, runs[0].horizontalScaling, 1e-2f)
            // The space (6 pt) fills the 10 pt gap; "world" fills 50 pt.
            assertEquals(100f * 10f / 6f, runs[1].horizontalScaling, 1e-2f)
            assertEquals(100f * 50f / 30f, runs[2].horizontalScaling, 1e-2f)
            // The text matrix's x axis reads to the right on the display, its y axis points up.
            runs.forEach { run ->
                val m = run.matrix
                assertPoint(Offset(1f, 0f), s.userToDisplay.mapVector(Offset(m.a, m.b)), message = "rotation $rotation")
                assertPoint(Offset(0f, -1f), s.userToDisplay.mapVector(Offset(m.c, m.d)), message = "rotation $rotation")
            }
        }
    }

    @Test
    fun `a slanted line keeps its angle`() {
        // 20° clockwise on the display (ML Kit's angle +20): along = (cos, sin), up = (sin, -cos) with y down.
        val angle = Math.toRadians(20.0)
        val along = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val up = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
        val bottomLeft = Offset(40f, 100f)
        val box = OcrQuad(
            topLeft = bottomLeft + up * 10f,
            topRight = bottomLeft + up * 10f + along * 80f,
            bottomRight = bottomLeft + along * 80f,
            bottomLeft = bottomLeft,
        )
        rotations.forEach { rotation ->
            val s = space(rotation)
            val runs = OcrGeometry.runs(OcrLine("Slanted", box, emptyList()), s, font)
            assertEquals(1, runs.size)
            val run = runs.single()
            assertEquals(10f, run.fontSize, 1e-3f)
            assertPoint(along, s.userToDisplay.mapVector(Offset(run.matrix.a, run.matrix.b)), message = "rotation $rotation")
            assertPoint(up, s.userToDisplay.mapVector(Offset(run.matrix.c, run.matrix.d)), message = "rotation $rotation")
            assertPoint(bottomLeft + up * 2f, s.userToDisplay.map(Offset(run.matrix.e, run.matrix.f)), 1e-2f, "rotation $rotation")
            assertEquals(100f * 80f / (7 * 0.5f * 10f), run.horizontalScaling, 1e-2f)
        }
    }

    @Test
    fun `overlapping words are pulled apart around their meeting point, with a space between`() {
        // "Hello" ends at 70, "world" starts at 65: they meet at 67.5 and get 0.25 line heights (3 pt) between them.
        val overlapping = line.copy(
            words = listOf(OcrWord("Hello", quad(20f, 50f, 70f, 62f)), OcrWord("world", quad(65f, 50f, 120f, 62f))),
        )
        val s = space(0)
        val runs = OcrGeometry.runs(overlapping, s, font)
        assertEquals(listOf("Hello", " ", "world"), runs.map { it.text })
        val origins = runs.map { s.userToDisplay.map(Offset(it.matrix.e, it.matrix.f)) }
        assertPoint(Offset(66f, 59.6f), origins[1])
        assertPoint(Offset(69f, 59.6f), origins[2])
        // "Hello" now spans 20..66.
        assertEquals(100f * 46f / 30f, runs[0].horizontalScaling, 1e-2f)
    }

    @Test
    fun `a line without words is written as one`() {
        val runs = OcrGeometry.runs(line.copy(words = emptyList()), space(0), font)
        assertEquals(listOf("Hello world"), runs.map { it.text })
    }

    @Test
    fun `blank words and degenerate boxes are skipped`() {
        val blank = line.copy(words = listOf(OcrWord(" ", quad(20f, 50f, 60f, 62f))))
        assertTrue(OcrGeometry.runs(blank, space(0), font).isEmpty())
        assertNull(OcrGeometry.lineFrame(quad(20f, 50f, 20f, 62f)))
        assertNull(OcrGeometry.lineFrame(quad(20f, 62f, 120f, 62f)))
        assertTrue(OcrGeometry.runs(line.copy(box = quad(20f, 62f, 120f, 62f)), space(0), font).isEmpty())
    }

    @Test
    fun `render scale is about 200 dpi, capped on the long side`() {
        assertEquals(200f / 72f, OcrGeometry.renderScale(PageSize(595f, 842f)), 1e-4f)
        assertEquals(0.75f, OcrGeometry.renderScale(PageSize(3000f, 4000f)), 1e-4f)
    }

    @Test
    fun `only pages without any character need recognition`() {
        fun glyph(text: String) = TextGlyph(text, Offset.Zero, Offset(1f, 0f), Offset(0f, -1f), Offset.Zero)
        assertTrue(OcrProcessor.needsRecognition(PageText(0, emptyList())))
        assertTrue(OcrProcessor.needsRecognition(PageText(0, listOf(glyph(" "), glyph("")))))
        assertFalse(OcrProcessor.needsRecognition(PageText(0, listOf(glyph(" "), glyph("1")))))
    }
}
