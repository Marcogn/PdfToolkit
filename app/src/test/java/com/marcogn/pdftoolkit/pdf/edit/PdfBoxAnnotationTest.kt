package com.marcogn.pdftoolkit.pdf.edit

import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationGeometry
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.annotations.PdfBoxAnnotationReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.contentstream.operator.Operator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Annotations written by [PdfBoxEditor] and read back by [PdfBoxAnnotationReader] and by PdfBox's
 * own model (spec §7.4, phase 7a): new highlights and ink with their appearance, removal of
 * annotations made elsewhere with their pop-ups and replies, and the safety checks on references.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxAnnotationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val editor by lazy { PdfBoxEditor(FakeImageLoader(), AssetFontSource(ApplicationProvider.getApplicationContext())) }
    private lateinit var source: File
    private lateinit var output: File

    private val rotations = listOf(0, 90, 180, 270)

    /** Upright text on a line from y 200 to 214, x 100..160. */
    private val quad = Quad(UserPoint(100f, 214f), UserPoint(160f, 214f), UserPoint(100f, 200f), UserPoint(160f, 200f))
    private val yellow = AnnotationStyle(AnnotationColor.YELLOW, opacity = 0.5f)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        source = folder.newFile("source.pdf")
        output = File(folder.root, "out.pdf")
    }

    private fun writeSource(build: PDDocument.() -> Unit = {}) {
        PDDocument().use { doc ->
            rotations.forEach { rotation ->
                doc.addPage(
                    PDPage(PDRectangle(300f, 400f)).apply {
                        cropBox = PDRectangle(10f, 20f, 200f, 300f)
                        this.rotation = rotation
                    },
                )
            }
            doc.build()
            doc.save(source)
        }
    }

    private fun apply(session: EditSession) = runBlocking { editor.applySession(session, mapOf(DocRef.MAIN to source), output) }

    private fun read(file: File): DocumentAnnotations = PDDocument.load(file).use { PdfBoxAnnotationReader.read(it, docId = 0) }

    private fun highlight(id: String, pageId: String, kind: MarkupKind = MarkupKind.HIGHLIGHT, vararg quads: Quad = arrayOf(quad)) =
        NewAnnotation(id, pageId, AnnotationShape.TextMarkup(kind, quads.toList()), yellow)

    /** A highlight made by another app on page [page] of the document, with a pop-up and an answer to it. */
    private fun PDDocument.foreignHighlight(page: Int, x: Float): PDAnnotationTextMarkup {
        val target = getPage(page)
        val markup = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
            rectangle = PDRectangle(x, 100f, 50f, 12f)
            quadPoints = floatArrayOf(x, 112f, x + 50f, 112f, x, 100f, x + 50f, 100f)
            color = PDColor(floatArrayOf(0f, 1f, 0f), PDDeviceRGB.INSTANCE)
        }
        val popup = PDAnnotationPopup().apply {
            rectangle = PDRectangle(x, 300f, 100f, 50f)
            cosObject.setItem(COSName.PARENT, markup)
        }
        markup.popup = popup
        val reply = PDAnnotationText().apply {
            rectangle = PDRectangle(x, 120f, 20f, 20f)
            inReplyTo = markup
        }
        val annotations = target.annotations
        annotations += listOf(markup, popup, reply)
        target.annotations = annotations
        return markup
    }

    private fun subtypes(page: PDPage): List<String> = page.annotations.map { it.subtype }

    @Test
    fun `a new highlight is written on its page with quads, colour, opacity and an appearance`() {
        writeSource()
        val session = EditSession.of(4).addAnnotation(highlight("h1", "p1"))
        apply(session)
        PDDocument.load(output).use { doc ->
            val annotation = doc.getPage(1).annotations.single() as PDAnnotationTextMarkup
            assertEquals("Highlight", annotation.subtype)
            assertEquals(quad.toQuadPoints().toList(), annotation.quadPoints.toList())
            listOf(1f, 0.92f, 0.23f).zip(annotation.color.components.toList()).forEach { (e, a) -> assertEquals(e, a, 0.001f) }
            assertEquals(0.5f, annotation.constantOpacity, 0.001f)
            assertTrue(annotation.isPrinted)
            assertEquals("h1", annotation.annotationName)
            val rect = annotation.rectangle
            val bounds = AnnotationGeometry.bounds(AnnotationShape.TextMarkup(MarkupKind.HIGHLIGHT, listOf(quad)))
            assertEquals(bounds.left, rect.lowerLeftX, 0.001f)
            assertEquals(bounds.top, rect.upperRightY, 0.001f)
            // The appearance is a form over /Rect in user space, multiplying with the page.
            val appearance = annotation.appearance.normalAppearance.appearanceStream
            assertEquals(rect.lowerLeftX, appearance.bBox.lowerLeftX, 0.001f)
            assertEquals(rect.upperRightY, appearance.bBox.upperRightY, 0.001f)
            val state = appearance.resources.extGStateNames.single().let { appearance.resources.getExtGState(it) }
            assertEquals(COSName.getPDFName("Multiply"), state.cosObject.getDictionaryObject(COSName.BM))
            assertEquals(0.5f, state.nonStrokingAlphaConstant, 0.001f)
            // One filled quad: its four corners, then fill.
            val tokens = PDFStreamParser(appearance).apply { parse() }.tokens
            val operators = tokens.filterIsInstance<Operator>().map { it.name }
            assertEquals(listOf("m", "l", "l", "l", "h", "f"), operators.filter { it in setOf("m", "l", "h", "f", "S") })
            // Nothing on the other pages.
            listOf(0, 2, 3).forEach { assertTrue(doc.getPage(it).annotations.isEmpty()) }
        }
        // And the app reads it back as it wrote it.
        val readBack = read(output).on(1).single()
        assertEquals(AnnotationShape.TextMarkup(MarkupKind.HIGHLIGHT, listOf(quad)), readBack.shape)
        assertEquals(0.5f, readBack.style.opacity, 0.001f)
    }

    @Test
    fun `underline, strikeout, squiggly and ink are written with their own subtype and strokes`() {
        writeSource()
        val ink = NewAnnotation("i", "p0", AnnotationShape.Ink(listOf(listOf(UserPoint(10f, 20f), UserPoint(40f, 60f), UserPoint(80f, 30f))), 3f), yellow)
        var session = EditSession.of(4)
        listOf(MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT, MarkupKind.SQUIGGLY).forEachIndexed { i, kind -> session = session.addAnnotation(highlight("m$i", "p0", kind)) }
        apply(session.addAnnotation(ink))
        val page = read(output).on(0)
        assertEquals(listOf("Underline", "StrikeOut", "Squiggly", "Ink"), page.map { it.subtype })
        assertEquals(ink.shape, page.last().shape)
        PDDocument.load(output).use { doc ->
            doc.getPage(0).annotations.forEach { annotation ->
                val stream = annotation.appearance.normalAppearance.appearanceStream
                val operators = PDFStreamParser(stream).apply { parse() }.tokens.filterIsInstance<Operator>().map { it.name }
                assertTrue("${annotation.subtype} strokes", "S" in operators)
                assertTrue("${annotation.subtype} doesn't multiply", stream.resources.extGStateNames.all { stream.resources.getExtGState(it).cosObject.getDictionaryObject(COSName.BM) == null })
            }
        }
    }

    @Test
    fun `annotations follow their page when pages move, and pages removed take theirs away`() {
        writeSource()
        val session = EditSession.of(4)
            .addAnnotation(highlight("a", "p2"))
            .addAnnotation(highlight("b", "p3"))
            .moveToStart("p2")
            .remove(setOf("p3"))
        apply(session)
        PDDocument.load(output).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertEquals(listOf("a"), doc.getPage(0).annotations.map { (it as PDAnnotationTextMarkup).annotationName })
            // The page turned by 180° came first, and its annotation with it.
            assertEquals(180, doc.getPage(0).rotation)
            assertTrue((1..2).all { doc.getPage(it).annotations.isEmpty() })
        }
    }

    @Test
    fun `an annotation of another app is removed with its pop-up and replies, the others stay`() {
        writeSource {
            foreignHighlight(page = 2, x = 30f)
            foreignHighlight(page = 2, x = 120f)
            val link = PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 20f, 20f) }
            getPage(2).annotations = getPage(2).annotations + link
        }
        val existing = read(source)
        // The reader lists the two highlights and the two replies, not pop-ups or links.
        assertEquals(listOf("Highlight", "Text", "Highlight", "Text"), existing.on(2).map { it.subtype })
        val first = existing.on(2).first()
        apply(EditSession.of(4).removeExistingAnnotation(first.ref))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(2)
            assertEquals(listOf("Highlight", "Popup", "Text", "Link"), subtypes(page))
            assertEquals(120f, page.annotations.first().rectangle.lowerLeftX, 0.001f)
        }
    }

    @Test
    fun `removal works on a page that was moved, and skips a reference that no longer matches`() {
        writeSource { foreignHighlight(page = 3, x = 30f) }
        val ref = read(source).on(3).first().ref
        apply(EditSession.of(4).moveToStart("p3").removeExistingAnnotation(ref))
        PDDocument.load(output).use { doc ->
            assertEquals(270, doc.getPage(0).rotation)
            assertTrue(doc.getPage(0).annotations.isEmpty())
        }
        // The same reference with a fingerprint that no longer matches the file: nothing is removed.
        apply(EditSession.of(4).removeExistingAnnotation(ref.copy(fingerprint = "Highlight@0.0,0.0,1.0,1.0")))
        PDDocument.load(output).use { doc -> assertEquals(listOf("Highlight", "Popup", "Text"), subtypes(doc.getPage(3))) }
        // An index past the end is ignored too.
        apply(EditSession.of(4).removeExistingAnnotation(AnnotationRef(0, 3, 42, ref.fingerprint)))
        PDDocument.load(output).use { doc -> assertEquals(3, doc.getPage(3).annotations.size) }
    }

    @Test
    fun `an annots array shared by two pages loses the annotation on one page only`() {
        writeSource {
            foreignHighlight(page = 0, x = 30f)
            getPage(1).cosObject.setItem(COSName.ANNOTS, getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS))
        }
        val ref = read(source).on(1).first().ref
        apply(EditSession.of(4).removeExistingAnnotation(ref))
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Highlight", "Popup", "Text"), subtypes(doc.getPage(0)))
            assertTrue(doc.getPage(1).annotations.isEmpty())
        }
    }

    @Test
    fun `a new annotation on a page sharing its annots array stays on that page`() {
        writeSource {
            foreignHighlight(page = 0, x = 30f)
            getPage(1).cosObject.setItem(COSName.ANNOTS, getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS))
        }
        apply(EditSession.of(4).addAnnotation(highlight("h", "p1")))
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Highlight", "Popup", "Text"), subtypes(doc.getPage(0)))
            assertEquals(listOf("Highlight", "Popup", "Text", "Highlight"), subtypes(doc.getPage(1)))
        }
    }

    @Test
    fun `annotations go on blank pages added to the session`() {
        writeSource()
        val session = EditSession.of(4).insert(0, listOf(PageItem.Blank("b", 200f, 300f))).addAnnotation(highlight("h", "b"))
        apply(session)
        PDDocument.load(output).use { doc -> assertEquals(1, doc.getPage(0).annotations.size) }
    }

    @Test
    fun `the reader keeps what it can't draw and skips what isn't shown`() {
        writeSource {
            val page = getPage(0)
            val hidden = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                rectangle = PDRectangle(10f, 10f, 10f, 10f)
                color = PDColor(floatArrayOf(1f, 0f, 0f), PDDeviceRGB.INSTANCE)
                isHidden = true
            }
            // No /QuadPoints: the whole /Rect is marked. Gray colour.
            val noQuads = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE).apply {
                rectangle = PDRectangle(40f, 50f, 30f, 10f)
                color = PDColor(floatArrayOf(0.5f), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray.INSTANCE)
            }
            // No colour: transparent, listed but not drawn.
            val noColour = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT).apply { rectangle = PDRectangle(40f, 80f, 30f, 10f) }
            // A note: listed, no shape.
            val note = PDAnnotationText().apply {
                rectangle = PDRectangle(5f, 5f, 20f, 20f)
                color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
            }
            // Ink with its width in /Border only.
            val ink = COSDictionary().apply {
                setName(COSName.TYPE, "Annot")
                setName(COSName.SUBTYPE, "Ink")
                setItem(COSName.RECT, COSArray().apply { listOf(0f, 0f, 50f, 50f).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } })
                setItem(COSName.C, COSArray().apply { listOf(0f, 0f, 1f).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } })
                setItem(COSName.BORDER, COSArray().apply { listOf(0f, 0f, 2.5f).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } })
                setItem(COSName.INKLIST, COSArray().apply { add(COSArray().apply { listOf(1f, 2f, 3f, 4f).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } }) })
            }
            page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { listOf(hidden, noQuads, noColour, note).forEach { add(it) }; add(ink) })
        }
        val annotations = read(source).on(0)
        assertEquals(listOf("Underline", "StrikeOut", "Text", "Ink"), annotations.map { it.subtype })
        // Indices are the positions in /Annots, hidden entries included.
        assertEquals(listOf(1, 2, 3, 4), annotations.map { it.ref.index })
        val underline = annotations[0].shape as AnnotationShape.TextMarkup
        assertEquals(listOf(Quad(UserPoint(40f, 60f), UserPoint(70f, 60f), UserPoint(40f, 50f), UserPoint(70f, 50f))), underline.quads)
        assertEquals(AnnotationColor(0.5f, 0.5f, 0.5f), annotations[0].style.color)
        assertNull(annotations[1].shape)
        assertNull(annotations[2].shape)
        val ink = annotations[3].shape as AnnotationShape.Ink
        assertEquals(2.5f, ink.width, 0.001f)
        assertEquals(listOf(listOf(UserPoint(1f, 2f), UserPoint(3f, 4f))), ink.strokes)
        assertNotNull(read(source).pageBoxes.getOrNull(3))
    }

    /** A freehand stroke as the app makes it: the centre line, and an outline in two overlapping parts. */
    private val drawnInk = AnnotationShape.Ink(
        strokes = listOf(listOf(UserPoint(50f, 60f), UserPoint(90f, 80f))),
        width = 3f,
        outlines = listOf(
            listOf(UserPoint(48f, 58f), UserPoint(92f, 78f), UserPoint(91f, 82f), UserPoint(49f, 62f)),
            listOf(UserPoint(88f, 76f), UserPoint(94f, 76f), UserPoint(94f, 84f)),
        ),
    )

    private fun operators(tokens: List<Any>) = tokens.filterIsInstance<Operator>().map { it.name }

    @Test
    fun `a drawn stroke is an Ink annotation whose appearance fills its outline, with the centre line in InkList`() {
        writeSource()
        apply(EditSession.of(4).addAnnotation(NewAnnotation("d", "p1", drawnInk, AnnotationStyle(AnnotationColor.BLACK))))
        PDDocument.load(output).use { doc ->
            val annotation = doc.getPage(1).annotations.single() as PDAnnotationMarkup
            assertEquals("Ink", annotation.subtype)
            assertEquals(listOf(50f, 60f, 90f, 80f), annotation.inkList.single().toList())
            assertEquals(3f, annotation.borderStyle.width, 0.001f)
            val bounds = AnnotationGeometry.bounds(drawnInk)
            assertEquals(bounds.left, annotation.rectangle.lowerLeftX, 0.001f)
            assertEquals(bounds.bottom, annotation.rectangle.lowerLeftY, 0.001f)
            assertEquals(bounds.right, annotation.rectangle.upperRightX, 0.001f)
            assertEquals(bounds.top, annotation.rectangle.upperRightY, 0.001f)
            // Both outlines in one path, filled once with the nonzero rule; no stroke.
            val appearance = annotation.appearance.normalAppearance.appearanceStream
            assertEquals(annotation.rectangle.lowerLeftX, appearance.bBox.lowerLeftX, 0.001f)
            val ops = operators(PDFStreamParser(appearance).apply { parse() }.tokens).filter { it in setOf("m", "l", "h", "f", "f*", "S") }
            assertEquals(listOf("m", "l", "l", "l", "h", "m", "l", "l", "h", "f"), ops)
            assertTrue(appearance.resources.extGStateNames.all { appearance.resources.getExtGState(it).cosObject.getDictionaryObject(COSName.BM) == null })
        }
        // The file keeps the centre line; the outline is only in the appearance.
        val readBack = read(output).on(1).single().shape as AnnotationShape.Ink
        assertEquals(drawnInk.strokes, readBack.strokes)
        assertEquals(3f, readBack.width, 0.001f)
        assertTrue(readBack.outlines.isEmpty())
        assertFalse(readBack.highlighter)
    }

    @Test
    fun `a freehand highlighter multiplies with the page and reads back as a highlighter`() {
        writeSource()
        val marker = NewAnnotation("h", "p0", drawnInk.copy(highlighter = true), AnnotationStyle(AnnotationColor.YELLOW))
        apply(EditSession.of(4).addAnnotation(marker))
        PDDocument.load(output).use { doc ->
            val appearance = doc.getPage(0).annotations.single().appearance.normalAppearance.appearanceStream
            val state = appearance.resources.extGStateNames.single().let { appearance.resources.getExtGState(it) }
            assertEquals(COSName.getPDFName("Multiply"), state.cosObject.getDictionaryObject(COSName.BM))
        }
        assertTrue((read(output).on(0).single().shape as AnnotationShape.Ink).highlighter)
    }

    @Test
    fun `make final draws new strokes into the page content and leaves highlights as annotations`() {
        writeSource()
        val pen = NewAnnotation("pen", "p2", drawnInk, AnnotationStyle(AnnotationColor.DARK_BLUE))
        val marker = NewAnnotation("marker", "p2", drawnInk.copy(highlighter = true), AnnotationStyle(AnnotationColor.YELLOW))
        val session = EditSession.of(4).addAnnotation(pen).addAnnotation(marker).addAnnotation(highlight("text", "p2"))
        runBlocking { editor.applySession(session, mapOf(DocRef.MAIN to source), output, WriteOptions(flattenInk = true)) }
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(2)
            assertEquals(listOf("Highlight"), subtypes(page))
            val tokens = PDFStreamParser(page).apply { parse() }.tokens
            val ops = operators(tokens)
            assertEquals("one fill per stroke", 2, ops.count { it == "f" })
            assertEquals(ops.count { it == "q" }, ops.count { it == "Q" })
            // The first point of the outline, in user space as it is: the page content has no other transform.
            val firstMove = tokens.indexOfFirst { it is Operator && it.name == "m" }
            val (x, y) = tokens.subList(firstMove - 2, firstMove).map { (it as COSNumber).floatValue() }
            assertEquals(48f, x, 0.001f)
            assertEquals(58f, y, 0.001f)
            // The highlighter still multiplies, now through the page's own resources.
            val modes = page.resources.extGStateNames.map { page.resources.getExtGState(it).cosObject.getDictionaryObject(COSName.BM) }
            assertTrue(modes.toString(), COSName.getPDFName("Multiply") in modes)
        }
        // Without the option the strokes stay annotations.
        apply(session)
        PDDocument.load(output).use { doc -> assertEquals(listOf("Ink", "Ink", "Highlight"), subtypes(doc.getPage(2))) }
    }
}
