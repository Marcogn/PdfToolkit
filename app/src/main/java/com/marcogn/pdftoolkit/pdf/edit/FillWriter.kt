package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkShape
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes what "Fill and sign" added (spec §6.5): form values with their appearances, the optional
 * flattening, and the overlays, into the page content stream (never as annotations).
 */
internal class FillWriter(
    private val document: PDDocument,
    private val fonts: FontSource,
    private val images: PageImageLoader,
) {
    /** Embedded on first use, as a subset: only the glyphs written in the content streams. */
    private var overlayFont: PDFont? = null

    /** Fully embedded, for form fields: a viewer may regenerate their appearance with other characters. */
    private var formFont: PDFont? = null

    /** One XObject per image file, so a signature repeated on many pages is stored once. */
    private val imageObjects = HashMap<String, PDImageXObject>()

    /**
     * Sets [values] on the AcroForm and generates their appearances. A field whose font can't
     * show the value (e.g. a standard font without "ő") gets Noto Sans instead. Values for fields
     * that don't exist or are read-only are skipped.
     */
    fun fillForm(values: Map<String, FieldValue>) {
        if (values.isEmpty()) return
        val form = document.documentCatalog.acroForm ?: return
        // Static XFA would make XFA-aware readers ignore the values written here (spec §6.5: XFA unsupported).
        if (form.hasXFA()) form.xfa = null
        // PdfBox builds appearances only when NeedAppearances is off; the viewer's own wish is kept.
        val needAppearances = form.needAppearances
        form.needAppearances = false
        for ((name, value) in values) {
            val field = form.getField(name) ?: continue
            if (field.isReadOnly) continue
            try {
                setValue(field, value)
            } catch (e: IllegalArgumentException) {
                retryWithOwnFont(form, field, value)
            } catch (e: IOException) {
                retryWithOwnFont(form, field, value)
            }
        }
        if (needAppearances) form.needAppearances = true
    }

    private fun setValue(field: PDField, value: FieldValue) {
        when {
            field is PDTextField && value is FieldValue.Text -> field.value = value.value
            field is PDCheckBox && value is FieldValue.Toggle -> if (value.on) field.check() else field.unCheck()
            field is PDButton && value is FieldValue.Choice -> field.value = value.value ?: COSName.Off.name
            field is PDChoice && value is FieldValue.Choice -> if (value.value == null) field.value = emptyList() else field.setValue(value.value)
        }
    }

    private fun retryWithOwnFont(form: PDAcroForm, field: PDField, value: FieldValue) {
        if (field !is PDTextField || value !is FieldValue.Text) return
        val font = formFont ?: fonts.openRegular().use { PDType0Font.load(document, it, false) }.also { formFont = it }
        val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        val fontName = resources.add(font)
        val size = field.defaultAppearance?.let { FONT_SIZE.find(it)?.groupValues?.get(1) } ?: "0"
        field.defaultAppearance = "/${fontName.name} $size Tf 0 g"
        try {
            field.value = value.value
        } catch (e: IllegalArgumentException) {
            // Characters Noto Sans doesn't have either: the field keeps its old value.
        } catch (e: IOException) {
            // Same: better a field left as it was than a failed save.
        }
    }

    /**
     * Turns the form into page content (spec §6.5 "make final"). Appearances are regenerated
     * first when the file asked viewers to do it, otherwise fields without one would vanish.
     */
    fun flattenForm() {
        val form = document.documentCatalog.acroForm ?: return
        if (form.hasXFA()) form.xfa = null
        val fields = form.fieldTree.toList()
        try {
            form.flatten(fields, form.needAppearances)
        } catch (e: IOException) {
            form.flatten(fields, false)
        } catch (e: IllegalArgumentException) {
            form.flatten(fields, false)
        }
    }

    /** Draws [overlays] on the pages they belong to ([pages] by page id); overlays of pages no longer in the document are skipped. */
    fun drawOverlays(overlays: List<Overlay>, pages: Map<String, PDPage>) {
        overlays.groupBy { it.pageId }.forEach { (pageId, onPage) ->
            val page = pages[pageId] ?: return@forEach
            // Append, with the existing content wrapped in q/Q so its state can't move ours.
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                onPage.forEach { overlay ->
                    stream.saveGraphicsState()
                    when (overlay) {
                        is TextOverlay -> drawText(stream, overlay)
                        is MarkOverlay -> drawMark(stream, overlay)
                        is ImageOverlay -> drawImage(stream, overlay)
                    }
                    stream.restoreGraphicsState()
                }
            }
        }
    }

    private fun drawText(stream: PDPageContentStream, overlay: TextOverlay) {
        val font = overlayFont ?: fonts.openRegular().use { PDType0Font.load(document, it, true) }.also { overlayFont = it }
        val text = TextBlock.sanitize(overlay.text) { codePoint -> font.canEncode(codePoint) }
        if (text.isBlank()) return
        stream.setNonStrokingColor(0f, 0f, 0f)
        stream.beginText()
        stream.setFont(font, overlay.fontSize)
        TextBlock.lines(text).forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val (x, baseline) = TextBlock.lineOrigin(index, overlay.fontSize)
            stream.setTextMatrix(OverlayGeometry.textMatrix(overlay.box, x, baseline).toMatrix())
            stream.showText(line)
        }
        stream.endText()
    }

    private fun drawMark(stream: PDPageContentStream, overlay: MarkOverlay) {
        val box = overlay.box
        stream.transform(OverlayGeometry.localToUser(box).toMatrix())
        stream.setStrokingColor(0f, 0f, 0f)
        stream.setLineWidth(MarkShape.STROKE * min(box.width, box.height))
        stream.setLineCapStyle(ROUND)
        stream.setLineJoinStyle(ROUND)
        MarkShape.strokes(overlay.kind).forEach { points ->
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) stream.moveTo(x * box.width, y * box.height) else stream.lineTo(x * box.width, y * box.height)
            }
        }
        stream.stroke()
    }

    private fun drawImage(stream: PDPageContentStream, overlay: ImageOverlay) {
        val image = imageObjects[overlay.imageUri] ?: loadImage(overlay.imageUri).also { imageObjects[overlay.imageUri] = it }
        val box = overlay.box
        stream.drawImage(image, (OverlayGeometry.localToUser(box) * OverlayGeometry.imageUnitToLocal(box)).toMatrix())
    }

    /** Decoded at most [MAX_IMAGE_SIDE] px on the long side; transparency is kept (lossless), photos are JPEG. */
    private fun loadImage(uri: String): PDImageXObject {
        val probe = images.probe(uri) ?: throw IOException("Can't read $uri")
        val scale = min(1f, MAX_IMAGE_SIDE.toFloat() / max(probe.widthPx, probe.heightPx))
        val loaded = images.load(uri, max(1, (probe.widthPx * scale).roundToInt()), max(1, (probe.heightPx * scale).roundToInt()))
        try {
            return if (loaded.hasAlpha) {
                LosslessFactory.createFromImage(document, loaded.bitmap)
            } else {
                JPEGFactory.createFromImage(document, loaded.bitmap, JPEG_QUALITY)
            }
        } finally {
            loaded.bitmap.recycle()
        }
    }


    private companion object {
        const val ROUND = 1
        const val MAX_IMAGE_SIDE = 1600
        const val JPEG_QUALITY = 0.9f
        val FONT_SIZE = Regex("""(\d+(?:\.\d+)?)\s+Tf""")
    }
}

/** Whether [font] can write [codePoint] (shared by the writers that put text into the page content). */
internal fun PDFont.canEncode(codePoint: Int): Boolean = try {
    encode(String(Character.toChars(codePoint)))
    true
} catch (e: IllegalArgumentException) {
    false
} catch (e: IOException) {
    false
}

internal fun Affine.toMatrix() = Matrix(a, b, c, d, e, f)
