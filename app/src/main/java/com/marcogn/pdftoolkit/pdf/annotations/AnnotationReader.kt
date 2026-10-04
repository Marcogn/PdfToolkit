package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.ExistingAnnotation
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.PageBox
import com.marcogn.pdftoolkit.domain.fill.UserRect
import com.marcogn.pdftoolkit.pdf.forms.PdfBoxFormReader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max

/**
 * The annotations of one PDF: per page, its box ([pageBoxes], to place them) and its annotations
 * ([pages]), both indexed like the document's pages.
 */
data class DocumentAnnotations(val pageBoxes: List<PageBox>, val pages: List<List<ExistingAnnotation>>) {
    fun on(pageIndex: Int): List<ExistingAnnotation> = pages.getOrNull(pageIndex).orEmpty()
}

/**
 * Reads the annotations of a PDF (spec §7.4), which the system renderer doesn't draw: the app draws
 * them itself. The UI never touches PdfBox (ADR 0002).
 */
interface AnnotationReader {
    /**
     * The annotations of the PDF read from [open], as document [docId] (`DocRef.id`) of an edit
     * session. Null if it can't be read (missing, damaged, wrong [password]).
     */
    suspend fun read(open: () -> InputStream?, password: String? = null, docId: Int = 0): DocumentAnnotations?
}

/**
 * [AnnotationReader] on PdfBox-Android (ADR 0004).
 *
 * Every annotation the user could see or remove is listed: links, form widgets, pop-ups and printer
 * marks are not (they belong to other tools or to another annotation), nor are hidden ones. Text
 * markup and ink get a [AnnotationShape] to draw; the other kinds are listed without one.
 */
class PdfBoxAnnotationReader @Inject constructor() : AnnotationReader {

