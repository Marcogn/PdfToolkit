package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.pdf.render.DocumentLayout
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.Viewport
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

/**
 * Text extracted by the real PdfBox from PDFs written with known positions, then searched: the
 * rectangles must land on the text as displayed, on pages with a crop box away from the origin and
 * turned in every direction (spec §5.1, phase 5a). Expected positions are worked out by hand from
 * the page geometry, not through [com.marcogn.pdftoolkit.pdf.render.PdfPageSpace].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTextExtractorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val extractor = PdfBoxTextExtractor()
    private val font = PDType1Font.HELVETICA
    private val fontSize = 10f

    // Helvetica's /Ascent and /Descent (718 and -207 per 1000) at 10 pt.
    private val ascent = 7.18f
    private val descent = 2.07f

    /** Media box 0..300 x 0..400; crop box x 10..210, y 20..320: 200 x 300 before rotation. */
    private val crop = PDRectangle(10f, 20f, 200f, 300f)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun width(text: String) = font.getStringWidth(text) / 1000f * fontSize

    private fun pdf(build: PDDocument.() -> Unit): File {
        val file = folder.newFile()
        PDDocument().use { doc ->
            doc.build()
            doc.save(file)
        }
        return file
    }

    private fun PDDocument.page(rotation: Int = 0, box: PDRectangle? = null, draw: PDPageContentStream.() -> Unit = {}) {
        val page = PDPage(PDRectangle(300f, 400f)).apply {
            this.rotation = rotation
            if (box != null) cropBox = box
        }
        addPage(page)
        PDPageContentStream(this, page).use { it.draw() }
    }

    private fun PDPageContentStream.text(text: String, matrix: Matrix) {
        beginText()
        setFont(font, fontSize)
        setTextMatrix(matrix)
        showText(text)
        endText()
    }

    private fun indexes(file: File, password: String? = null): List<PageTextIndex> = runBlocking {
        extractor.pages({ file.inputStream() }, password).toList().map(PageTextIndex::build)
    }

    private fun find(index: PageTextIndex, query: String) = index.find(SearchQuery.of(query)!!)

    private fun assertRect(expected: Rect, actual: Rect, message: String = "", tolerance: Float = 0.05f) {
        assertEquals("$message left of $actual", expected.left, actual.left, tolerance)
        assertEquals("$message top of $actual", expected.top, actual.top, tolerance)
        assertEquals("$message right of $actual", expected.right, actual.right, tolerance)
        assertEquals("$message bottom of $actual", expected.bottom, actual.bottom, tolerance)
    }

    @Test
    fun `matches land on the text on pages turned in every direction`() {
        // "Perché no" written left to right in user space at (50, 100): on a turned page it
        // appears turned with the page.
        val file = pdf {
            listOf(0, 90, 180, 270).forEach { rotation ->
                page(rotation, crop) { text("Perché no", Matrix.getTranslateInstance(50f, 100f)) }
            }
        }
        val w = width("Perché")
        val expected = listOf(
            // 0°: display = (x - 10, 320 - y): baseline origin (40, 220), text runs right.
            Rect(40f, 220f - ascent, 40f + w, 220f + descent),
            // 90° clockwise: display = (y - 20, x - 10): origin (80, 40), text runs down, its
            // top faces right.
            Rect(80f - descent, 40f, 80f + ascent, 40f + w),
            // 180°: display = (210 - x, y - 20): origin (160, 80), text runs left, upside down.
            Rect(160f - w, 80f - descent, 160f, 80f + ascent),
            // 270°: display = (320 - y, 210 - x): origin (220, 160), text runs up, top faces left.
            Rect(220f - ascent, 160f - w, 220f + descent, 160f),
        )
        indexes(file).forEachIndexed { page, index ->
            val match = find(index, "perche").single()
            assertEquals(page, match.pageIndex)
            assertRect(expected[page], match.rects.single(), "page $page")
            // Words are split where the text has a space, on every rotation.
            assertEquals("page $page", 1, find(index, "perché no").size)
            assertTrue("page $page", find(index, "per che").isEmpty())
        }
    }

    @Test
    fun `text written upright on a turned page is found upright`() {
        // On a page turned by 90°, text that runs up the user space (+y) reads left to right.
        val file = pdf { page(90, crop) { text("Perché no", Matrix(0f, 1f, -1f, 0f, 150f, 100f)) } }
        val w = width("Perché")
        // display = (y - 20, x - 10): origin (80, 140).
        assertRect(Rect(80f, 140f - ascent, 80f + w, 140f + descent), find(indexes(file).single(), "PERCHE").single().rects.single())
    }

    @Test
    fun `scaled text keeps its box`() {
        // Text matrix scaled by 2: a 10 pt font drawn as 20 pt.
        val file = pdf { page { text("Perché", Matrix(2f, 0f, 0f, 2f, 20f, 300f)) } }
        val w = width("Perché") * 2
        assertRect(Rect(20f, 100f - 2 * ascent, 20f + w, 100f + 2 * descent), find(indexes(file).single(), "perche").single().rects.single())
    }

    @Test
    fun `a phrase broken over two lines is found, one rectangle per line`() {
        val file = pdf {
            page {
                beginText()
                setFont(font, fontSize)
                newLineAtOffset(30f, 300f)
                showText("la frase continua")
                newLineAtOffset(0f, -14f)
                showText("sulla riga dopo")
                endText()
            }
        }
        val match = find(indexes(file).single(), "continua sulla").single()
        assertEquals(2, match.rects.size)
        val (first, second) = match.rects
        val firstStart = 30f + width("la frase ")
        assertRect(Rect(firstStart, 100f - ascent, firstStart + width("continua"), 100f + descent), first, "first line")
        assertRect(Rect(30f, 114f - ascent, 30f + width("sulla"), 114f + descent), second, "second line")
    }

    @Test
    fun `pages come one at a time and collecting can stop early`() {
        val file = pdf { repeat(5) { n -> page { text("Pagina $n", Matrix.getTranslateInstance(20f, 300f)) } } }
        val firstTwo = runBlocking { extractor.pages({ file.inputStream() }).take(2).toList() }
        assertEquals(listOf(0, 1), firstTwo.map { it.pageIndex })
        assertEquals(1, find(PageTextIndex.build(firstTwo[1]), "pagina 1").size)
    }

    @Test
    fun `a page with only drawings has no searchable text`() {
        val file = pdf {
            page {
                addRect(20f, 20f, 100f, 100f)
                fill()
            }
            page { text("testo", Matrix.getTranslateInstance(20f, 300f)) }
        }
        val pages = indexes(file)
        assertTrue(pages[0].isEmpty)
        assertTrue(!pages[1].isEmpty)
    }

    @Test
    fun `a protected PDF is read with its password and refused without`() {
        val file = pdf {
            page { text("segreto", Matrix.getTranslateInstance(20f, 300f)) }
            protect(StandardProtectionPolicy("owner", "utente", AccessPermission()).apply { encryptionKeyLength = 128 })
        }
        assertEquals(1, find(indexes(file, "utente").single(), "segreto").size)
        try {
            indexes(file)
            fail("Expected a TextExtractionException")
        } catch (e: TextExtractionException) {
            // expected
        }
    }

    @Test
    fun `a file that isn't a PDF fails the flow`() {
        val file = folder.newFile().apply { writeText("not a pdf") }
        try {
            indexes(file)
            fail("Expected a TextExtractionException")
        } catch (e: TextExtractionException) {
            // expected
        }
        try {
            runBlocking { extractor.pages({ null }).toList() }
            fail("Expected a TextExtractionException")
        } catch (e: TextExtractionException) {
            // expected
        }
    }

    @Test
    fun `on a turned page a match goes to the screen through the coordinate mapper`() {
        val file = pdf {
            page(0, crop)
            page(90, crop) { text("Perché no", Matrix.getTranslateInstance(50f, 100f)) }
        }
        val match = find(indexes(file)[1], "perche").single()
        // As displayed both pages are 200 x 300 and 300 x 200 points; the widest (300 pt) fills a
        // 600 px viewport: 2 px per point, page 1 starts at y = 10 + 600 + 10 = 620 px.
        val layout = DocumentLayout.continuous(listOf(PageSize(200f, 300f), PageSize(300f, 200f)), viewportWidth = 600f, gap = 10f)
        val mapper = PageCoordinateMapper(layout, Viewport(zoom = 1.5f, offset = Offset(100f, 800f)))
        val onScreen = mapper.pageRectToScreen(match.pageIndex, match.rects.single())
        val w = width("Perché")
        // Page points (80 - descent .. 80 + ascent, 40 .. 40 + w) → layout (x * 2, 620 + y * 2)
        // → screen (layout * 1.5 - offset).
        fun screenX(points: Float) = points * 2f * 1.5f - 100f
        fun screenY(points: Float) = (620f + points * 2f) * 1.5f - 800f
        assertRect(Rect(screenX(80f - descent), screenY(40f), screenX(80f + ascent), screenY(40f + w)), onScreen, tolerance = 0.2f)
    }
}
