package com.marcogn.pdftoolkit.pdf.annotations

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.render.DocumentLayout
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freehand strokes in the viewer's multi-page viewport (plan V-a): which page a stroke belongs to,
 * and that every point lands where it was drawn at any zoom and pan, also on turned pages.
 */
class DocumentStrokesTest {

    // Widest page 600 pt on a 1200 px viewport: 2 px per point, gap 10 px.
    //   page 0: 600 x 800 pt  → x 0..1200,  y 10..1610
    //   page 1: 300 x 400 pt  → x 300..900, y 1620..2420 (narrower, centred)
    //   page 2: 600 x 450 pt as displayed → x 0..1200, y 2430..3330 (its user space varies by test)
    private val pageSizes = listOf(PageSize(600f, 800f), PageSize(300f, 400f), PageSize(600f, 450f))
    private val layout = DocumentLayout.continuous(pageSizes, viewportWidth = 1200f, gap = 10f)

    /** What the ink library does with a pointer position: the inverse of document → screen, set at the stroke start. */
    private fun drawnAt(viewport: Viewport, screen: List<Offset>): FreehandStroke {
        val toStroke = DocumentStrokes.documentToScreen(layout, viewport).inverse()
        return FreehandStroke(screen.map(toStroke::map), outlines = listOf(screen.map(toStroke::map)), width = 2f, epsilon = 0.01f)
    }

    private fun assertNear(expected: Offset, actual: Offset, tolerance: Float = 0.01f, message: String = "") {
        assertEquals("$message x", expected.x, actual.x, tolerance)
        assertEquals("$message y", expected.y, actual.y, tolerance)
    }

    private val viewports = listOf(
        Viewport(),
        Viewport(zoom = 2.5f, offset = Offset(700f, 3900f)),
        Viewport(zoom = 0.6f, offset = Offset(-120f, 400f)),
        Viewport(zoom = 5f, offset = Offset(2000f, 9000f)),
    )

    @Test
    fun `document points map to the screen like the layout does`() {
        for (viewport in viewports) {
            val mapper = PageCoordinateMapper(layout, viewport)
            val transform = DocumentStrokes.documentToScreen(layout, viewport)
            for (page in pageSizes.indices) {
                val point = Offset(37f, 91f)
                // A page point is its page's corner (in document points) plus the point.
                val document = DocumentStrokes.documentToPage(layout, page).inverse().map(point)
                assertNear(mapper.pageToScreen(page, point), transform.map(document), message = "zoom ${viewport.zoom} page $page")
            }
        }
    }

    @Test
    fun `a stroke belongs to the page under its first point`() {
        val viewport = Viewport(zoom = 2f, offset = Offset(600f, 3000f))
        val mapper = PageCoordinateMapper(layout, viewport)
        // Starts on page 1 and runs down onto page 2: page 1 owns it.
        val start = mapper.pageToScreen(1, Offset(150f, 390f))
        val end = mapper.pageToScreen(2, Offset(150f, 40f))
        val (page, onPage) = DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(start, end)))!!
        assertEquals(1, page)
        assertNear(Offset(150f, 390f), onPage.centre.first())
        // The part on the next page is still in page 1's points, below its bottom edge (clipped when drawn).
        assertTrue(onPage.centre.last().y > 400f)
    }

    @Test
    fun `a stroke started in the gap between two pages belongs to the page above`() {
        val viewport = Viewport()
        // The gap between page 0 (ends at y 1610 px) and page 1 (starts at 1620 px).
        val (page, _) = DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(Offset(600f, 1615f), Offset(600f, 1700f))))!!
        assertEquals(0, page)
        // Above the first page, and below the last one: the nearest page.
        assertEquals(0, DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(Offset(600f, 2f))))!!.first)
        assertEquals(2, DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(Offset(600f, 5000f))))!!.first)
    }

    @Test
    fun `beside a narrow page a stroke still belongs to that page`() {
        val viewport = Viewport()
        // Page 1 spans x 300..900 px: x 100 is on the background to its left.
        val (page, onPage) = DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(Offset(100f, 1700f))))!!
        assertEquals(1, page)
        assertTrue(onPage.centre.single().x < 0f)
    }

    @Test
    fun `a stroke without points belongs to no page`() {
        assertNull(DocumentStrokes.onPage(layout, FreehandStroke(emptyList(), emptyList(), 2f, 0.1f)))
    }

    @Test
    fun `strokes land where they were drawn at any zoom, on pages turned by their file`() {
        // Page 2 shown 600 x 450: a 450 x 600 user-space box turned by its /Rotate, at every quarter turn
        // that gives that display size (90 and 270), and an unturned 600 x 450 one (0 and 180).
        val spaces = listOf(
            PdfPageSpace(left = 20f, bottom = 30f, width = 600f, height = 450f, rotation = 0),
            PdfPageSpace(left = 20f, bottom = 30f, width = 450f, height = 600f, rotation = 90),
            PdfPageSpace(left = 20f, bottom = 30f, width = 600f, height = 450f, rotation = 180),
            PdfPageSpace(left = 20f, bottom = 30f, width = 450f, height = 600f, rotation = 270),
        )
        for (viewport in viewports) {
            val mapper = PageCoordinateMapper(layout, viewport)
            val screen = listOf(Offset(40f, 60f), Offset(300f, 200f), Offset(590f, 440f)).map { mapper.pageToScreen(2, it) }
            val (page, onPage) = DocumentStrokes.onPage(layout, drawnAt(viewport, screen))!!
            assertEquals(2, page)
            for (space in spaces) {
                assertEquals(PageSize(600f, 450f), space.displaySize)
                val ink = FreehandGeometry.toInk(onPage, space.displayToUser, highlighter = false)!!
                assertTrue(FreehandGeometry.touchesPage(ink, space))
                val back = ink.strokes.single().map { mapper.userToScreen(2, space, Offset(it.x, it.y)) }
                // Rounded to 0.01 pt in user space: at most 0.01 pt x screen px per point off.
                val tolerance = 0.011f * mapper.screenPxPerPoint
                screen.zip(back).forEach { (drawn, written) ->
                    assertNear(drawn, written, tolerance, "zoom ${viewport.zoom} rotation ${space.rotation}")
                }
            }
        }
    }

    @Test
    fun `brush sizes stay points of the page whatever the zoom`() {
        // Two points 10 screen px apart at zoom 2.5 are 10 / (2 * 2.5) = 2 page points apart.
        val viewport = Viewport(zoom = 2.5f, offset = Offset(0f, 0f))
        val (_, onPage) = DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(Offset(100f, 100f), Offset(110f, 100f))))!!
        assertEquals(2f, onPage.centre[1].x - onPage.centre[0].x, 0.001f)
    }

    @Test
    fun `a stroke drawn only on the background next to a page is not kept`() {
        val viewport = Viewport()
        val space = PdfPageSpace(0f, 0f, 300f, 400f)
        // Left of page 1, all of it.
        val (_, onPage) = DocumentStrokes.onPage(layout, drawnAt(viewport, listOf(Offset(50f, 1700f), Offset(120f, 1800f))))!!
        val ink = FreehandGeometry.toInk(onPage, space.displayToUser, highlighter = false)!!
        assertFalse(FreehandGeometry.touchesPage(ink, space))
    }
}
