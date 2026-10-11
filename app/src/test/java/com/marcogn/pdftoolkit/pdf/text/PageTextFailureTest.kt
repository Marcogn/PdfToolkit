package com.marcogn.pdftoolkit.pdf.text

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** A page PdfBox can't read is reported as failed, not as empty (issue #31). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageTextFailureTest {

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun extract(failure: Throwable?): PageText = PDDocument().use { document ->
        document.addPage(PDPage())
        val stripper = object : PositionedTextStripper() {
            override fun readText(document: PDDocument) {
                if (failure != null) throw failure
                super.readText(document)
            }
        }
        stripper.extract(document, 0)
    }

    @Test
    fun `an IO failure marks the page failed`() {
        val page = extract(IOException("font"))
        assertTrue(page.failed)
        assertTrue(page.glyphs.isEmpty())
    }

    @Test
    fun `a runtime failure marks the page failed`() {
        assertTrue(extract(IllegalStateException("font")).failed)
    }

    @Test
    fun `a page without text is empty, not failed`() {
        val page = extract(null)
        assertFalse(page.failed)
        assertEquals(0, page.glyphs.size)
    }
}
