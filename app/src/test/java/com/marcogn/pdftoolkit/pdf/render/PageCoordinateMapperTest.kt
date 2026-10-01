package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCoordinateMapperTest {

    // Widest page 600 pt on a 1200 px viewport: 2 px per point. The second page is half as wide,
    // so it is centred. Gap 10 px.
    //   page 0: x 0..1200,   y 10..1610
    //   page 1: x 300..900,  y 1620..2420
    private val layout = DocumentLayout.continuous(
        listOf(PageSize(600f, 800f), PageSize(300f, 400f)),
        viewportWidth = 1200f,
        gap = 10f,
    )

    @Test
    fun layoutFitsWidestPageAndCentresNarrowerOnes() {
        assertEquals(2f, layout.pxPerPoint, 0f)
        assertEquals(Rect(0f, 10f, 1200f, 1610f), layout.pageRects[0])
        assertEquals(Rect(300f, 1620f, 900f, 2420f), layout.pageRects[1])
        assertEquals(1200f, layout.contentSize.width, 0f)
        assertEquals(2430f, layout.contentSize.height, 0f)
    }

    @Test
    fun pagesBetweenFindsIntersectingPagesOnly() {
        assertEquals(0..0, layout.pagesBetween(0f, 500f))
        assertEquals(0..1, layout.pagesBetween(1500f, 1700f))
        assertEquals(1..1, layout.pagesBetween(1700f, 5000f))
        assertTrue(layout.pagesBetween(1610f, 1620f).isEmpty()) // only the gap
        assertTrue(layout.pagesBetween(3000f, 4000f).isEmpty())
    }

    @Test
    fun pageAtReturnsPageAboveInGaps() {
        assertEquals(0, layout.pageAt(-50f))
        assertEquals(0, layout.pageAt(1615f))
        assertEquals(1, layout.pageAt(1620f))
        assertEquals(1, layout.pageAt(99999f))
    }

    @Test
    fun fitPageZoomIsAtMostOne() {
        assertEquals(1f, layout.fitPageZoom(5000f), 0f)
        // Tallest page 1600 px + two 10 px gaps in a 810 px high viewport: half.
        assertEquals(0.5f, layout.fitPageZoom(810f), 1e-6f)
    }

    @Test
    fun pageToScreenAtZoomOne() {
        val mapper = PageCoordinateMapper(layout, Viewport())
        assertEquals(Offset(0f, 10f), mapper.pageToScreen(0, Offset.Zero))
        assertEquals(Offset(200f, 210f), mapper.pageToScreen(0, Offset(100f, 100f)))
        assertEquals(Offset(300f, 1620f), mapper.pageToScreen(1, Offset.Zero))
        assertEquals(2f, mapper.screenPxPerPoint, 0f)
    }

    @Test
    fun pageToScreenWithZoomAndPan() {
        val mapper = PageCoordinateMapper(layout, Viewport(zoom = 2.5f, offset = Offset(400f, 3000f)))
        // Page 1 origin in layout px (300, 1620) → ×2.5 = (750, 4050) → − offset = (350, 1050).
        assertEquals(Offset(350f, 1050f), mapper.pageToScreen(1, Offset.Zero))
        // 10 pt → 10 × 2 px/pt × 2.5 = 50 screen px further.
        assertEquals(Offset(400f, 1100f), mapper.pageToScreen(1, Offset(10f, 10f)))
        assertEquals(5f, mapper.screenPxPerPoint, 0f)
    }

    @Test
    fun screenToPageIsTheInverse() {
        val mapper = PageCoordinateMapper(layout, Viewport(zoom = 3.7f, offset = Offset(123.4f, 5678.9f)))
        val point = Offset(57.25f, 311.5f)
        val back = mapper.screenToPage(1, mapper.pageToScreen(1, point))
        assertEquals(point.x, back.x, 1e-3f)
        assertEquals(point.y, back.y, 1e-3f)
    }

    @Test
    fun pageRectAndBoundsOnScreen() {
        val mapper = PageCoordinateMapper(layout, Viewport(zoom = 2f, offset = Offset(100f, 0f)))
        assertEquals(Rect(-100f, 20f, 2300f, 3220f), mapper.pageBoundsOnScreen(0))
        // 10..20 pt → 20..40 layout px → ×2 = 40..80, plus page top 20 / minus offset 100 in x.
        assertEquals(Rect(-60f, 60f, -20f, 100f), mapper.pageRectToScreen(0, Rect(10f, 10f, 20f, 20f)))
    }

    @Test
    fun hitTestFindsPageAndPoint() {
        val mapper = PageCoordinateMapper(layout, Viewport(zoom = 2f, offset = Offset(0f, 3000f)))
        // Screen (700, 300) → layout (350, 1650): page 1, (50, 30) px from its corner = (25, 15) pt.
        val hit = mapper.hitTest(Offset(700f, 300f))!!
        assertEquals(1, hit.pageIndex)
        assertEquals(25f, hit.point.x, 1e-4f)
        assertEquals(15f, hit.point.y, 1e-4f)
    }

    @Test
    fun hitTestMissesGapsAndMargins() {
        val mapper = PageCoordinateMapper(layout, Viewport())
        assertNull(mapper.hitTest(Offset(600f, 1615f))) // gap between pages
        assertNull(mapper.hitTest(Offset(100f, 2000f))) // left of the narrower page
        assertNull(mapper.hitTest(Offset(600f, 5f))) // gap above the first page
    }
}
