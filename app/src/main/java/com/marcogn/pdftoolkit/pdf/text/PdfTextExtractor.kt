package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.forms.PdfBoxFormReader
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.IdentityHashMap
import javax.inject.Inject

/** The PDF could not be opened for text extraction (missing, damaged, wrong password). */
class TextExtractionException(cause: Throwable?) : IOException("Text extraction failed", cause)

/** Text of a PDF with the position of every glyph (spec §5.1); the UI never touches PdfBox (ADR 0002). */
interface PdfTextExtractor {
    /**
     * The pages of the PDF read from [open], one [PageText] per page in order, as each is
     * extracted: collect it to index progressively. Cold; cancelling the collector stops between
     * two pages. A page that can't be read comes out empty; a document that can't be opened
     * fails the flow with [TextExtractionException]. [password]: for protected PDFs.
     */
    fun pages(open: () -> InputStream?, password: String? = null): Flow<PageText>

    /**
     * A reader for one page at a time (text selection, spec §7.4), on the PDF read from [open].
     * Close it when done. The default runs [pages] up to the page each time; implementations
     * keep the document open instead.
     */
    fun reader(open: () -> InputStream?, password: String? = null): PageTextReader = object : PageTextReader {
        override suspend fun page(index: Int): PageText =
            pages(open, password).firstOrNull { it.pageIndex == index } ?: PageText(index, emptyList())

        override fun close() = Unit
    }
}

/** The text of single pages of one open PDF; see [PdfTextExtractor.reader]. */
interface PageTextReader : Closeable {
    /**
     * Page [index] (0-based), empty if it doesn't exist or can't be read.
     * @throws TextExtractionException if the document can't be opened.
     */
    suspend fun page(index: Int): PageText
}

class PdfBoxTextExtractor @Inject constructor() : PdfTextExtractor {

    override fun pages(open: () -> InputStream?, password: String?): Flow<PageText> = flow {
        val document = try {
            val stream = open() ?: throw TextExtractionException(null)
            stream.use { PDDocument.load(it, password.orEmpty(), MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)) }
        } catch (e: IOException) {
            throw e as? TextExtractionException ?: TextExtractionException(e)
        } catch (e: RuntimeException) {
            throw TextExtractionException(e)
        }
        document.use {
            val stripper = PositionedTextStripper()
            for (index in 0 until document.numberOfPages) {
                currentCoroutineContext().ensureActive()
                emit(stripper.extract(document, index))
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun reader(open: () -> InputStream?, password: String?): PageTextReader = PdfBoxPageTextReader(open, password)

    private companion object {
        const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024
    }
}

/**
 * Keeps the document open between pages, opened on the first request, and the last few pages
 * extracted: selecting text touches the same page many times. Calls are serialised.
 */
private class PdfBoxPageTextReader(
    private val open: () -> InputStream?,
    private val password: String?,
) : PageTextReader {

    private val mutex = Mutex()
    private var document: PDDocument? = null
    private var closed = false
    private val stripper = PositionedTextStripper()
    private val cache = object : LinkedHashMap<Int, PageText>(CACHED_PAGES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?) = size > CACHED_PAGES
    }

    override suspend fun page(index: Int): PageText = mutex.withLock {
        cache[index]?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val doc = document ?: load().also { document = it }
            val text = if (index in 0 until doc.numberOfPages) stripper.extract(doc, index) else PageText(index, emptyList())
            cache[index] = text
            text
        }
    }

