package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
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
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
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
 * Ink), each with its own appearance stream so every reader shows it the same way; freehand ink
 * can instead be drawn into the page content ("make final").
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

    /**
     * Adds [annotations] to the pages they belong to ([pages] by page id); annotations of pages no
     * longer in the document are skipped. With [flattenInk] ("make final", spec §7.4) freehand
     * strokes are drawn into the page content instead, exactly as their appearance shows them:
     * they can no longer be removed, in this app or any other.
     */
    fun addNew(annotations: List<NewAnnotation>, pages: Map<String, PDPage>, flattenInk: Boolean = false) {
        val now = Calendar.getInstance()
        val (flattened, kept) = annotations.partition { flattenInk && it.shape is AnnotationShape.Ink }
        for (annotation in kept) {
            val page = pages[annotation.pageId] ?: continue
            val pdAnnotation = create(annotation, now)
            pdAnnotation.setPage(page)
            // A copy of /Annots, as in removeExisting: the array may be shared with other pages.
            val annots = COSArray()
            (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { old -> for (i in 0 until old.size()) annots.add(old.get(i)) }
            annots.add(pdAnnotation)
            page.cosObject.setItem(COSName.ANNOTS, annots)
        }
        // One appended content stream per page, in the order the strokes were drawn.
        for ((pageId, onPage) in flattened.groupBy { it.pageId }) {
            val page = pages[pageId] ?: continue
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                val canvas = PageCanvas(cs)
                for (annotation in onPage) {
                    cs.saveGraphicsState()
                    paint(canvas, annotation)
                    cs.restoreGraphicsState()
                }
            }
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
     * matrix, so its space is the page's user space and the paths go in as they are.
     */
    private fun appearanceOf(annotation: NewAnnotation, bounds: UserRect): PDAppearanceStream {
        val stream = PDAppearanceStream(document)
        stream.bBox = bounds.toPdRectangle()
        stream.resources = PDResources()
        PDAppearanceContentStream(stream).use { cs -> paint(AppearanceCanvas(cs), annotation) }
        return stream
    }

    /**
     * Draws [annotation] from [AnnotationGeometry], in user space: the same paths the screen draws.
     * A highlight (text or freehand) multiplies with the page, as in Acrobat; opacity is `/CA` on
     * both. Fills of one group go in one path filled with the nonzero rule (`f`).
     */
    private fun paint(canvas: PathCanvas, annotation: NewAnnotation) {
        canvas.graphicsState(
            PDExtendedGraphicsState().apply {
                setStrokingAlphaConstant(annotation.style.opacity)
                setNonStrokingAlphaConstant(annotation.style.opacity)
                if (AnnotationGeometry.multiplies(annotation.shape)) setBlendMode(BlendMode.MULTIPLY)
            },
        )
        canvas.color(annotation.style.color.let { floatArrayOf(it.red, it.green, it.blue) })
        canvas.roundCapsAndJoins()
        for (paths in AnnotationGeometry.paths(annotation.shape)) {
            if (paths.fills.isNotEmpty()) {
                for (polygon in paths.fills) {
                    path(canvas, polygon)
                    canvas.closePath()
                }
                canvas.fill()
            }
            if (paths.strokes.isNotEmpty()) {
                canvas.lineWidth(paths.strokeWidth)
                paths.strokes.forEach { polyline ->
                    path(canvas, polyline)
                    // A single point still shows as a dot with round caps.
                    if (polyline.size == 1) canvas.lineTo(polyline[0].x, polyline[0].y)
                    canvas.stroke()
                }
            }
        }
    }

    private fun path(canvas: PathCanvas, points: List<UserPoint>) {
        points.forEachIndexed { i, p -> if (i == 0) canvas.moveTo(p.x, p.y) else canvas.lineTo(p.x, p.y) }
    }

    /**
     * The drawing operations [paint] needs, over an appearance stream or a page's content stream:
     * PdfBox 2.0's `PDPageContentStream` doesn't share a public type with `PDAppearanceContentStream`.
     */
    private interface PathCanvas {
        fun graphicsState(state: PDExtendedGraphicsState)
        fun color(rgb: FloatArray)
        fun roundCapsAndJoins()
        fun lineWidth(width: Float)
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun closePath()
        fun fill()
        fun stroke()
    }

    private class AppearanceCanvas(private val cs: PDAppearanceContentStream) : PathCanvas {
        override fun graphicsState(state: PDExtendedGraphicsState) = cs.setGraphicsStateParameters(state)
        override fun color(rgb: FloatArray) {
            cs.setNonStrokingColor(rgb)
            cs.setStrokingColor(rgb)
        }
        override fun roundCapsAndJoins() {
            cs.setLineCapStyle(ROUND)
            cs.setLineJoinStyle(ROUND)
        }
        override fun lineWidth(width: Float) = cs.setLineWidth(width)
        override fun moveTo(x: Float, y: Float) = cs.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = cs.lineTo(x, y)
        override fun closePath() = cs.closePath()
        override fun fill() = cs.fill()
        override fun stroke() = cs.stroke()
    }

    private class PageCanvas(private val cs: PDPageContentStream) : PathCanvas {
        override fun graphicsState(state: PDExtendedGraphicsState) = cs.setGraphicsStateParameters(state)
        override fun color(rgb: FloatArray) {
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            cs.setStrokingColor(rgb[0], rgb[1], rgb[2])
        }
        override fun roundCapsAndJoins() {
            cs.setLineCapStyle(ROUND)
            cs.setLineJoinStyle(ROUND)
        }
        override fun lineWidth(width: Float) = cs.setLineWidth(width)
        override fun moveTo(x: Float, y: Float) = cs.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = cs.lineTo(x, y)
        override fun closePath() = cs.closePath()
        override fun fill() = cs.fill()
        override fun stroke() = cs.stroke()
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
