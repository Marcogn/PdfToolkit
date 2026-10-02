package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import com.marcogn.pdftoolkit.domain.fill.UserRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    private fun assertPoint(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertEquals("x of $actual", expected.x, actual.x, tolerance)
        assertEquals("y of $actual", expected.y, actual.y, tolerance)
    }

    private val rotations = listOf(0, 90, 180, 270)

    private fun space(rotation: Int) = PdfPageSpace(left = 10f, bottom = 20f, width = 200f, height = 300f, rotation = rotation)

    @Test
    fun overlayPlacedUprightCoversTheDisplayRectangleTapped() {
        // A 40 x 10 box centred at (100, 50) on the display must show as x 80..120, y 45..55
        // whatever the page rotation, with its local top-left corner at the display top-left.
        for (rotation in rotations) {
            val s = space(rotation)
            val box = OverlayGeometry.uprightAt(s, Offset(100f, 50f), 40f, 10f)
            val toDisplay = s.userToDisplay * OverlayGeometry.localToUser(box)
            assertPoint(Offset(80f, 45f), toDisplay.map(Offset(0f, 0f)))
            assertPoint(Offset(120f, 45f), toDisplay.map(Offset(40f, 0f)))
            assertPoint(Offset(80f, 55f), toDisplay.map(Offset(0f, 10f)))
            assertPoint(Offset(120f, 55f), toDisplay.map(Offset(40f, 10f)))
        }
    }

    @Test
    fun overlayTurnsWithItsPageWhenTheUserRotatesIt() {
        // Placed on an unrotated page, then the page is turned 90° clockwise: the box's top-left
        // corner (display 80, 45 on a 200 x 300 page) ends up near the top-right of the 300 x 200
        // display, and the text now runs downwards.
        val placed = space(0)
        val box = OverlayGeometry.uprightAt(placed, Offset(100f, 50f), 40f, 10f)
        val turned = placed.withAddedRotation(90)
        val toDisplay = turned.userToDisplay * OverlayGeometry.localToUser(box)
        assertPoint(Offset(300f - 45f, 80f), toDisplay.map(Offset.Zero))
        assertPoint(Offset(0f, 1f), toDisplay.mapVector(Offset(1f, 0f)))
        assertEquals(90f, turned.displayAngle(box.angle), 0f)
    }

    @Test
    fun containsUsesTheTurnedBox() {
        for (rotation in rotations) {
            val s = space(rotation)
            val box = OverlayGeometry.uprightAt(s, Offset(100f, 50f), 40f, 10f)
            assertTrue(OverlayGeometry.contains(box, s.displayToUser.map(Offset(118f, 54f))))
            assertFalse(OverlayGeometry.contains(box, s.displayToUser.map(Offset(118f, 57f))))
            assertFalse(OverlayGeometry.contains(box, s.displayToUser.map(Offset(78f, 50f))))
        }
    }

    @Test
    fun resizingFromTopLeftKeepsThatCorner() {
        for (rotation in rotations) {
            val s = space(rotation)
            val box = OverlayGeometry.uprightAt(s, Offset(100f, 50f), 40f, 10f)
            val bigger = OverlayGeometry.resizedFromTopLeft(box, 60f, 30f)
            val toDisplay = s.userToDisplay * OverlayGeometry.localToUser(bigger)
            assertPoint(Offset(80f, 45f), toDisplay.map(Offset.Zero))
            assertPoint(Offset(140f, 75f), toDisplay.map(Offset(60f, 30f)))
        }
    }

    @Test
    fun imageUnitSquareFillsTheBoxRightSideUp() {
        // Image space has y up: its top edge (v = 1) must be the top of the box on the display.
        for (rotation in rotations) {
            val s = space(rotation)
            val box = OverlayGeometry.uprightAt(s, Offset(100f, 50f), 40f, 10f)
            val toDisplay = s.userToDisplay * OverlayGeometry.localToUser(box) * OverlayGeometry.imageUnitToLocal(box)
            assertPoint(Offset(80f, 45f), toDisplay.map(Offset(0f, 1f)))
            assertPoint(Offset(120f, 55f), toDisplay.map(Offset(1f, 0f)))
        }
    }

    @Test
    fun textMatrixHasNoMirroringAndRunsAlongTheBox() {
        for (rotation in rotations) {
            val s = space(rotation)
            val box = OverlayGeometry.uprightAt(s, Offset(100f, 50f), 40f, 10f)
            val tm = OverlayGeometry.textMatrix(box, 2f, 8f)
            // Text space is y up: a determinant of +1 means glyphs aren't mirrored.
            assertEquals(1f, tm.a * tm.d - tm.b * tm.c, 1e-4f)
            val toDisplay = s.userToDisplay * tm
            assertPoint(Offset(82f, 53f), toDisplay.map(Offset.Zero)) // baseline start
            assertPoint(Offset(1f, 0f), toDisplay.mapVector(Offset(1f, 0f))) // runs right
            assertPoint(Offset(0f, -1f), toDisplay.mapVector(Offset(0f, 1f))) // ascenders go up
        }
    }

    @Test
    fun mapperPutsUserSpaceOnScreenThroughThePageAndTheViewport() {
        // One 300 x 200 (displayed) page on a 600 px viewport: 2 px per point, gap 10, zoom 2,
        // scrolled by (100, 40).
        for (rotation in listOf(90, 270)) {
            val s = space(rotation)
            val layout = DocumentLayout.continuous(listOf(s.displaySize), viewportWidth = 600f, gap = 10f)
            val mapper = PageCoordinateMapper(layout, Viewport(zoom = 2f, offset = Offset(100f, 40f)))
            val user = s.displayToUser.map(Offset(30f, 20f))
            // page point (30, 20) → layout (60, 50) → screen (120 - 100, 100 - 40)
            assertPoint(Offset(20f, 60f), mapper.userToScreen(0, s, user))
            assertPoint(user, mapper.screenToUser(0, s, Offset(20f, 60f)))
        }
    }

    @Test
    fun widgetRectangleOnARotatedPageStaysAxisAligned() {
        val s = space(90)
        val layout = DocumentLayout.continuous(listOf(s.displaySize), viewportWidth = 300f, gap = 0f)
        val mapper = PageCoordinateMapper(layout, Viewport())
        // User x 10..110, y 20..40 → on a page turned 90°: display x 0..20, y 0..100.
        val rect = mapper.userRectToScreen(0, s, UserRect(10f, 20f, 110f, 40f))
        assertEquals(Rect(0f, 0f, 20f, 100f), rect)
    }

    @Test
    fun overlayToScreenComposesEverything() {
        val s = space(180)
        val layout = DocumentLayout.continuous(listOf(s.displaySize), viewportWidth = 400f, gap = 0f)
        val mapper = PageCoordinateMapper(layout, Viewport(zoom = 1f, offset = Offset(0f, 0f)))
        val box = OverlayBox(110f, 170f, 20f, 10f, s.userAngle(0f))
        val corner = mapper.overlayToScreen(0, s, box).map(Offset.Zero)
        // Centre (110, 170) shows at display (100, 150); the top-left is 10 left and 5 up: ×2 px.
        assertPoint(Offset(180f, 290f), corner)
    }
}
