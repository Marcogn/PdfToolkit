package com.marcogn.pdftoolkit.data.ocr

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.data.save.PdfSaver
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import com.marcogn.pdftoolkit.pdf.edit.WriteOptions
import com.marcogn.pdftoolkit.pdf.ocr.OcrPage
import com.marcogn.pdftoolkit.pdf.ocr.OcrProcessor
import com.marcogn.pdftoolkit.pdf.ocr.TextRecognizer
import com.marcogn.pdftoolkit.pdf.ocr.TextRecognizerFactory
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.PdfBoxTextExtractor
import com.marcogn.pdftoolkit.pdf.text.PdfTextExtractor
import com.marcogn.pdftoolkit.pdf.text.TextExtractionException
import com.marcogn.pdftoolkit.pdf.text.TextGlyph
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import java.io.InputStream

/**
 * The copy-recognise-copy flow of plan 10b with a fake extractor and file:// URIs. What reaches the renderer and ML Kit
 * (the pages that do need recognition) is device only; here the outcomes that stop before it, and the failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrJobTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var source: File
    private lateinit var destination: File

    private class FakeExtractor(private val pages: Flow<PageText>) : PdfTextExtractor {
        override fun pages(open: () -> InputStream?, password: String?): Flow<PageText> = pages
    }

    private object NoEditor : PdfEditor {
        override suspend fun applySession(session: EditSession, sources: Map<DocRef, File>, output: File, options: WriteOptions, onProgress: (Float) -> Unit) = Unit
        override suspend fun addTextLayer(source: File, output: File, pages: List<OcrPage>) = Unit
        override suspend fun hasFormFields(open: () -> InputStream?) = false
    }

    private val noRecognizer = TextRecognizerFactory { error("no page should be recognised") as TextRecognizer }

    @Before
    fun setUp() {
        source = folder.newFile("source.pdf").apply { writeText("SOURCE") }
        destination = File(folder.root, "out.pdf").apply { writeText("KEEP ME") }
    }

    private fun job(pages: Flow<PageText>) = OcrJob(context, OcrProcessor(FakeExtractor(pages), noRecognizer, NoEditor))

    private fun request(from: File = source) = OcrRequest(from.toUri().toString(), destination.toUri().toString())

    private fun workFiles() = PdfSaver.workDir(context).listFiles().orEmpty().toList()

    private fun textPage(index: Int) =
        PageText(index, listOf(TextGlyph("a", Offset.Zero, Offset(1f, 0f), Offset(0f, -1f), Offset.Zero)))

    @Test
    fun `a document that has text already is reported and left alone`() = runBlocking {
        val result = job(flowOf(textPage(0), textPage(1))).run(request())

        assertEquals(OcrResult.ALREADY_TEXT, result)
        assertEquals("KEEP ME", destination.readText())
        assertEquals("SOURCE", source.readText())
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `a page whose text could not be read is not recognised`() = runBlocking {
        val result = job(flowOf(PageText(0, emptyList(), failed = true))).run(request())

        assertEquals(OcrResult.ALREADY_TEXT, result)
        assertEquals("KEEP ME", destination.readText())
    }

    @Test
    fun `a missing source is reported as such`() = runBlocking {
        source.delete()
        assertFailure(SaveFailure.SOURCE_UNREADABLE, job(flowOf()))
        assertEquals("KEEP ME", destination.readText())
    }

    @Test
    fun `a document that cannot be read is reported as unreadable`() = runBlocking {
        assertFailure(SaveFailure.SOURCE_UNREADABLE, job(flow { throw TextExtractionException(null) }))
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `a password protected document is reported as protected`() = runBlocking {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            document.save(source)
        }
        val real = OcrJob(context, OcrProcessor(PdfBoxTextExtractor(), noRecognizer, NoEditor))
        assertFailure(SaveFailure.PROTECTED, real)
        assertEquals("KEEP ME", destination.readText())
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `progress starts after the source is copied`() = runBlocking {
        val seen = mutableListOf<Float>()
        job(flowOf(textPage(0))).run(request()) { seen += it }
        assertTrue(seen.isNotEmpty() && seen.all { it in 0f..1f } && seen == seen.sorted())
    }

    private suspend fun assertFailure(expected: SaveFailure, job: OcrJob) {
        try {
            job.run(request())
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(expected, e.failure)
        }
    }
}
