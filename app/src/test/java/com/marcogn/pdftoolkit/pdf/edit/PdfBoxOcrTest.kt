package com.marcogn.pdftoolkit.pdf.edit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.pdf.ocr.OcrLine
import com.marcogn.pdftoolkit.pdf.ocr.OcrPage
import com.marcogn.pdftoolkit.pdf.ocr.OcrProcessor
import com.marcogn.pdftoolkit.pdf.ocr.OcrQuad
import com.marcogn.pdftoolkit.pdf.ocr.OcrWord
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.PageTextIndex
import com.marcogn.pdftoolkit.pdf.text.PdfBoxTextExtractor
import com.marcogn.pdftoolkit.pdf.text.SearchQuery
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The invisible text layer (spec §7.2, plan 10a) written by the real PdfBox and read back by the
 * app's own extractor and search, on pages whose crop box doesn't start at the origin and that
 * are turned in every direction: the text must be found, where the line was recognised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxOcrTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val editor by lazy { PdfBoxEditor(FakeImageLoader(), AssetFontSource(ApplicationProvider.getApplicationContext())) }
    private lateinit var source: File
    private lateinit var output: File

    private val rotations = listOf(0, 90, 180, 270)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        source = folder.newFile("source.pdf")
        output = File(folder.root, "out.pdf")
        PDDocument().use { doc ->
            rotations.forEach { rotation ->
                doc.addPage(
                    PDPage(PDRectangle(300f, 400f)).apply {
                        cropBox = PDRectangle(10f, 20f, 200f, 300f)
                        this.rotation = rotation
                    },
                )
            }
            doc.save(source)
        }
    }

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) =
        OcrQuad(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom))

    /** A line as ML Kit would give it, in page points: x 20..150, y 50..64, three words. */
    private val line = OcrLine(
        text = "Città di prova",
        box = quad(20f, 50f, 150f, 64f),
        words = listOf(
            OcrWord("Città", quad(20f, 50f, 64f, 64f)),
            OcrWord("di", quad(70f, 50f, 86f, 64f)),
            OcrWord("prova", quad(92f, 50f, 150f, 64f)),
        ),
    )

    private val secondLine = OcrLine(
        text = "Seconda riga",
        box = quad(20f, 80f, 140f, 94f),
        words = listOf(OcrWord("Seconda", quad(20f, 80f, 95f, 94f)), OcrWord("riga", quad(101f, 80f, 140f, 94f))),
    )

    private fun write(): List<PageText> = runBlocking {
        editor.addTextLayer(source, output, rotations.indices.map { OcrPage(it, listOf(line, secondLine)) })
        PdfBoxTextExtractor().pages({ output.inputStream() }).toList()
    }

    private fun PageText.plain() = glyphs.joinToString("") { (if (it.spaceBefore) " " else "") + it.text }

    private fun assertRect(expected: Rect, actual: Rect, tolerance: Float, message: String) {
        assertEquals("$message left of $actual", expected.left, actual.left, tolerance)
        assertEquals("$message top of $actual", expected.top, actual.top, tolerance)
        assertEquals("$message right of $actual", expected.right, actual.right, tolerance)
        assertEquals("$message bottom of $actual", expected.bottom, actual.bottom, tolerance)
    }

    @Test
    fun `text layer is extracted word by word on every rotation`() {
        val pages = write()
        assertEquals(4, pages.size)
        pages.forEach { page ->
            assertEquals("rotation ${rotations[page.pageIndex]}", "Città di prova Seconda riga", page.plain())
        }
    }

    @Test
    fun `search finds a phrase where the line was recognised`() {
        val pages = write()
        pages.forEach { page ->
            val message = "rotation ${rotations[page.pageIndex]}"
            val index = PageTextIndex.build(page)
            val matches = index.find(SearchQuery.of("citta di prova")!!)
            assertEquals(message, 1, matches.size)
            // One box per line, covering the recognised line box (the font's line is as tall as the box).
            assertRect(Rect(20f, 50f, 150f, 64f), matches.single().rects.single(), 0.5f, message)
            val word = index.find(SearchQuery.of("riga")!!).single().rects.single()
            assertRect(Rect(101f, 80f, 140f, 94f), word, 0.5f, message)
        }
    }

    @Test
    fun `text is invisible and pages are not recognised twice`() {
        val pages = write()
        pages.forEach { assertFalse(OcrProcessor.needsRecognition(it)) }
        PDDocument.load(output).use { doc ->
            doc.pages.forEach { page ->
                val tokens = PDFStreamParser(page).apply { parse() }.tokens
                val modes = tokens.indices.filter { (tokens[it] as? Operator)?.name == "Tr" }.map { (tokens[it - 1] as COSNumber).intValue() }
                assertEquals(listOf(3), modes)
            }
        }
    }

    @Test
    fun `words whose boxes touch are still two words`() {
        val tight = OcrLine(
            text = "per favore",
            box = quad(20f, 50f, 100f, 64f),
            words = listOf(OcrWord("per", quad(20f, 50f, 50f, 64f)), OcrWord("favore", quad(49.5f, 50f, 100f, 64f))),
        )
        runBlocking { editor.addTextLayer(source, output, listOf(OcrPage(0, listOf(tight)))) }
        val page = runBlocking { PdfBoxTextExtractor().pages({ output.inputStream() }).toList() }.first()
        assertEquals("per favore", page.plain())
        assertEquals(1, PageTextIndex.build(page).find(SearchQuery.of("per favore")!!).size)
    }

    @Test
    fun `characters the font lacks are dropped, the rest is still written`() {
        val odd = OcrLine("ab", quad(20f, 50f, 60f, 64f), emptyList())
        runBlocking { editor.addTextLayer(source, output, listOf(OcrPage(0, listOf(odd)))) }
        val page = runBlocking { PdfBoxTextExtractor().pages({ output.inputStream() }).toList() }.first()
        assertEquals("ab", page.plain())
        assertTrue(runBlocking { PdfBoxTextExtractor().pages({ output.inputStream() }).toList() }.drop(1).all { OcrProcessor.needsRecognition(it) })
    }
}
