package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page used everywhere: crop box x 10..210, y 20..320 in user space (200 x 300 points), so
 * the box doesn't start at the origin and width and height differ.
 */
class PdfPageSpaceTest {

    private fun space(rotation: Int) = PdfPageSpace(left = 10f, bottom = 20f, width = 200f, height = 300f, rotation = rotation)

    private fun assertPoint(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertEquals("x of $actual", expected.x, actual.x, tolerance)
        assertEquals("y of $actual", expected.y, actual.y, tolerance)
    }

    // User-space corners of the box.
    private val bottomLeft = Offset(10f, 20f)
    private val bottomRight = Offset(210f, 20f)
    private val topLeft = Offset(10f, 320f)
    private val topRight = Offset(210f, 320f)

    @Test
    fun noRotationPutsTheTopLeftCornerAtTheOrigin() {
        val s = space(0)
        assertEquals(PageSize(200f, 300f), s.displaySize)
        assertPoint(Offset(0f, 0f), s.userToDisplay.map(topLeft))
        assertPoint(Offset(200f, 300f), s.userToDisplay.map(bottomRight))
        assertPoint(Offset(50f, 100f), s.userToDisplay.map(Offset(60f, 220f)))
    }

    @Test
    fun quarterTurnShowsTheBottomLeftCornerTopLeft() {
        // Turning the page 90° clockwise brings its bottom-left corner to the top-left of the screen.
        val s = space(90)
        assertEquals(PageSize(300f, 200f), s.displaySize)
        assertPoint(Offset(0f, 0f), s.userToDisplay.map(bottomLeft))
        assertPoint(Offset(300f, 0f), s.userToDisplay.map(topLeft))
        assertPoint(Offset(0f, 200f), s.userToDisplay.map(bottomRight))
        assertPoint(Offset(300f, 200f), s.userToDisplay.map(topRight))
    }

    @Test
    fun halfTurnShowsTheBottomRightCornerTopLeft() {
        val s = space(180)
        assertEquals(PageSize(200f, 300f), s.displaySize)
        assertPoint(Offset(0f, 0f), s.userToDisplay.map(bottomRight))
        assertPoint(Offset(200f, 300f), s.userToDisplay.map(topLeft))
    }

    @Test
    fun threeQuarterTurnShowsTheTopRightCornerTopLeft() {
        val s = space(270)
        assertEquals(PageSize(300f, 200f), s.displaySize)
        assertPoint(Offset(0f, 0f), s.userToDisplay.map(topRight))
        assertPoint(Offset(300f, 200f), s.userToDisplay.map(bottomLeft))
        assertPoint(Offset(0f, 200f), s.userToDisplay.map(topLeft))
    }

    /**
     * pdfium's `CPDF_Page::UpdateDimensions` gives, per rotation, a matrix from user space to a
     * y-up display space; flipping its y over the display height must give our matrix.
     */
    @Test
    fun matchesPdfiumPageMatrixForEveryRotation() {
        val left = 10f
        val bottom = 20f
        val right = 210f
        val top = 320f
        val pdfium = mapOf(
            0 to Affine(1f, 0f, 0f, 1f, -left, -bottom),
            90 to Affine(0f, -1f, 1f, 0f, -bottom, right),
            180 to Affine(-1f, 0f, 0f, -1f, right, top),
            270 to Affine(0f, 1f, -1f, 0f, top, -left),
        )
        val samples = listOf(bottomLeft, topRight, Offset(57f, 133f), Offset(200f, 40f))
        for ((rotation, matrix) in pdfium) {
            val s = space(rotation)
            val flip = Affine(1f, 0f, 0f, -1f, 0f, s.displaySize.height)
            for (p in samples) assertPoint((flip * matrix).map(p), s.userToDisplay.map(p))
        }
    }

    @Test
    fun displayToUserIsTheInverse() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val s = space(rotation)
            val p = Offset(123f, 45f)
            assertPoint(p, s.userToDisplay.map(s.displayToUser.map(p)))
        }
    }

    @Test
    fun uprightOnTheDisplayMeansTurnedByThePageRotationInUserSpace() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val s = space(rotation)
            val userAngle = s.userAngle(0f)
            assertEquals(rotation.toFloat(), userAngle, 0f)
            // The text direction, turned by that angle in user space, points right on the display.
            val direction = s.userToDisplay.mapVector(Affine.rotate(userAngle).mapVector(Offset(1f, 0f)))
            assertPoint(Offset(1f, 0f), direction)
            assertEquals(0f, s.displayAngle(userAngle), 0f)
        }
    }

    @Test
    fun addedRotationWrapsAround() {
        assertEquals(0, space(270).withAddedRotation(90).rotation)
        assertEquals(270, space(0).withAddedRotation(-90).rotation)
        assertEquals(PageSize(300f, 200f), space(0).withAddedRotation(90).displaySize)
    }

    @Test
    fun normalizeRotationReadsWholeQuarterTurnsLikePdfium() {
        assertEquals(0, PdfPageSpace.normalizeRotation(0))
        assertEquals(90, PdfPageSpace.normalizeRotation(90))
        assertEquals(0, PdfPageSpace.normalizeRotation(360))
        assertEquals(90, PdfPageSpace.normalizeRotation(450))
        assertEquals(270, PdfPageSpace.normalizeRotation(-90))
        assertEquals(0, PdfPageSpace.normalizeRotation(45)) // truncated, as pdfium does
        assertEquals(90, PdfPageSpace.normalizeRotation(135))
    }

    @Test
    fun affineInverseAndProduct() {
        val t = Affine.translate(5f, -3f) * Affine.rotate(30f) * Affine.scale(2f, 0.5f)
        val p = Offset(7f, 11f)
        assertPoint(p, t.inverse().map(t.map(p)))
        assertPoint(t.map(p), Affine.translate(5f, -3f).map(Affine.rotate(30f).map(Affine.scale(2f, 0.5f).map(p))))
    }

    @Test
    fun quarterTurnsAreExact() {
        assertEquals(Affine(0f, 1f, -1f, 0f, 0f, 0f), Affine.rotate(90f))
        assertEquals(Affine(-1f, 0f, -0f, -1f, 0f, 0f).map(Offset(1f, 2f)), Affine.rotate(180f).map(Offset(1f, 2f)))
        assertEquals(Affine(0f, -1f, 1f, 0f, 0f, 0f), Affine.rotate(-90f))
    }
}
