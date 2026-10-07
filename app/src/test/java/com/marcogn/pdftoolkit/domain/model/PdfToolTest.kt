package com.marcogn.pdftoolkit.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PdfToolTest {

    @Test
    fun `Home shows the available tools in spec order`() {
        // Spec §4.1, tool grid; Highlight joined them in 7b.
        assertEquals(
            listOf(
                PdfTool.MERGE,
                PdfTool.ADD_PAGES,
                PdfTool.INSERT_IMAGES,
                PdfTool.REMOVE_PAGES,
                PdfTool.REORDER_PAGES,
                PdfTool.FILL_AND_SIGN,
                PdfTool.MY_SIGNATURES,
                PdfTool.HIGHLIGHT,
                PdfTool.DRAW,
            ),
            PdfTool.available,
        )
    }

    @Test
    fun `Phase 2 tools still to come are listed as coming soon`() {
        // Spec §4.1: Scan, Upload to cloud, Export to ODF (Highlight and Draw are done).
        assertEquals(
            listOf(PdfTool.SCAN, PdfTool.CLOUD_UPLOAD, PdfTool.EXPORT_ODF),
            PdfTool.upcoming,
        )
    }

    @Test
    fun `signature archive does not need an open document`() {
        assertFalse(PdfTool.MY_SIGNATURES.requiresDocument)
    }
}
