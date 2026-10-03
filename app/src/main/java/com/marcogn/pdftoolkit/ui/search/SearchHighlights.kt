package com.marcogn.pdftoolkit.ui.search

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import com.marcogn.pdftoolkit.pdf.text.SearchState
import com.marcogn.pdftoolkit.pdf.text.TextMatch

/**
 * The rectangles to draw over the pages for the open search (spec §5.1): overlay only, never
 * written into the PDF. In page points; the viewport turns them into pixels with
 * [com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper].
 */
@Immutable
class SearchHighlights private constructor(
    private val byPage: Map<Int, List<Rect>>,
    private val currentMatch: TextMatch?,
) {
    /** All occurrences on [pageIndex] (document page index), the current one included. */
    fun rectsOn(pageIndex: Int): List<Rect> = byPage[pageIndex].orEmpty()

    /** The rectangles of the current occurrence if it is on [pageIndex]. */
    fun currentRectsOn(pageIndex: Int): List<Rect> = currentMatch?.takeIf { it.pageIndex == pageIndex }?.rects.orEmpty()

    val isEmpty: Boolean get() = byPage.isEmpty()

    companion object {
        val None = SearchHighlights(emptyMap(), null)

        fun of(matches: List<TextMatch>, current: Int): SearchHighlights {
            if (matches.isEmpty()) return None
            val byPage = HashMap<Int, MutableList<Rect>>()
            matches.forEach { byPage.getOrPut(it.pageIndex) { ArrayList() } += it.rects }
            return SearchHighlights(byPage, matches.getOrNull(current))
        }

        fun of(state: SearchState): SearchHighlights = of(state.matches, state.current)
    }
}

/** Asks the viewer to bring [match] to the centre of the screen; [token] tells one request from the next. */
@Immutable
data class RevealRequest(val token: Int, val match: TextMatch)
