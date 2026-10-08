package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.text.LineRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionBarPlacementTest {

    private val viewport = Size(400f, 800f)
    private val bar = Size(200f, 48f)

    @Test
    fun `sits above the selection, centred on it`() {
        val at = SelectionBarPlacement.place(Rect(100f, 300f, 300f, 330f), bar, viewport, gap = 8f, margin = 8f)!!
        assertEquals(100f, at.x, 0.01f)
        assertEquals(300f - 8f - 48f, at.y, 0.01f)
    }

    @Test
    fun `goes below the last line when there is no room above`() {
        val at = SelectionBarPlacement.place(Rect(100f, 20f, 300f, 50f), bar, viewport, gap = 8f, margin = 8f)!!
        assertEquals(50f + 8f, at.y, 0.01f)
    }

    @Test
    fun `stays inside the viewport sideways`() {
        val left = SelectionBarPlacement.place(Rect(0f, 300f, 40f, 330f), bar, viewport, gap = 8f, margin = 8f)!!
        assertEquals(8f, left.x, 0.01f)
        val right = SelectionBarPlacement.place(Rect(380f, 300f, 400f, 330f), bar, viewport, gap = 8f, margin = 8f)!!
        assertEquals(400f - 200f - 8f, right.x, 0.01f)
    }

    @Test
    fun `a selection scrolled off the screen shows no bar`() {
        assertNull(SelectionBarPlacement.place(Rect(100f, -200f, 300f, -150f), bar, viewport, gap = 8f, margin = 8f))
        assertNull(SelectionBarPlacement.place(Rect(100f, 900f, 300f, 950f), bar, viewport, gap = 8f, margin = 8f))
    }

    @Test
    fun `a partly visible selection keeps its bar on screen`() {
        val at = SelectionBarPlacement.place(Rect(100f, -10f, 300f, 20f), bar, viewport, gap = 8f, margin = 8f)
        assertNotNull(at)
        assertEquals(28f, at!!.y, 0.01f)
    }

    @Test
    fun `bounds cover every run after the page transform`() {
        val runs = listOf(
            LineRun(Offset(10f, 20f), Offset(110f, 20f), Offset(0f, -10f), Offset(0f, 3f)),
            LineRun(Offset(10f, 40f), Offset(60f, 40f), Offset(0f, -10f), Offset(0f, 3f)),
        )
        // Page points → screen: scale 2, moved by (5, 7).
        val bounds = SelectionBarPlacement.screenBounds(runs, Affine(2f, 0f, 0f, 2f, 5f, 7f))!!
        assertEquals(Rect(25f, 7f + 20f, 225f, 7f + 86f), bounds)
    }

    @Test
    fun `no runs, no bounds`() {
        assertNull(SelectionBarPlacement.screenBounds(emptyList(), Affine(1f, 0f, 0f, 1f, 0f, 0f)))
    }
}
