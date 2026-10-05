package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.text.LineRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which handle of a selection a finger is on (spec §7.4, phase 7b). */
class SelectionHandlesTest {

    /** Two lines, 6 high above the baseline and 2 below; the first from x 10 to 50 at y 20, the second from 10 to 30 at y 34. */
    private val runs = listOf(
        LineRun(Offset(10f, 20f), Offset(50f, 20f), Offset(0f, -8f), Offset(0f, 2f)),
        LineRun(Offset(10f, 34f), Offset(30f, 34f), Offset(0f, -8f), Offset(0f, 2f)),
    )
    private val metrics = HandleMetrics(radius = 10f, slop = 4f)

    /** Page points on the screen at 2 px per point, shifted by 5 px. */
    private val toScreen = Affine(2f, 0f, 0f, 2f, 5f, 5f)

    @Test
    fun `the start handle hangs from the first line's lower-left corner, the end handle from the last line's lower-right`() {
        assertEquals(Offset(10f, 22f), SelectionHandles.hangPoint(runs, SelectionHandle.START))
        assertEquals(Offset(30f, 36f), SelectionHandles.hangPoint(runs, SelectionHandle.END))
    }

    @Test
    fun `a handle is dragged by the middle of the line at its end`() {
        assertEquals(Offset(10f, 17f), SelectionHandles.anchorPoint(runs, SelectionHandle.START))
        assertEquals(Offset(30f, 31f), SelectionHandles.anchorPoint(runs, SelectionHandle.END))
    }

    @Test
    fun `a touch on the handle disc grabs it and remembers the way to the text`() {
        // START hangs from page (10, 22) = screen (25, 49); its disc is 10 px below, at (25, 59).
        val grab = SelectionHandles.grabAt(runs, toScreen, Offset(25f, 62f), metrics)!!
        assertEquals(SelectionHandle.START, grab.handle)
        // The anchor is page (10, 17) = screen (25, 39): 23 px above the touch.
        assertEquals(Offset(0f, -23f), grab.toAnchor)
    }

    @Test
    fun `a touch away from both handles grabs nothing`() {
        assertNull(SelectionHandles.grabAt(runs, toScreen, Offset(150f, 150f), metrics))
        assertNull(SelectionHandles.grabAt(runs, toScreen, Offset(25f, 100f), metrics))
    }

    @Test
    fun `the nearer handle wins when the two overlap`() {
        val tiny = listOf(LineRun(Offset(10f, 20f), Offset(12f, 20f), Offset(0f, -8f), Offset(0f, 2f)))
        // START hangs from screen (25, 49), END from (29, 49): a touch at x 28 is nearer to END.
        assertEquals(SelectionHandle.END, SelectionHandles.grabAt(tiny, toScreen, Offset(28f, 59f), metrics)!!.handle)
        assertEquals(SelectionHandle.START, SelectionHandles.grabAt(tiny, toScreen, Offset(26f, 59f), metrics)!!.handle)
    }

    @Test
    fun `without a selection there is nothing to grab`() {
        assertNull(SelectionHandles.grabAt(emptyList(), toScreen, Offset(25f, 59f), metrics))
    }
}