    private fun load(): PDDocument {
        if (closed) throw TextExtractionException(null)
        return try {
            val stream = open() ?: throw TextExtractionException(null)
            stream.use { PDDocument.load(it, password.orEmpty(), MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)) }
        } catch (e: IOException) {
            throw e as? TextExtractionException ?: TextExtractionException(e)
        } catch (e: RuntimeException) {
            throw TextExtractionException(e)
        }
    }

    /** Doesn't wait for a page being extracted: that one closes the document as soon as it is done. */
    override fun close() {
        if (mutex.tryLock()) {
            try {
                release()
            } finally {
                mutex.unlock()
            }
        } else {
            closed = true
            CoroutineScope(Dispatchers.IO).launch { mutex.withLock { release() } }
        }
    }

    private fun release() {
        closed = true
        cache.clear()
        runCatching { document?.close() }
        document = null
    }

    private companion object {
        const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024
        const val CACHED_PAGES = 4
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * [PDFTextStripper] that keeps, for each glyph, its Unicode text and its position on the page as
 * displayed. PdfBox still orders the glyphs, merges diacritics and drops duplicate text used as
 * fake bold; its own text output is ignored, and so are its word breaks (see [writeString]).
 *
 * Positions come from each [TextPosition]'s text rendering matrix and its end point, both in user
 * space but shifted by PdfBox so that the crop box's lower-left corner is the origin
 * (`LegacyPDFStreamEngine.processPage`): the shift is added back and the point goes through
 * [PdfPageSpace.userToDisplay], the same matrices the viewer and the fill pane use. PdfBox's own
 * `getX`/`getY` turned by the rotation aren't used: they ignore a crop box away from the origin
 * (found in phase 4a, `PdfBoxFillTest`).
 *
 * Not thread-safe: one per extraction.
 */
class PositionedTextStripper : PDFTextStripper() {

    private val glyphs = mutableListOf<TextGlyph>()
    private val fontExtents = IdentityHashMap<PDFont, FontExtent>()
    private var toDisplay = PdfPageSpace.ofSize(1f, 1f).userToDisplay
    private var cropLeft = 0f
    private var cropBottom = 0f

    /** The text of page [index] (0-based); empty if the page can't be read. */
    fun extract(document: PDDocument, index: Int): PageText {
        glyphs.clear()
        val page = document.getPage(index)
        toDisplay = PdfBoxFormReader.pageBox(page).toPageSpace().userToDisplay
        page.cropBox.let {
            cropLeft = it.lowerLeftX
            cropBottom = it.lowerLeftY
        }
        startPage = index + 1
        endPage = index + 1
        try {
            getText(document)
        } catch (e: IOException) {
            glyphs.clear()
        } catch (e: RuntimeException) {
            glyphs.clear()
        }
        return PageText(index, glyphs.toList())
    }

    /**
     * Called once per word as PdfBox sees it. Its word breaks aren't used: on a page turned by a
     * quarter turn PdfBox 2.0 breaks words between letters ("P erc hé"), so breaks come from the
     * glyphs' positions on the display ([TextGlyph.isFollowedInWordBy]), whatever the rotation.
     */
    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (position in textPositions) {
            val glyph = glyphOf(position)
            val previous = glyphs.lastOrNull()
            glyphs += if (previous != null && !previous.isFollowedInWordBy(glyph)) glyph.copy(spaceBefore = true) else glyph
        }
    }

    private fun glyphOf(position: TextPosition): TextGlyph {
        val matrix = position.textMatrix
        val origin = toDisplay.map(Offset(matrix.translateX + cropLeft, matrix.translateY + cropBottom))
        val end = toDisplay.map(Offset(position.endX + cropLeft, position.endY + cropBottom))
        // The text space's y axis in user space: one unit of it is the font size (and the CTM).
        val up = Offset(matrix.getValue(1, 0), matrix.getValue(1, 1))
        val extent = fontExtents.getOrPut(position.font) { FontExtent.of(position.font) }
        return TextGlyph(
            text = position.unicode.orEmpty(),
            origin = origin,
            end = end,
            ascent = toDisplay.mapVector(up * extent.ascent),
            descent = toDisplay.mapVector(up * extent.descent),
        )
    }
}

/**
 * How far a font's line reaches above and below the baseline, in text space units (fractions of
 * the font size): the font descriptor's `/Ascent` and `/Descent`, else its bounding box, else
 * typical values. Out-of-range values (some fonts carry zeros or whole-em boxes) fall back too.
 */
internal data class FontExtent(val ascent: Float, val descent: Float) {
    companion object {
        val DEFAULT = FontExtent(0.8f, -0.2f)

        fun of(font: PDFont?): FontExtent {
            if (font == null) return DEFAULT
            return try {
                val descriptor = font.fontDescriptor
                val box = font.boundingBox
                val ascent = toTextSpace(font, descriptor?.ascent)?.takeIf { it in ASCENT_RANGE }
                    ?: toTextSpace(font, box?.upperRightY)?.takeIf { it in ASCENT_RANGE }
                    ?: DEFAULT.ascent
                val descent = toTextSpace(font, descriptor?.descent)?.takeIf { it in DESCENT_RANGE }
                    ?: toTextSpace(font, box?.lowerLeftY)?.takeIf { it in DESCENT_RANGE }
                    ?: DEFAULT.descent
                FontExtent(ascent, descent)
            } catch (e: IOException) {
                DEFAULT
            } catch (e: RuntimeException) {
                DEFAULT
            }
        }

        /**
         * Glyph space → text space: 1/1000 for every font but Type 3, whose glyph space is its
         * `/FontMatrix` (PDF 32000-1 §9.2.4, §9.8). Not `PDFont.getFontMatrix` for the others:
         * for a standard 14 font PdfBox-Android reports the matrix of the substitute it renders
         * with (1/2048 for Helvetica), while `/Ascent` and `/Descent` stay in thousandths.
         */
        private fun toTextSpace(font: PDFont, value: Float?): Float? = value?.takeIf { it != 0f }?.let {
            if (font is PDType3Font) font.fontMatrix.getValue(1, 1) * it else it / GLYPH_UNITS
        }

        private const val GLYPH_UNITS = 1000f

        private val ASCENT_RANGE = 0.3f..1.5f
        private val DESCENT_RANGE = -1f..-0.01f
    }
}
