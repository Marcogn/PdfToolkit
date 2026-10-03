package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportBoundsTest {

    // A 1000 x 4000 document in a 1000 x 2000 viewport.
    private val bounds = ViewportBounds(
        contentSize = Size(1000f, 4000f),
        viewportSize = Size(1000f, 2000f),
        minZoom = 0.5f,
        maxZoom = ViewportBounds.MAX_ZOOM,
    )

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals("x", expected.x, actual.x, 1e-3f)
        assertEquals("y", expected.y, actual.y, 1e-3f)
    }

    @Test
    fun offsetRangeCentresContentSmallerThanViewport() {
        // Width at zoom 1 equals the viewport: no horizontal pan.
        assertEquals(0f..0f, bounds.offsetRange(1f, horizontal = true))
        // At zoom 0.5 the width is 500 px: centred with 250 px on each side.
        assertEquals(-250f..-250f, bounds.offsetRange(0.5f, horizontal = true))
        assertEquals(0f..2000f, bounds.offsetRange(1f, horizontal = false))
        assertEquals(0f..18000f, bounds.offsetRange(5f, horizontal = false))
    }

    @Test
    fun clampKeepsZoomAndOffsetInRange() {
        val clamped = bounds.clamp(Viewport(zoom = 9f, offset = Offset(-50f, 99999f)))
        assertEquals(5f, clamped.zoom, 0f)
        assertOffset(Offset(0f, 18000f), clamped.offset)
        assertEquals(0.5f, bounds.clamp(Viewport(zoom = 0.1f)).zoom, 0f)
    }

    @Test
    fun zoomAroundKeepsFocusStill() {
        val start = Viewport(zoom = 1f, offset = Offset(0f, 1000f))
        val focus = Offset(300f, 700f)
        val content = (focus + start.offset) / start.zoom
        val zoomed = bounds.zoomAround(start, focus, 2f)
        assertEquals(2f, zoomed.zoom, 0f)
        assertOffset(focus, content * zoomed.zoom - zoomed.offset)
    }

    @Test
    fun zoomAroundStopsAtMaxZoom() {
        val start = Viewport(zoom = 4f, offset = Offset(1000f, 5000f))
        val zoomed = bounds.zoomAround(start, Offset(500f, 1000f), 10f)
        assertEquals(5f, zoomed.zoom, 0f)
        // Focus still fixed: (500 + 1000) / 4 × 5 − 500 = 1375, (1000 + 5000) / 4 × 5 − 1000 = 6500.
        assertOffset(Offset(1375f, 6500f), zoomed.offset)
    }

    @Test
    fun panMovesContentWithTheFingerAndStopsAtEdges() {
        val start = Viewport(zoom = 2f, offset = Offset(500f, 500f))
        // Finger moves right and down by (100, 200): the content follows, the offset decreases.
        assertOffset(Offset(400f, 300f), bounds.panBy(start, Offset(100f, 200f)).offset)
        assertOffset(Offset(0f, 0f), bounds.panBy(start, Offset(5000f, 5000f)).offset)
        assertOffset(Offset(1000f, 6000f), bounds.panBy(start, Offset(-9000f, -9000f)).offset)
    }

    @Test
    fun doubleTapGoesFromFitWidthToTwoAndAHalfAndBack() {
        val tap = Offset(400f, 600f)
        val zoomedIn = bounds.doubleTapTarget(Viewport(zoom = 1f, offset = Offset(0f, 200f)), tap)
        assertEquals(ViewportBounds.DOUBLE_TAP_ZOOM, zoomedIn.zoom, 0f)
        // The tapped content stays under the finger: (400, 800) layout px × 2.5 − tap.
        assertOffset(Offset(600f, 1400f), zoomedIn.offset)

        assertEquals(1f, bounds.doubleTapTarget(zoomedIn, tap).zoom, 0f)
        assertEquals(1f, bounds.doubleTapTarget(Viewport(zoom = 3.3f), tap).zoom, 0f)
        assertEquals(1f, bounds.doubleTapTarget(Viewport(zoom = 0.6f), tap).zoom, 0f)
    }

    @Test
    fun centerOnPutsTheLayoutPointInTheMiddleAndKeepsTheZoom() {
        // Zoom 1: the point at y = 3000 goes to the middle of a 2000 px viewport, offset 2000, which is the maximum.
        val atFit = bounds.centerOn(Viewport(1f, Offset.Zero), Offset(500f, 3000f))
        assertEquals(1f, atFit.zoom, 0f)
        assertOffset(Offset(0f, 2000f), atFit.offset)

        // Zoom 2: content 2000 x 8000, viewport 1000 x 2000. Point (700, 3000) → screen centre (500, 1000).
        val zoomed = bounds.centerOn(Viewport(2f, Offset.Zero), Offset(700f, 3000f))
        assertOffset(Offset(900f, 5000f), zoomed.offset)
        assertEquals(500f, 700f * zoomed.zoom - zoomed.offset.x, 1e-3f)
        assertEquals(1000f, 3000f * zoomed.zoom - zoomed.offset.y, 1e-3f)
    }

    @Test
    fun centerOnStopsAtTheEdgesOfTheDocument() {
        val top = bounds.centerOn(Viewport(2f, Offset(300f, 4000f)), Offset(10f, 20f))
        assertOffset(Offset(0f, 0f), top.offset)
        val bottom = bounds.centerOn(Viewport(2f, Offset.Zero), Offset(990f, 3990f))
        assertOffset(Offset(1000f, 6000f), bottom.offset)
    }
}
