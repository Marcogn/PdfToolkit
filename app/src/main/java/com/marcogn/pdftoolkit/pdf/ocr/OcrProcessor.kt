package com.marcogn.pdftoolkit.pdf.ocr

import android.os.ParcelFileDescriptor
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import com.marcogn.pdftoolkit.pdf.render.PageKey
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.PdfTextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.ceil

/** How an OCR run ended when it didn't fail. */
sealed interface OcrOutcome {
    /** [output] was written: [lines] lines recognised, on [pages] pages with text. */
    data class Written(val pages: Int, val lines: Int) : OcrOutcome

    /** Every page already has text: nothing to recognise, [output] untouched. */
    data object AlreadyText : OcrOutcome

    /** Pages were recognised but no text was found on them: [output] untouched. */
    data object NoTextFound : OcrOutcome
}

/**
 * Makes a PDF searchable (spec §7.2, plan 10a): the pages that have no text ([needsRecognition])
 * are rendered ([OcrGeometry.renderScale]), recognised, and their text is written as an
 * invisible layer through [PdfEditor] (ADR 0002). One page bitmap alive at a time.
 */
class OcrProcessor @Inject constructor(
    private val extractor: PdfTextExtractor,
    private val recognizers: TextRecognizerFactory,
    private val editor: PdfEditor,
) {
    /**
     * Recognises [source] and writes the result to [output]. [onProgress] receives 0..1.
     * Cancellable between pages.
     *
     * @throws com.marcogn.pdftoolkit.pdf.text.TextExtractionException if [source] can't be read,
     * [IOException] if a page can't be rendered or recognised,
     * [com.marcogn.pdftoolkit.domain.edit.SaveException] if the result can't be written.
     */
    suspend fun run(source: File, output: File, onProgress: (Float) -> Unit = {}): OcrOutcome {
        onProgress(0f)
        val todo = extractor.pages({ source.inputStream() }).filter(::needsRecognition).map { it.pageIndex }.toList()
        if (todo.isEmpty()) return OcrOutcome.AlreadyText
        val pages = recognize(source, todo) { done -> onProgress(RECOGNISED * done / todo.size) }
        val lines = pages.sumOf { it.lines.size }
        if (lines == 0) return OcrOutcome.NoTextFound
        editor.addTextLayer(source, output, pages)
        onProgress(1f)
        return OcrOutcome.Written(pages.count { it.lines.isNotEmpty() }, lines)
    }

    private suspend fun recognize(source: File, pageIndices: List<Int>, onPage: (Int) -> Unit): List<OcrPage> {
        val fd = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) }
        val renderer = try {
            PdfDocumentRenderer.open(fd)
        } catch (e: CancellationException) {
            // Cancelled before the renderer took the descriptor: closing twice is harmless.
            runCatching { fd.close() }
            throw e
        }
        try {
            recognizers.create().use { recognizer ->
                return pageIndices.mapIndexed { done, index ->
                    currentCoroutineContext().ensureActive()
                    // PdfBox and pdfium may disagree on a damaged file's page count.
                    val size = renderer.pageSizes.getOrNull(index) ?: throw IOException("Page ${index + 1} not found by the renderer")
                    val scale = OcrGeometry.renderScale(size)
                    val key = PageKey(index, ceil(size.width * scale).toInt(), ceil(size.height * scale).toInt(), scale)
                    val bitmap = renderer.render(key) ?: throw IOException("Page ${index + 1} could not be rendered")
                    // ML Kit can't be stopped mid-image: let it finish before the bitmap and the client go away.
                    val lines = withContext(NonCancellable) {
                        try {
                            recognizer.recognize(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    onPage(done + 1)
                    OcrPage(index, lines.map { line -> line.map { pixel -> pixel / scale } })
                }
            }
        } finally {
            renderer.close()
        }
    }

    companion object {
        /** Share of the progress bar for recognition; writing takes the rest. */
        private const val RECOGNISED = 0.9f

        /**
         * A page needs recognition when it has no text at all: a scan. Any character, even an
         * invisible one (an earlier OCR) or a stamped page number, means the page is left alone,
         * so text is never written twice.
         */
        fun needsRecognition(page: PageText): Boolean = page.glyphs.none { glyph -> glyph.text.any { !it.isWhitespace() } }
    }
}
