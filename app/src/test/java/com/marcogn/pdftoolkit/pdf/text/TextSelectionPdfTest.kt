package com.marcogn.pdftoolkit.pdf.text

import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.pdf.forms.PdfBoxFormReader
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Selection on text extracted by the real PdfBox, turned into `/QuadPoints` (spec §7.4, phase 7a):
 * whatever the page rotation and crop box, the quad must sit on the text in **user space**, where
 * it was written. Expected corners are worked out from the text matrix by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSelectionPdfTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val extractor = PdfBoxTextExtractor()
    private val font = PDType1Font.HELVETICA
    private val fontSize = 10f

    // Helvetica's /Ascent and /Descent (718 and -207 per 1000) at 10 pt.
    private val ascent = 7.18f
    private val descent = 2.07f

    /** Media box 0..300 x 0..400, crop box x 10..210, y 20..320. */
    private val crop = PDRectangle(10f, 20f, 200f, 300f)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun width(text: String) = font.getStringWidth(text) / 1000f * fontSize

    private fun pdf(pages: List<Pair<Int, Matrix>>, text: String): File {
        val file = folder.newFile()
        PDDocument().use { doc ->
            pages.forEach { (rotation, matrix) ->
                val page = PDPage(PDRectangle(300f, 400f)).apply {
                    this.rotation = rotation
                    cropBox = crop
                }
                doc.addPage(page)
                PDPageContentStream(doc, page).use {
                    it.beginText()
                    it.setFont(font, fontSize)
                    it.setTextMatrix(matrix)
                    it.showText(text)
                    it.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun spaces(file: File): List<PdfPageSpace> = PDDocument.load(file).use { doc -> doc.pages.map { PdfBoxFormReader.pageBox(it).toPageSpace() } }

    /** The quads of the word under the middle of glyph [glyph] of each page. */
    private fun wordQuads(file: File, glyph: Int): List<Quad> {
        val spaces = spaces(file)
        return extractor.reader({ file.inputStream() }).use { reader ->
            spaces.indices.map { index ->
                val page = runBlocking { reader.page(index) }
                val g = page.glyphs[glyph]
                val selection = TextSelection(page)
                val word = selection.wordAt((g.origin + g.end) / 2f + (g.ascent + g.descent) / 2f)!!
                selection.runs(word).single().toUser(spaces[index])
            }
        }
    }

    private fun assertQuad(expected: Quad, actual: Quad, message: String) {
        listOf("upperLeft", "upperRight", "lowerLeft", "lowerRight").zip(expected.points.zip(actual.points)).forEach { (name, pair) ->
            val (e, a) = pair
            assertEquals("$message $name x of $actual", e.x, a.x, 0.05f)
            assertEquals("$message $name y of $actual", e.y, a.y, 0.05f)
        }
    }

    @Test
    fun `a selected word lands where it was written in user space, on pages turned in every direction`() {
        // "Perché no" at user (50, 100) on every page: the page rotation changes the display, not
        // the user space, so the quad is the same on all four.
        val file = pdf(listOf(0, 90, 180, 270).map { it to Matrix.getTranslateInstance(50f, 100f) }, "Perché no")
        val w = width("Perché")
        val expected = Quad(
            upperLeft = UserPoint(50f, 100f + ascent),
            upperRight = UserPoint(50f + w, 100f + ascent),
            lowerLeft = UserPoint(50f, 100f - descent),
            lowerRight = UserPoint(50f + w, 100f - descent),
        )
        wordQuads(file, glyph = 2).forEachIndexed { page, quad -> assertQuad(expected, quad, "page $page") }
    }

    @Test
    fun `text written turned in user space gives a turned quad`() {
        // Text running up the user space (+y): its top points to -x. On a page turned by 90° it
        // reads upright; on an unturned one it runs up the screen.
        val matrix = Matrix(0f, 1f, -1f, 0f, 150f, 100f)
        val file = pdf(listOf(0 to matrix, 90 to matrix), "Perché no")
        val w = width("Perché")
        val expected = Quad(
            upperLeft = UserPoint(150f - ascent, 100f),
            upperRight = UserPoint(150f - ascent, 100f + w),
            lowerLeft = UserPoint(150f + descent, 100f),
            lowerRight = UserPoint(150f + descent, 100f + w),
        )
        wordQuads(file, glyph = 2).forEachIndexed { page, quad -> assertQuad(expected, quad, "page $page") }
    }

    @Test
    fun `the page reader serves pages in any order and an empty page past the end`() {
        val file = pdf(listOf(0 to Matrix.getTranslateInstance(20f, 300f), 0 to Matrix.getTranslateInstance(20f, 300f)), "testo")
        extractor.reader({ file.inputStream() }).use { reader ->
            runBlocking {
                assertEquals("testo", TextSelection(reader.page(1)).let { it.text(it.all()!!) })
                assertEquals(0, reader.page(0).pageIndex)
                assertEquals(0, reader.page(5).glyphs.size)
            }
        }
    }
}
