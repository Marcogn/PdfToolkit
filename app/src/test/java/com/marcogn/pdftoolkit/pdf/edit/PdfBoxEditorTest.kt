package com.marcogn.pdftoolkit.pdf.edit

import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real PdfBox on generated documents: each page gets a distinct width (100 + 10 * n), so the order
 * of the output can be read back from the page sizes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxEditorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val editor = PdfBoxEditor()
    private lateinit var source: File
    private lateinit var output: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        source = folder.newFile("source.pdf")
        output = File(folder.root, "out.pdf")
        document(pageCount = 5).use { it.save(source) }
    }

    @After
    fun tearDown() {
        source.delete()
        output.delete()
    }

    private fun document(pageCount: Int, rotations: Map<Int, Int> = emptyMap()): PDDocument {
        val document = PDDocument()
        repeat(pageCount) { index ->
            val page = PDPage(PDRectangle(100f + 10f * index, 200f))
            rotations[index]?.let { page.rotation = it }
            document.addPage(page)
        }
        return document
    }

    private fun apply(session: EditSession) = runBlocking { editor.applySession(session, mapOf(DocRef.MAIN to source), output) }

    private fun widths(file: File = output): List<Int> = PDDocument.load(file).use { doc ->
        doc.pages.map { it.mediaBox.width.toInt() }
    }

    private fun rotations(file: File = output): List<Int> = PDDocument.load(file).use { doc -> doc.pages.map { it.rotation } }

    @Test
    fun `an untouched session rewrites every page`() {
        apply(EditSession.of(5))
        assertEquals(listOf(100, 110, 120, 130, 140), widths())
    }

    @Test
    fun `removed pages are gone and the rest keeps its order`() {
        apply(EditSession.of(5).remove(setOf("p1", "p3")))
        assertEquals(listOf(100, 120, 140), widths())
    }

    @Test
    fun `reordering writes the pages in the session order`() {
        apply(EditSession.of(5).move(4, 0).moveToEnd("p1"))
        assertEquals(listOf(140, 100, 120, 130, 110), widths())
    }

    @Test
    fun `rotation is added to the one the page already has`() {
        document(pageCount = 3, rotations = mapOf(1 to 90)).use { it.save(source) }
        apply(EditSession.of(3).rotate(setOf("p0", "p1"), 90).rotate(setOf("p1"), 270))
        assertEquals(listOf(90, 90, 0), rotations())
        apply(EditSession.of(3).rotate(setOf("p1"), -90))
        assertEquals(listOf(0, 0, 0), rotations())
    }

    @Test
    fun `everything together`() {
        val session = EditSession.of(5).remove(setOf("p0")).move(0, 2).rotate(setOf("p4"), 90)
        apply(session)
        assertEquals(listOf(120, 130, 110, 140), widths())
        assertEquals(listOf(0, 0, 0, 90), rotations())
    }

    @Test
    fun `pages inherit their attributes from the page tree and keep them after being detached`() {
        // /MediaBox and /Rotate set on the /Pages node instead of on each page.
        document(pageCount = 3).use { doc ->
            val root = doc.documentCatalog.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.PAGES) as com.tom_roush.pdfbox.cos.COSDictionary
            doc.pages.forEach { page ->
                page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.MEDIA_BOX)
            }
            root.setItem(com.tom_roush.pdfbox.cos.COSName.MEDIA_BOX, PDRectangle(300f, 400f))
            root.setInt(com.tom_roush.pdfbox.cos.COSName.ROTATE, 90)
            doc.save(source)
        }
        apply(EditSession.of(3).move(0, 2))
        assertEquals(listOf(300, 300, 300), widths())
        assertEquals(listOf(90, 90, 90), rotations())
    }

    @Test
    fun `a page that doesn't exist is a typed failure`() {
        val session = EditSession.decode("p0,0,9,0", 10)!!
        try {
            apply(session)
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
    }

    @Test
    fun `a file that isn't a PDF is a typed failure`() {
        source.writeText("not a pdf")
        try {
            apply(EditSession.of(1))
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
        assertFalse(output.exists() && output.length() > 0 && widths().isNotEmpty())
    }

    @Test
    fun `progress goes from 0 to 1`() {
        val seen = mutableListOf<Float>()
        runBlocking { editor.applySession(EditSession.of(5), mapOf(DocRef.MAIN to source), output) { seen += it } }
        assertEquals(0f, seen.first(), 0f)
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }
}
