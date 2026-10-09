package com.marcogn.pdftoolkit.pdf.ocr

import androidx.compose.ui.geometry.Offset

/**
 * The four corners of a recognised box, clockwise from the top-left as the text reads: ML Kit's
 * order (`Text.Line.getCornerPoints`). Not necessarily a rectangle (perspective, slanted scans).
 */
data class OcrQuad(val topLeft: Offset, val topRight: Offset, val bottomRight: Offset, val bottomLeft: Offset) {

    val corners: List<Offset> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun map(transform: (Offset) -> Offset): OcrQuad =
        OcrQuad(transform(topLeft), transform(topRight), transform(bottomRight), transform(bottomLeft))

    companion object {
        /** From ML Kit's four corner points; null if there aren't four. */
        fun of(corners: List<Offset>): OcrQuad? = if (corners.size == CORNERS) OcrQuad(corners[0], corners[1], corners[2], corners[3]) else null

        private const val CORNERS = 4
    }
}

/** One recognised word (ML Kit's `Text.Element`). */
data class OcrWord(val text: String, val box: OcrQuad)

/**
 * One recognised line (ML Kit's `Text.Line`): its box and its words in reading order. [words] may
 * be empty; then [text] is written over the whole box.
 */
data class OcrLine(val text: String, val box: OcrQuad, val words: List<OcrWord>) {

    /** The words to write: [words], or the whole line as one word when there are none. */
    val wordsOrWhole: List<OcrWord> get() = words.ifEmpty { listOf(OcrWord(text, box)) }

    fun map(transform: (Offset) -> Offset): OcrLine =
        OcrLine(text, box.map(transform), words.map { OcrWord(it.text, it.box.map(transform)) })
}

/**
 * The lines recognised on page [pageIndex] (0-based) of a PDF, in **page points** (the page as
 * displayed: origin top-left, y down, `/Rotate` applied; see
 * [com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper]).
 */
data class OcrPage(val pageIndex: Int, val lines: List<OcrLine>)
