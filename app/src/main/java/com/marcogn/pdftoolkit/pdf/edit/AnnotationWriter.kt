package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.UserRect
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationFingerprint
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationGeometry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDAppearanceContentStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import java.util.Calendar

/**
 * Writes what the annotate tools changed (spec §7.4, ADR 0004): removes annotations of the source
 * files and adds the new ones as standard annotations (Highlight, Underline, StrikeOut, Squiggly,
 * Ink), each with its own appearance stream so every reader shows it the same way.
 *
 * The appearance is drawn from [AnnotationGeometry], the same paths the app draws on screen, rather
 * than by PdfBox's appearance handlers: those only handle quads that are horizontal or vertical
 * (`PDHighlightAppearanceHandler`, 2.0 branch) and the screen would have to imitate them.
 */
internal class AnnotationWriter(private val document: PDDocument) {

    /**
     * Removes the annotations [removed] points at from [page], with their pop-up and the replies to
     * them (`/IRT`), as Acrobat does. Must run before anything else changes `/Annots` (flattening a
     * form removes widgets): the references are indices into the array as it was read. A
     * reference whose fingerprint no longer matches is skipped.
     */
    fun removeExisting(page: PDPage, removed: Collection<AnnotationRef>) {
        if (removed.isEmpty()) return
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return
        val doomed = HashSet<COSDictionary>()
        for (ref in removed) {
            val dictionary = annots.getObjectOrNull(ref.index) as? COSDictionary ?: continue
            if (AnnotationFingerprint.of(dictionary) != ref.fingerprint) continue
            doomed += dictionary
        }
        if (doomed.isEmpty()) return
        // Replies, their replies, and every pop-up of what goes.
        val entries = (0 until annots.size()).mapNotNull { annots.getObject(it) as? COSDictionary }
        var grew = true
        while (grew) {
            grew = false
            for (entry in entries) {
                if (entry in doomed) continue
                val inReplyTo = entry.getDictionaryObject(IRT) as? COSDictionary
                val parent = entry.getDictionaryObject(COSName.PARENT) as? COSDictionary
                if ((inReplyTo != null && inReplyTo in doomed) || (isPopup(entry) && parent != null && parent in doomed)) {
                    doomed += entry
                    grew = true
                }
            }
        }
        doomed.toList().forEach { (it.getDictionaryObject(POPUP) as? COSDictionary)?.let { popup -> doomed += popup } }
        // A new array: /Annots may be shared with other pages.
        val kept = COSArray()
        for (i in 0 until annots.size()) {
            val dictionary = annots.getObject(i) as? COSDictionary
            if (dictionary == null || dictionary !in doomed) kept.add(annots.get(i))
        }
        page.cosObject.setItem(COSName.ANNOTS, kept)
    }

    /** Adds [annotations] to the pages they belong to ([pages] by page id); annotations of pages no longer in the document are skipped. */
    fun addNew(annotations: List<NewAnnotation>, pages: Map<String, PDPage>) {
        val now = Calendar.getInstance()
        for (annotation in annotations) {
            val page = pages[annotation.pageId] ?: continue
            val pdAnnotation = create(annotation, now)
            pdAnnotation.setPage(page)
            // A copy of /Annots, as in removeExisting: the array may be shared with other pages.
            val annots = COSArray()
            (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { old -> for (i in 0 until old.size()) annots.add(old.get(i)) }
            annots.add(pdAnnotation)
            page.cosObject.setItem(COSName.ANNOTS, annots)
        }
    }

    private fun create(annotation: NewAnnotation, now: Calendar): PDAnnotation {
        val shape = annotation.shape
        val bounds = AnnotationGeometry.bounds(shape)
        val markup: PDAnnotationMarkup = when (shape) {
            is AnnotationShape.TextMarkup -> PDAnnotationTextMarkup(shape.kind.subtype).apply {
                quadPoints = shape.quads.flatMap { it.toQuadPoints().asList() }.toFloatArray()
            }
            is AnnotationShape.Ink -> PDAnnotationMarkup().apply {
                cosObject.setName(COSName.SUBTYPE, INK)
                inkList = shape.strokes.map { stroke -> stroke.flatMap { listOf(it.x, it.y) }.toFloatArray() }.toTypedArray()
                borderStyle = PDBorderStyleDictionary().apply { width = shape.width }
            }
        }
        return markup.apply {
            rectangle = bounds.toPdRectangle()
            color = PDColor(floatArrayOf(annotation.style.color.red, annotation.style.color.green, annotation.style.color.blue), PDDeviceRGB.INSTANCE)
            constantOpacity = annotation.style.opacity
            isPrinted = true
            annotationName = annotation.id
            creationDate = now
            setModifiedDate(now)
            appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearanceOf(annotation, bounds)) }
        }
    }

    /**
     * The normal appearance: a form whose bounding box is the annotation's `/Rect` with the identity
     * matrix, so its space is the page's user space and the paths go in as they are. A highlight
     * multiplies with the page (text stays readable), as in Acrobat; opacity is `/CA` on both.
     */
    private fun appearanceOf(annotation: NewAnnotation, bounds: UserRect): PDAppearanceStream {
        val stream = PDAppearanceStream(document)
        stream.bBox = bounds.toPdRectangle()
        stream.resources = PDResources()
        val isHighlight = (annotation.shape as? AnnotationShape.TextMarkup)?.kind == MarkupKind.HIGHLIGHT
        val color = annotation.style.color.let { floatArrayOf(it.red, it.green, it.blue) }
        PDAppearanceContentStream(stream).use { cs ->
            val state = PDExtendedGraphicsState().apply {
                setStrokingAlphaConstant(annotation.style.opacity)
                setNonStrokingAlphaConstant(annotation.style.opacity)
                if (isHighlight) setBlendMode(BlendMode.MULTIPLY)
            }
            cs.setGraphicsStateParameters(state)
            cs.setNonStrokingColor(color)
            cs.setStrokingColor(color)
            cs.setLineCapStyle(ROUND)
            cs.setLineJoinStyle(ROUND)
            for (paths in AnnotationGeometry.paths(annotation.shape)) {
                for (polygon in paths.fills) {
                    path(cs, polygon)
                    cs.closePath()
                    cs.fill()
                }
                if (paths.strokes.isNotEmpty()) {
                    cs.setLineWidth(paths.strokeWidth)
                    paths.strokes.forEach { polyline ->
                        path(cs, polyline)
                        // A single point still shows as a dot with round caps.
                        if (polyline.size == 1) cs.lineTo(polyline[0].x, polyline[0].y)
                        cs.stroke()
                    }
                }
            }
        }
        return stream
    }

    private fun path(cs: PDAppearanceContentStream, points: List<UserPoint>) {
        points.forEachIndexed { i, p -> if (i == 0) cs.moveTo(p.x, p.y) else cs.lineTo(p.x, p.y) }
    }

    private fun isPopup(dictionary: COSDictionary) = dictionary.getNameAsString(COSName.SUBTYPE) == POPUP_SUBTYPE

    private fun COSArray.getObjectOrNull(index: Int): COSBase? = if (index in 0 until size()) getObject(index) else null

    private fun UserRect.toPdRectangle() = PDRectangle(left, bottom, right - left, top - bottom)

    private companion object {
        const val ROUND = 1
        const val INK = "Ink"
        const val POPUP_SUBTYPE = "Popup"
        val IRT: COSName = COSName.getPDFName("IRT")
        val POPUP: COSName = COSName.getPDFName("Popup")
    }
}
