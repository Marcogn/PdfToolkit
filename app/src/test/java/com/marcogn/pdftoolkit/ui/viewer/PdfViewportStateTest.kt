package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.marcogn.pdftoolkit.pdf.render.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PdfViewportStateTest {

    private val pages = List(20) { PageSize(600f, 800f) }

    @Test
    fun startsAtTheTopAtFitWidth() {
        val state = PdfViewportState()
        state.setContent(pages, Size(1080f, 2000f), gapPx = 20f)
        assertEquals(1f, state.viewport.zoom, 0f)
        assertEquals(Offset.Zero, state.viewport.offset)
    }

    @Test
    fun rotationKeepsPageAndPositionInThePage() {
        val state = PdfViewportState()
        state.setContent(pages, Size(1080f, 2000f), gapPx = 20f)
        state.zoomBy(2f, Offset(540f, 0f))
        state.panBy(Offset(0f, -15_000f))
        val before = state.currentAnchor()!!

        state.setContent(pages, Size(2000f, 1080f), gapPx = 20f)
        val after = state.currentAnchor()!!
        assertEquals(before.pageIndex, after.pageIndex)
        assertEquals(before.pageFractionY, after.pageFractionY, 1e-3f)
        assertEquals(before.contentFractionX, after.contentFractionX, 1e-3f)
        assertEquals(2f, state.viewport.zoom, 0f)
    }

    @Test
    fun savedAnchorIsRestoredOnFirstLayout() {
        val state = PdfViewportState()
        state.setContent(pages, Size(1080f, 2000f), gapPx = 20f)
        state.panBy(Offset(0f, -9_000f))
        val saved = with(PdfViewportState.Saver) { SaverScope { true }.save(state) }
        assertNotNull(saved)

        val restored = PdfViewportState.Saver.restore(saved!!)!!
        restored.setContent(pages, Size(1080f, 2000f), gapPx = 20f)
        assertEquals(state.viewport.offset.y, restored.viewport.offset.y, 0.5f)
        assertEquals(state.currentAnchor()!!.pageIndex, restored.currentAnchor()!!.pageIndex)
    }
}