    override suspend fun read(open: () -> InputStream?, password: String?, docId: Int): DocumentAnnotations? = withContext(Dispatchers.IO) {
        try {
            val stream = open() ?: return@withContext null
            stream.use {
                PDDocument.load(it, password.orEmpty(), MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)).use { document -> read(document, docId) }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    companion object {
        private const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024

        /** Subtypes that aren't annotations a reader adds to a page: other tools own them. */
        private val SKIPPED = setOf("Link", "Widget", "Popup", "PrinterMark", "TrapNet")

        /** Annotation flags (ISO 32000-1 §12.5.3): Hidden and NoView mean nothing is shown on screen. */
        private const val FLAG_HIDDEN = 1 shl 1
        private const val FLAG_NO_VIEW = 1 shl 5

        /** Thinnest ink stroke kept, in points (a width of 0 means the thinnest line the device can draw). */
        private const val MIN_INK_WIDTH = 0.1f

        /** Reads an open document; also used by the tests. */
        fun read(document: PDDocument, docId: Int): DocumentAnnotations {
            val pages = document.pages.toList()
            return DocumentAnnotations(
                pageBoxes = pages.map(PdfBoxFormReader::pageBox),
                pages = pages.mapIndexed { index, page -> readPage(page, docId, index) },
            )
        }

        private fun readPage(page: PDPage, docId: Int, pageIndex: Int): List<ExistingAnnotation> {
            val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return emptyList()
            return (0 until annots.size()).mapNotNull { index ->
                val dictionary = annots.getObject(index) as? COSDictionary ?: return@mapNotNull null
                try {
                    toAnnotation(dictionary, AnnotationLocation(docId, pageIndex, index))
                } catch (e: RuntimeException) {
                    // A malformed annotation is left out, never the whole page.
                    null
                }
            }
        }

        private fun toAnnotation(dictionary: COSDictionary, at: AnnotationLocation): ExistingAnnotation? {
            val subtype = dictionary.getNameAsString(COSName.SUBTYPE) ?: return null
            if (subtype in SKIPPED) return null
            val flags = dictionary.getInt(COSName.F, 0)
            if (flags and (FLAG_HIDDEN or FLAG_NO_VIEW) != 0) return null
            val bounds = AnnotationFingerprint.rect(dictionary) ?: return null
            val color = color(dictionary.getDictionaryObject(COSName.C) as? COSArray)
            val style = AnnotationStyle(color ?: AnnotationColor.BLACK, opacity(dictionary))
            // No colour means transparent (ISO 32000-1 §12.5.2): nothing to draw.
            val shape = if (color == null) null else shape(dictionary, subtype, bounds)
            val ref = AnnotationRef(at.docId, at.pageIndex, at.index, AnnotationFingerprint.of(subtype, bounds))
            return ExistingAnnotation(ref, subtype, shape, style, bounds)
        }

        private fun shape(dictionary: COSDictionary, subtype: String, bounds: UserRect): AnnotationShape? {
            MarkupKind.ofSubtype(subtype)?.let { kind ->
                // Without /QuadPoints the whole /Rect is marked, as pdfium does.
                val quads = (dictionary.getDictionaryObject(COSName.QUADPOINTS) as? COSArray)?.toFloatArray()
                    ?.let(Quad::fromQuadPoints)
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(quadOf(bounds))
                return AnnotationShape.TextMarkup(kind, quads)
            }
            if (subtype == INK) {
                val list = dictionary.getDictionaryObject(COSName.INKLIST) as? COSArray ?: return null
                val strokes = (0 until list.size()).mapNotNull { i ->
                    val values = (list.getObject(i) as? COSArray)?.toFloatArray() ?: return@mapNotNull null
                    (0 until values.size / 2).map { UserPoint(values[2 * it], values[2 * it + 1]) }.takeIf { it.isNotEmpty() }
                }
                if (strokes.isEmpty()) return null
                return AnnotationShape.Ink(strokes, max(borderWidth(dictionary), MIN_INK_WIDTH))
            }
            return null
        }

        private fun quadOf(r: UserRect) = Quad(
            upperLeft = UserPoint(r.left, r.top),
            upperRight = UserPoint(r.right, r.top),
            lowerLeft = UserPoint(r.left, r.bottom),
            lowerRight = UserPoint(r.right, r.bottom),
        )

        /** `/BS /W`, else the third number of `/Border`, else 1 (ISO 32000-1 §12.5.4). */
        private fun borderWidth(dictionary: COSDictionary): Float {
            (dictionary.getDictionaryObject(COSName.BS) as? COSDictionary)?.let { bs ->
                (bs.getDictionaryObject(COSName.W) as? COSNumber)?.let { return it.floatValue() }
            }
            (dictionary.getDictionaryObject(COSName.BORDER) as? COSArray)?.let { border ->
                (border.getObject(2) as? COSNumber)?.let { return it.floatValue() }
            }
            return 1f
        }

        /** `/CA`, the constant opacity, 1 if missing. */
        private fun opacity(dictionary: COSDictionary): Float =
            (dictionary.getDictionaryObject(COSName.CA) as? COSNumber)?.floatValue()?.coerceIn(0f, 1f) ?: 1f

        /** `/C`: gray, RGB or CMYK (ISO 32000-1 §12.5.2); null for none or anything else. */
        private fun color(array: COSArray?): AnnotationColor? {
            val c = array?.toFloatArray()?.map { it.coerceIn(0f, 1f) } ?: return null
            return when (c.size) {
                1 -> AnnotationColor(c[0], c[0], c[0])
                3 -> AnnotationColor(c[0], c[1], c[2])
                // The naive conversion; annotations rarely use CMYK.
                4 -> AnnotationColor((1 - c[0]) * (1 - c[3]), (1 - c[1]) * (1 - c[3]), (1 - c[2]) * (1 - c[3]))
                else -> null
            }
        }

        private const val INK = "Ink"
    }

    private data class AnnotationLocation(val docId: Int, val pageIndex: Int, val index: Int)
}

/**
 * The subtype and rectangle of an annotation as a string, rounded so that writing a file and
 * reading it back gives the same text: how a saved [AnnotationRef] checks that it still points at
 * the annotation the user chose.
 */
object AnnotationFingerprint {
    fun of(subtype: String, rect: UserRect): String =
        String.format(Locale.ROOT, "%s@%.1f,%.1f,%.1f,%.1f", subtype, rect.left, rect.bottom, rect.right, rect.top)

    /** The fingerprint of an annotation dictionary; null without a subtype or a usable `/Rect`. */
    fun of(dictionary: COSDictionary): String? {
        val subtype = dictionary.getNameAsString(COSName.SUBTYPE) ?: return null
        return rect(dictionary)?.let { of(subtype, it) }
    }

    /** `/Rect`, normalised (some writers swap the corners); null if missing or too short. */
    fun rect(dictionary: COSDictionary): UserRect? {
        val values = (dictionary.getDictionaryObject(COSName.RECT) as? COSArray)?.toFloatArray() ?: return null
        if (values.size < RECT_NUMBERS) return null
        return UserRect(
            left = minOf(values[0], values[2]),
            bottom = minOf(values[1], values[3]),
            right = maxOf(values[0], values[2]),
            top = maxOf(values[1], values[3]),
        )
    }

    private const val RECT_NUMBERS = 4
}
