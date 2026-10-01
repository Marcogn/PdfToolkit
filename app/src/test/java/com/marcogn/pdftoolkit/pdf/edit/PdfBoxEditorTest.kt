package com.marcogn.pdftoolkit.pdf.edit

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.edit.ImageFit
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
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
import java.io.IOException

/**
 * Real PdfBox on generated documents: each page gets a distinct width (100 + 10 * n), so the order
 * of the output can be read back from the page sizes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxEditorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val images = FakeImageLoader()
    private val editor = PdfBoxEditor(images)
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
        val session = EditSession.decode("P,p0,0,9,0", 10)!!
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

    // --- Phase 3: blank pages, pages of other PDFs, images ---

    private fun apply(session: EditSession, vararg extras: Pair<DocRef, File>) =
        runBlocking { editor.applySession(session, mapOf(DocRef.MAIN to source) + extras, output) }

    private fun heights(file: File = output): List<Int> = PDDocument.load(file).use { doc -> doc.pages.map { it.mediaBox.height.toInt() } }

    @Test
    fun `blank pages are written with their size, in place, and rotate`() {
        val session = EditSession.of(2).insert(1, listOf(PageItem.Blank("b1", 595f, 842f), PageItem.Blank("b2", 612f, 792f, rotation = 90)))
        apply(session)
        assertEquals(listOf(100, 595, 612, 110), widths())
        assertEquals(listOf(200, 842, 792, 200), heights())
        assertEquals(listOf(0, 0, 90, 0), rotations())
    }

    @Test
    fun `pages of another PDF keep their size and come in the chosen order`() {
        val other = folder.newFile("other.pdf")
        PDDocument().use { doc ->
            listOf(300f, 310f, 320f).forEach { doc.addPage(PDPage(PDRectangle(it, 400f))) }
            doc.save(other)
        }
        val added = DocRef(1)
        val session = EditSession.of(5).insert(2, listOf(PageItem.FromPdf("n1", added, 2), PageItem.FromPdf("n2", added, 0)))
        apply(session, added to other)
        assertEquals(listOf(100, 110, 320, 300, 120, 130, 140), widths())
        assertEquals(listOf(200, 200, 400, 400, 200, 200, 200), heights())
    }

    @Test
    fun `imported pages keep their content, their own rotation and take the user's on top`() {
        val other = folder.newFile("other-content.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 400f))
            page.rotation = 90
            doc.addPage(page)
            PDPageContentStream(doc, page).use { it.addRect(10f, 10f, 50f, 50f); it.fill() }
            doc.save(other)
        }
        val added = DocRef(1)
        val session = EditSession.of(1).insert(1, listOf(PageItem.FromPdf("n1", added, 0, rotation = 90)))
        apply(session, added to other)
        PDDocument.load(output).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val imported = doc.getPage(1)
            assertEquals(180, imported.rotation)
            val content = imported.contents.readBytes().decodeToString()
            assertTrue("rectangle drawing is kept: $content", content.contains("re"))
        }
    }

    @Test
    fun `a PDF with inherited attributes imports with them`() {
        val other = folder.newFile("inherited.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(300f, 400f)))
            val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES) as COSDictionary
            doc.getPage(0).cosObject.removeItem(COSName.MEDIA_BOX)
            root.setItem(COSName.MEDIA_BOX, PDRectangle(500f, 600f))
            doc.save(other)
        }
        val added = DocRef(1)
        apply(EditSession.of(1).insert(1, listOf(PageItem.FromPdf("n1", added, 0))), added to other)
        assertEquals(listOf(100, 500), widths())
    }

    @Test
    fun `a merge of three PDFs follows the order of the files and of the session`() {
        val second = folder.newFile("second.pdf")
        val third = folder.newFile("third.pdf")
        PDDocument().use { doc -> repeat(2) { doc.addPage(PDPage(PDRectangle(300f + it, 400f))) }; doc.save(second) }
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle(612f, 792f))); doc.save(third) }
        // Main has 5 pages (widths 100..140); the session is merged as main, second, third and then the files are reordered by moving.
        val merged = EditSession.ofDocuments(listOf(5, 2, 1))
        apply(merged, DocRef(1) to second, DocRef(2) to third)
        assertEquals(listOf(100, 110, 120, 130, 140, 300, 301, 612), widths())
        assertEquals(listOf(200, 200, 200, 200, 200, 400, 400, 792), heights())
        // Third file first: the session lists the documents in the order the user arranged them.
        val reordered = EditSession.ofDocuments(listOf(5, 2, 1)).moveToStart("d2p0")
        apply(reordered, DocRef(1) to second, DocRef(2) to third)
        assertEquals(listOf(612, 100, 110, 120, 130, 140, 300, 301), widths())
    }

    @Test
    fun `the same PDF can be added twice and mixed A4 and Letter pages stay as they are`() {
        val mixed = folder.newFile("mixed.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.addPage(PDPage(PDRectangle.LETTER))
            doc.save(mixed)
        }
        val added = DocRef(1)
        val session = EditSession.of(1).insert(1, listOf(PageItem.FromPdf("n1", added, 0), PageItem.FromPdf("n2", added, 1), PageItem.FromPdf("n3", added, 1)))
        apply(session, added to mixed)
        assertEquals(listOf(100, 595, 612, 612), widths())
        assertEquals(listOf(200, 841, 792, 792), heights()) // A4 is 841.89 pt, read back truncated
    }

    @Test
    fun `an added PDF whose file is missing is a typed failure`() {
        val session = EditSession.of(1).insert(1, listOf(PageItem.FromPdf("n1", DocRef(1), 0)))
        try {
            apply(session) // no source for document 1
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
    }

    private fun imagePage(id: String, mode: ImageFit, width: Float, height: Float, rotation: Int = 0) =
        PageItem.FromImage(id, "img:$id", mode, width, height, rotation)

    @Test
    fun `an image becomes a page of the given size with the image drawn centred inside it`() {
        images.add("img:a", widthPx = 400, heightPx = 200)
        apply(EditSession.of(1).insert(1, listOf(imagePage("a", ImageFit.FIT_PAGE, 595f, 842f))))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(1)
            assertEquals(595f, page.mediaBox.width, 0.01f)
            assertEquals(842f, page.mediaBox.height, 0.01f)
            val names = page.resources.xObjectNames.toList()
            assertEquals(1, names.size)
            val content = page.contents.readBytes().decodeToString()
            // 400x200 on 595x842: full width, 297.5 high, vertically centred at (842 - 297.5) / 2 = 272.25.
            assertTrue(content, content.contains("595 0 0 297.5 0 272.25 cm"))
            assertTrue(content, content.contains("/${names.single().name} Do"))
        }
    }

    @Test
    fun `fit mode decodes at most 3000 px on the long side and original mode keeps the pixels`() {
        images.add("img:big", widthPx = 4000, heightPx = 3000)
        images.add("img:big2", widthPx = 4000, heightPx = 3000)
        apply(
            EditSession.of(1).insert(
                1,
                listOf(imagePage("big", ImageFit.FIT_PAGE, 842f, 595f), imagePage("big2", ImageFit.ORIGINAL_SIZE, 1920f, 1440f)),
            ),
        )
        assertEquals(listOf("img:big" to (3000 to 2250), "img:big2" to (4000 to 3000)), images.loads)
        PDDocument.load(output).use { doc ->
            assertEquals(3000, (doc.getPage(1).resources.getXObject(doc.getPage(1).resources.xObjectNames.single()) as PDImageXObject).width)
            assertEquals(4000, (doc.getPage(2).resources.getXObject(doc.getPage(2).resources.xObjectNames.single()) as PDImageXObject).width)
        }
    }

    @Test
    fun `photos are compressed as JPEG and images with transparency stay lossless`() {
        images.add("img:photo", widthPx = 120, heightPx = 80)
        images.add("img:logo", widthPx = 120, heightPx = 80, hasAlpha = true)
        apply(EditSession.of(1).insert(1, listOf(imagePage("photo", ImageFit.FIT_PAGE, 595f, 842f), imagePage("logo", ImageFit.FIT_PAGE, 595f, 842f))))
        PDDocument.load(output).use { doc ->
            fun filtersOf(index: Int): List<String> {
                val resources = doc.getPage(index).resources
                val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
                return image.stream.filters.map { it.name }
            }
            assertEquals(listOf("DCTDecode"), filtersOf(1))
            assertEquals(listOf("FlateDecode"), filtersOf(2))
        }
    }

    @Test
    fun `rotation of an image page is written as the page rotation`() {
        images.add("img:r", widthPx = 100, heightPx = 100)
        apply(EditSession.of(1).insert(1, listOf(imagePage("r", ImageFit.FIT_PAGE, 595f, 842f, rotation = 270))))
        assertEquals(listOf(0, 270), rotations())
    }

    @Test
    fun `an image that can't be read is a typed failure and no bitmap is left alive`() {
        try {
            apply(EditSession.of(1).insert(1, listOf(imagePage("missing", ImageFit.FIT_PAGE, 595f, 842f))))
            fail("expected a failure")
        } catch (e: SaveException) {
            assertEquals(SaveFailure.FAILED, e.failure)
        }
    }

    @Test
    fun `every decoded bitmap is released once its page is built`() {
        images.add("img:a", widthPx = 50, heightPx = 50)
        images.add("img:b", widthPx = 60, heightPx = 40)
        apply(EditSession.of(1).insert(1, listOf(imagePage("a", ImageFit.FIT_PAGE, 595f, 842f), imagePage("b", ImageFit.FIT_PAGE, 595f, 842f))))
        assertTrue(images.decoded.isNotEmpty() && images.decoded.all { it.isRecycled })
    }

    // --- Form fields (merge warning) ---

    @Test
    fun `a PDF with form fields is detected`() {
        val withForm = folder.newFile("form.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "name"
            val widget = field.widgets[0]
            widget.rectangle = PDRectangle(50f, 700f, 150f, 20f)
            widget.page = page
            page.annotations.add(widget)
            form.fields.add(field)
            doc.save(withForm)
        }
        assertTrue(runBlocking { editor.hasFormFields { withForm.inputStream() } })
        assertFalse(runBlocking { editor.hasFormFields { source.inputStream() } })
        assertFalse(runBlocking { editor.hasFormFields { null } })
        assertFalse(runBlocking { editor.hasFormFields { "not a pdf".byteInputStream() } })
    }
}

/** Images as generated bitmaps, so the editor runs without a real decoder. A name nobody added can't be read. */
private class FakeImageLoader : PageImageLoader {
    private class Entry(val widthPx: Int, val heightPx: Int, val hasAlpha: Boolean)

    private val entries = mutableMapOf<String, Entry>()
    val loads = mutableListOf<Pair<String, Pair<Int, Int>>>()
    val decoded = mutableListOf<Bitmap>()

    fun add(uri: String, widthPx: Int, heightPx: Int, hasAlpha: Boolean = false) {
        entries[uri] = Entry(widthPx, heightPx, hasAlpha)
    }

    override fun probe(uri: String): ImageDimensions? = entries[uri]?.let { ImageDimensions(it.widthPx, it.heightPx) }

    override fun load(uri: String, width: Int, height: Int): LoadedImage {
        val entry = entries[uri] ?: throw IOException("No image $uri")
        loads += uri to (width to height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (entry.hasAlpha) 0x80FF0000.toInt() else 0xFF336699.toInt())
            setHasAlpha(entry.hasAlpha)
        }
        decoded += bitmap
        return LoadedImage(bitmap, entry.hasAlpha)
    }

    override fun thumbnail(uri: String, maxSidePx: Int): Bitmap? = null
}
