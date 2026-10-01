package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlannerTest {

    private val viewportSize = Size(600f, 800f)
    private val noLimit = Long.MAX_VALUE

    /** Pages of 600 x 800 pt on a 600 px wide viewport, no gaps: 1 px per point. */
    private fun layout(pages: Int) = DocumentLayout.continuous(List(pages) { PageSize(600f, 800f) }, 600f, gap = 0f)

    @Test
    fun pageKeyIsFitWidth() {
        val key = RenderPlanner(layout(1), noLimit).pageKey(0)
        assertEquals(PageKey(0, 600, 800, 1f), key)
        assertEquals(600L * 800 * 4, key.byteCount)
    }

    @Test
    fun pageKeyAboveTheLimitIsScaledDown() {
        val key = RenderPlanner(layout(1), maxPageBitmapBytes = 600L * 800 * 4 / 4).pageKey(0)
        assertEquals(PageKey(0, 300, 400, 0.5f), key)
    }

    @Test
    fun tileLevelsAreQuarterOctavesRoundedUp() {
        val planner = RenderPlanner(layout(1), noLimit)
        assertEquals(0, planner.tileLevelFor(1f))
        assertEquals(4, planner.tileLevelFor(2f))
        assertEquals(2, planner.tileLevelFor(1.19f)) // just above 2^(1/4) ≈ 1.1892
        assertEquals(6, planner.tileLevelFor(2.5f))
        assertEquals(-4, planner.tileLevelFor(0.5f))
        for (zoom in listOf(1.01f, 1.7f, 2.5f, 3.3f, 5f)) {
            assertTrue("level scale below zoom $zoom", planner.levelScale(planner.tileLevelFor(zoom)) >= zoom * 0.9999f)
        }
    }

    @Test
    fun noTilesAtFitWidthUnlessThePageBitmapWasScaledDown() {
        assertFalse(RenderPlanner(layout(1), noLimit).needsTiles(0, 0))
        assertTrue(RenderPlanner(layout(1), noLimit).needsTiles(0, 4))
        assertTrue(RenderPlanner(layout(1), maxPageBitmapBytes = 600L * 800).needsTiles(0, 0))
    }

    @Test
    fun visibleTilesCoverOnlyTheVisibleArea() {
        val planner = RenderPlanner(layout(1), noLimit)
        // Zoom 2, level 4 (scale 2): the page is 1200 x 1600 px, a 3 x 4 grid of 512 px tiles.
        val topLeft = planner.visibleTiles(0, 4, Viewport(2f, Offset.Zero), viewportSize)
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), topLeft.map { it.col to it.row }.toSet())
        assertTrue(topLeft.all { it.width == 512 && it.height == 512 && it.scale == 2f })

        val bottomRight = planner.visibleTiles(0, 4, Viewport(2f, Offset(600f, 800f)), viewportSize)
        assertEquals(
            setOf(1 to 1, 2 to 1, 1 to 2, 2 to 2, 1 to 3, 2 to 3),
            bottomRight.map { it.col to it.row }.toSet(),
        )
        val corner = bottomRight.single { it.col == 2 && it.row == 3 }
        assertEquals(1200 - 1024, corner.width)
        assertEquals(1600 - 1536, corner.height)
    }

    @Test
    fun tileRectInPointsMatchesItsPixels() {
        val planner = RenderPlanner(layout(1), noLimit)
        val tile = planner.visibleTiles(0, 4, Viewport(2f, Offset.Zero), viewportSize).single { it.col == 1 && it.row == 1 }
        assertEquals(Rect(256f, 256f, 512f, 512f), planner.tileRectInPoints(tile))
        assertEquals(512, tile.left)
        assertEquals(512, tile.top)
    }

    @Test
    fun planPutsVisiblePagesFirstThenTilesThenPrefetch() {
        val planner = RenderPlanner(layout(10), noLimit)
        // Band 3700..4500: pages 4 (3200..4000) and 5 (4000..4800); 5 is closer to the centre.
        val atZoomOne = planner.plan(Viewport(1f, Offset(0f, 3700f)), viewportSize, tileLevel = 0)
        assertEquals(listOf(5, 4, 6, 3, 7, 2), atZoomOne.map { it.pageIndex })
        assertTrue(atZoomOne.all { it is PageKey })

        // Band 3900..4300 (centre 4100): page 5 is again the closest.
        val zoomed = Viewport(2f, Offset(0f, 7800f))
        val withTiles = planner.plan(zoomed, viewportSize, tileLevel = 4)
        val pages = withTiles.filterIsInstance<PageKey>().map { it.pageIndex }
        assertEquals(listOf(5, 4, 6, 3, 7, 2), pages)
        val firstTile = withTiles.indexOfFirst { it is TileKey }
        val lastTile = withTiles.indexOfLast { it is TileKey }
        assertEquals(2, firstTile) // right after the two visible pages
        assertTrue(withTiles.subList(firstTile, lastTile + 1).all { it is TileKey })
        assertEquals(6, withTiles.size - (lastTile - firstTile + 1))
    }

    @Test
    fun planWithoutTileLevelHasNoTilesAndPrefetchStopsAtTheEnds() {
        val planner = RenderPlanner(layout(3), noLimit)
        val plan = planner.plan(Viewport(2f, Offset.Zero), viewportSize, tileLevel = null)
        assertEquals(listOf(0, 1, 2), plan.map { it.pageIndex })
        assertTrue(plan.all { it is PageKey })
    }
}
