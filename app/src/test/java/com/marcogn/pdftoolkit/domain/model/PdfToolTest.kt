package com.marcogn.pdftoolkit.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PdfToolTest {

    @Test
    fun `Home shows the available tools in spec order`() {
        // Spec §4.1, tool grid; Highlight joined them in 7b, Scan in 9; U-b folded Add, Insert, Remove and Reorder into one.
        assertEquals(
            listOf(
                PdfTool.MERGE,
                PdfTool.ORGANIZE_PAGES,
                PdfTool.FILL_AND_SIGN,
                PdfTool.MY_SIGNATURES,
                PdfTool.SCAN,
                PdfTool.HIGHLIGHT,
                PdfTool.DRAW,
            ),
            PdfTool.available,
        )
    }

    @Test
    fun `Phase 2 tools still to come are listed as coming soon`() {
        // Spec §4.1: Upload to cloud, Export to ODF (Highlight, Draw and Scan are done).
        assertEquals(
            listOf(PdfTool.CLOUD_UPLOAD, PdfTool.EXPORT_ODF),
            PdfTool.upcoming,
        )
    }

    @Test
    fun `scan starts without an open document`() {
        assertFalse(PdfTool.SCAN.requiresDocument)
    }

    @Test
    fun `signature archive does not need an open document`() {
        assertFalse(PdfTool.MY_SIGNATURES.requiresDocument)
    }
}
