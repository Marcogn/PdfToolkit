package com.marcogn.pdftoolkit.pdf.edit

import android.graphics.Path
import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.pdf.forms.PdfBoxFormReader
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Fill and sign written by the real PdfBox and read back with PdfBox's own tools (text
 * positions, the image's transformation matrix, the AcroForm), on pages whose crop box doesn't
 * start at the origin and that are turned in every direction (spec §12, phase 4a).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxFillTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val images = FakeImageLoader()
    private val editor by lazy { PdfBoxEditor(images, AssetFontSource(ApplicationProvider.getApplicationContext())) }
    private lateinit var source: File
    private lateinit var output: File

    /** Media box 0..300 x 0..400, crop box x 10..210, y 20..320: as displayed, 200 x 300 before rotation. */
    private val crop = PDRectangle(10f, 20f, 200f, 300f)
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
                        cropBox = crop
                        this.rotation = rotation
                    },
                )
            }
            doc.save(source)
        }
    }

    private fun apply(session: EditSession, flatten: Boolean = false) = runBlocking {
        editor.applySession(session, mapOf(DocRef.MAIN to source), output, WriteOptions(flattenForm = flatten))
    }

    /** The space of page [index] of the source, as the app reads it. */
    private fun space(index: Int): PdfPageSpace = PDDocument.load(source).use { PdfBoxFormReader.pageBox(it.getPage(index)).toPageSpace() }

    private fun assertNear(expected: Offset, actual: Offset, tolerance: Float = 0.05f, message: String = "") {
        assertEquals("$message x of $actual", expected.x, actual.x, tolerance)
        assertEquals("$message y of $actual", expected.y, actual.y, tolerance)
    }

    /**
     * Where a glyph starts in user space. PdfBox's text extraction gives the text matrix relative
     * to the crop box's lower-left corner (10, 20 here), so that is added back.
     */
    private fun TextPosition.userOrigin() = Offset(textMatrix.translateX + crop.lowerLeftX, textMatrix.translateY + crop.lowerLeftY)

    /** Text read back: per page, every glyph with its position. */
    private fun glyphs(file: File = output): List<List<TextPosition>> = PDDocument.load(file).use { doc ->
        List(doc.numberOfPages) { index ->
            val found = mutableListOf<TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String?, textPositions: MutableList<TextPosition>) {
                    found += textPositions
                }
            }
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            found
        }
    }

    @Test
    fun `text placed upright lands where it was placed on every rotation`() {
        var session = EditSession.of(4)
        val placedAt = Offset(40f, 60f) // display point of the box's top-left corner
        rotations.forEachIndexed { index, _ ->
            val s = space(index)
            val text = "Città $index"
            val fontSize = 10f
            // A box of any width: the text starts at its top-left whatever the size.
            val box = OverlayGeometry.uprightAt(s, placedAt + Offset(50f, 10f), 100f, 20f)
            session = session.addOverlay(TextOverlay("t$index", "p$index", box, text, fontSize))
        }
        apply(session)

        val pages = glyphs()
        rotations.forEachIndexed { index, rotation ->
            val s = space(index)
            val read = pages[index]
            assertEquals("Città $index", read.joinToString("") { it.unicode })
            val first = read.first()
            val (x, baseline) = TextBlock.lineOrigin(0, 10f)
            val expected = placedAt + Offset(x, baseline)
            // Where PdfBox's text matrix puts the first glyph, seen on the display.
            assertNear(expected, s.userToDisplay.map(first.userOrigin()), message = "rotation $rotation")
            // Upright: the last glyph is further right on the display, on the same baseline.
            val lastOnDisplay = s.userToDisplay.map(read.last().userOrigin())
            assertTrue("rotation $rotation", lastOnDisplay.x > expected.x + 20f)
            assertEquals(expected.y, lastOnDisplay.y, 0.05f)
        }
    }

    /**
     * A check that doesn't go through [PdfPageSpace]: PdfBox's text extraction turns positions by
     * the page rotation on its own (`TextPosition.getX/getY`, y = baseline from the top). Its
     * PdfBox 2.0 version measures from an origin that ignores a crop box not at (0, 0), so this
     * page has its box at the origin.
     */
    @Test
    fun `PdfBox's own reading of the rotation agrees on where the text is`() {
        PDDocument().use { doc ->
            rotations.forEach { rotation -> doc.addPage(PDPage(PDRectangle(200f, 300f)).apply { this.rotation = rotation }) }
            doc.save(source)
        }
        var session = EditSession.of(4)
        rotations.forEachIndexed { index, _ ->
            val box = OverlayGeometry.uprightAt(space(index), Offset(90f, 70f), 100f, 20f)
            session = session.addOverlay(TextOverlay("t$index", "p$index", box, "Perché", 10f))
        }
        apply(session)
        val (x, baseline) = TextBlock.lineOrigin(0, 10f)
        val expected = Offset(40f, 60f) + Offset(x, baseline)
        glyphs().forEachIndexed { index, read ->
            assertEquals("Perché", read.joinToString("") { it.unicode })
            assertNear(expected, Offset(read.first().x, read.first().y), tolerance = 0.5f, message = "rotation ${rotations[index]}")
            assertTrue(read.last().x > read.first().x + 20f)
            assertEquals(read.first().y, read.last().y, 0.05f)
        }
    }

    @Test
    fun `overlays turn with their page when the page is rotated after placing them`() {
        val s = space(0)
        val box = OverlayGeometry.uprightAt(s, Offset(100f, 50f), 80f, 20f)
        apply(EditSession.of(4).addOverlay(TextOverlay("t", "p0", box, "abc", 10f)).rotate(setOf("p0"), 90))
        val first = glyphs()[0].first().userOrigin()
        val turned = s.withAddedRotation(90)
        val (x, baseline) = TextBlock.lineOrigin(0, 10f)
        // The box's top-left was at display (60, 40); after a quarter turn the text runs down from
        // near the top-right corner of the now 300 x 200 page.
        val onDisplay = turned.userToDisplay.map(first)
        assertNear(Offset(300f - 40f - baseline, 60f + x), onDisplay)
    }

    @Test
    fun `an image covers exactly its box, right side up, on every rotation`() {
        images.add("sig.png", 300, 100, hasAlpha = true)
        var session = EditSession.of(4)
        rotations.forEachIndexed { index, _ ->
            val box = OverlayGeometry.uprightAt(space(index), Offset(100f, 80f), 60f, 20f)
            session = session.addOverlay(ImageOverlay("i$index", "p$index", box, "sig.png"))
        }
        apply(session)
        PDDocument.load(output).use { doc ->
            rotations.forEachIndexed { index, rotation ->
                val ctm = imageMatrices(doc.getPage(index)).single()
                val s = space(index)
                // Image space: (0, 1) is the top-left of the picture, (1, 0) the bottom-right.
                assertNear(Offset(70f, 70f), s.userToDisplay.map(ctm.map(Offset(0f, 1f))), message = "rotation $rotation")
                assertNear(Offset(130f, 90f), s.userToDisplay.map(ctm.map(Offset(1f, 0f))), message = "rotation $rotation")
            }
        }
        // One image object shared by the four pages, decoded once.
        assertEquals(1, images.loads.size)
    }

    @Test
    fun `a mark is drawn as strokes inside its box`() {
        val box = OverlayGeometry.uprightAt(space(1), Offset(50f, 50f), 14f, 14f)
        apply(EditSession.of(4).addOverlay(MarkOverlay("m", "p1", box, MarkKind.CROSS)))
        PDDocument.load(output).use { doc ->
            val s = space(1)
            val points = strokePoints(doc.getPage(1)).map { s.userToDisplay.map(it) }
            assertEquals(4, points.size)
            points.forEach { p -> assertTrue("$p", p.x in 43f..57f && p.y in 43f..57f) }
        }
    }

    @Test
    fun `overlays of a removed page are not written`() {
        val box = OverlayGeometry.uprightAt(space(0), Offset(100f, 50f), 80f, 20f)
        apply(EditSession.of(4).addOverlay(TextOverlay("t", "p0", box, "gone", 10f)).remove(setOf("p0")))
        assertTrue(glyphs().all { it.isEmpty() })
    }

    @Test
    fun `characters the font lacks are dropped instead of failing the save`() {
        val box = OverlayGeometry.uprightAt(space(0), Offset(100f, 50f), 80f, 20f)
        apply(EditSession.of(4).addOverlay(TextOverlay("t", "p0", box, "ok 😀 è", 10f)))
        assertEquals("ok  è", glyphs()[0].joinToString("") { it.unicode })
    }

    // --- AcroForm ---

    private fun formDocument() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 400f)).apply { rotation = 90 }
            doc.addPage(page)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val resources = PDResources()
            val helv = resources.add(PDType1Font.HELVETICA)
            form.defaultResources = resources
            form.defaultAppearance = "/${helv.name} 0 Tf 0 g"

            val name = PDTextField(form).apply { partialName = "name"; defaultAppearance = "/${helv.name} 11 Tf 0 g" }
            widget(doc, name.widgets.single(), page, PDRectangle(50f, 300f, 150f, 20f))
            val agree = PDCheckBox(form).apply { partialName = "agree" }
            widget(doc, agree.widgets.single(), page, PDRectangle(50f, 250f, 15f, 15f), onStates = listOf("Yes"))
            val color = PDComboBox(form).apply { partialName = "color"; setOptions(listOf("r", "g"), listOf("Red", "Green")) }
            widget(doc, color.widgets.single(), page, PDRectangle(50f, 200f, 100f, 20f))
            val size = PDRadioButton(form).apply { partialName = "size" }
            val small = PDAnnotationWidget()
            val large = PDAnnotationWidget()
            size.widgets = listOf(small, large)
            widget(doc, small, page, PDRectangle(50f, 150f, 15f, 15f), onStates = listOf("S"))
            widget(doc, large, page, PDRectangle(80f, 150f, 15f, 15f), onStates = listOf("L"))
            form.fields = listOf(name, agree, color, size)
            doc.save(source)
        }
    }

    private fun widget(doc: PDDocument, widget: PDAnnotationWidget, page: PDPage, rect: PDRectangle, onStates: List<String> = emptyList()) {
        widget.rectangle = rect
        widget.page = page
        page.annotations.add(widget)
        if (onStates.isNotEmpty()) {
            val normal = com.tom_roush.pdfbox.cos.COSDictionary()
            (onStates + "Off").forEach { normal.setItem(it, PDAppearanceStream(doc).cosObject) }
            widget.appearance = PDAppearanceDictionary().apply {
                cosObject.setItem(COSName.N, normal)
            }
            widget.setAppearanceState(COSName.Off.name)
        }
    }

    @Test
    fun `the reader finds every field with its page, rectangle and value`() {
        formDocument()
        val form = PDDocument.load(source).use { PdfBoxFormReader.read(it, withFields = true) }
        assertEquals(90, form.pageBoxes.single().rotation)
        val byName = form.fields.associateBy { it.name }
        val name = byName["name"] as FormField.Text
        assertEquals(11f, name.fontSize, 0f)
        assertEquals(0, name.widgets.single().pageIndex)
        assertEquals(50f, name.widgets.single().rect.left, 0f)
        assertEquals(320f, name.widgets.single().rect.top, 0f)
        assertEquals(FieldValue.Toggle(false), byName["agree"]!!.value)
        assertEquals(listOf("r", "g"), (byName["color"] as FormField.Choice).options.map { it.value })
        assertEquals(listOf("Red", "Green"), (byName["color"] as FormField.Choice).options.map { it.label })
        val size = byName["size"] as FormField.Radio
        assertEquals(listOf("S", "L"), size.widgetValues)
        assertEquals(FieldValue.Choice(null), size.value)
    }

    @Test
    fun `values are written and read back`() {
        formDocument()
        val session = EditSession.of(1)
            .setField("name", FieldValue.Text("Niccolò Ősz"))
            .setField("agree", FieldValue.Toggle(true))
            .setField("color", FieldValue.Choice("g"))
            .setField("size", FieldValue.Choice("L"))
        apply(session)
        val form = PDDocument.load(output).use { PdfBoxFormReader.read(it, withFields = true) }
        val byName = form.fields.associateBy { it.name }
        // "Ő" isn't in Helvetica's encoding: the field switched to the embedded Noto Sans.
        assertEquals(FieldValue.Text("Niccolò Ősz"), byName["name"]!!.value)
        assertEquals(FieldValue.Toggle(true), byName["agree"]!!.value)
        assertEquals(FieldValue.Choice("g"), byName["color"]!!.value)
        assertEquals(FieldValue.Choice("L"), byName["size"]!!.value)
        PDDocument.load(output).use { doc ->
            val field = doc.documentCatalog.acroForm.getField("name")
            assertNotNull(field.widgets.single().appearance?.normalAppearance)
        }
    }

    @Test
    fun `make final turns the form into page content`() {
        formDocument()
        apply(EditSession.of(1).setField("name", FieldValue.Text("Mario Rossi")), flatten = true)
        PDDocument.load(output).use { doc ->
            assertTrue(doc.documentCatalog.acroForm?.fields.isNullOrEmpty())
            assertTrue(doc.getPage(0).annotations.none { it is PDAnnotationWidget })
        }
        assertEquals("Mario Rossi", glyphs()[0].joinToString("") { it.unicode })
    }

    @Test
    fun `without make final the form stays a form`() {
        formDocument()
        apply(EditSession.of(1).setField("name", FieldValue.Text("Mario")))
        PDDocument.load(output).use { doc -> assertEquals(4, doc.documentCatalog.acroForm.fields.size) }
    }

    @Test
    fun `a page without a crop box falls back to the media box`() {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(5f, 6f, 100f, 200f)).apply { rotation = -90 })
            doc.save(source)
        }
        val box = PDDocument.load(source).use { PdfBoxFormReader.pageBox(it.getPage(0)) }
        assertEquals(5f, box.left, 0f)
        assertEquals(6f, box.bottom, 0f)
        assertEquals(100f, box.width, 0f)
        assertEquals(270, box.rotation)
        assertNull(PDDocument.load(source).use { it.documentCatalog.acroForm })
    }

    /** Transformation matrices of the images drawn on [page], as affine transforms of image space → user space. */
    private fun imageMatrices(page: PDPage): List<com.marcogn.pdftoolkit.pdf.render.Affine> {
        val found = mutableListOf<com.marcogn.pdftoolkit.pdf.render.Affine>()
        object : RecordingEngine(page) {
            override fun drawImage(pdImage: PDImage) {
                val m = graphicsState.currentTransformationMatrix
                found += com.marcogn.pdftoolkit.pdf.render.Affine(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX, m.translateY)
            }
        }.processPage(page)
        return found
    }

    /** Points of the stroked paths on [page], in user space. */
    private fun strokePoints(page: PDPage): List<Offset> {
        val points = mutableListOf<Offset>()
        object : RecordingEngine(page) {
            override fun moveTo(x: Float, y: Float) { points += Offset(x, y) }
            override fun lineTo(x: Float, y: Float) { points += Offset(x, y) }
        }.processPage(page)
        return points
    }

    /** Path callbacks of PDFGraphicsStreamEngine receive device coordinates: here, user space (CTM applied). */
    private abstract class RecordingEngine(page: PDPage) : PDFGraphicsStreamEngine(page) {
        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) = Unit
        override fun drawImage(pdImage: PDImage) = Unit
        override fun clip(windingRule: Path.FillType) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): PointF = PointF()
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: Path.FillType) = Unit
        override fun fillAndStrokePath(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }
}
