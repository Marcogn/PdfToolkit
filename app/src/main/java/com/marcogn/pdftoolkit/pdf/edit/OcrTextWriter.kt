package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.pdf.forms.PdfBoxFormReader
import com.marcogn.pdftoolkit.pdf.ocr.OcrFontMetrics
import com.marcogn.pdftoolkit.pdf.ocr.OcrGeometry
import com.marcogn.pdftoolkit.pdf.ocr.OcrLine
import com.marcogn.pdftoolkit.pdf.ocr.OcrWord
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.marcogn.pdftoolkit.pdf.text.FontExtent
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException

/**
 * Writes recognised text as an invisible layer (spec §7.2): text render mode 3 (neither fill nor
 * stroke, PDF 32000-1 §9.3.6) in Noto Sans, appended to the page content, placed by
 * [OcrGeometry]. Readers find, select and copy it; nobody sees it.
 */
internal class OcrTextWriter(private val document: PDDocument, private val fonts: FontSource) {

    /** Embedded on first use as a subset, like the text overlays; PdfBox adds its `/ToUnicode`, which search needs. */
    private val font: PDType0Font by lazy { fonts.openRegular().use { PDType0Font.load(document, it, true) } }

    private val metrics: OcrFontMetrics by lazy {
        // The same extent the text extractor gives each glyph, so a found word covers its line box.
        val extent = FontExtent.of(font)
        object : OcrFontMetrics {
            override val ascent = extent.ascent
            override val descent = extent.descent
            override fun advance(text: String): Float = try {
                font.getStringWidth(text) / GLYPH_UNITS
            } catch (e: IOException) {
                0f
            } catch (e: IllegalArgumentException) {
                0f
            }
        }
    }

    /** Writes [lines] (page points) on [page]; returns how many runs were written. */
    fun write(page: PDPage, lines: List<OcrLine>): Int {
        val space = PdfBoxFormReader.pageBox(page).toPageSpace()
        val runs = lines.flatMap { line -> OcrGeometry.runs(sanitize(line), space, metrics) }
        if (runs.isEmpty()) return 0
        // Appended, with the existing content wrapped in q/Q so its state can't move ours.
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.beginText()
            stream.setRenderingMode(RenderingMode.NEITHER)
            for (run in runs) {
                stream.setFont(font, run.fontSize)
                stream.setHorizontalScaling(run.horizontalScaling)
                run.matrix.let { stream.setTextMatrix(Matrix(it.a, it.b, it.c, it.d, it.e, it.f)) }
                stream.showText(run.text)
            }
            stream.endText()
        }
        return runs.size
    }

    /** Drops what Noto Sans can't encode (a stray symbol must not fail the whole page), as the text overlays do. */
    private fun sanitize(line: OcrLine): OcrLine = OcrLine(
        text = clean(line.text),
        box = line.box,
        words = line.words.map { OcrWord(clean(it.text), it.box) },
    )

    private fun clean(text: String): String =
        TextBlock.sanitize(text) { canEncode(it) }.replace('\n', ' ')

    private fun canEncode(codePoint: Int): Boolean = try {
        font.encode(String(Character.toChars(codePoint)))
        true
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: IOException) {
        false
    }

    private companion object {
        const val GLYPH_UNITS = 1000f
    }
}

