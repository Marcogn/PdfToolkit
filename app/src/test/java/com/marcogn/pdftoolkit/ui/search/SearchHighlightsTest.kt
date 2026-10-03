package com.marcogn.pdftoolkit.ui.search

import androidx.compose.ui.geometry.Rect
import com.marcogn.pdftoolkit.pdf.text.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightsTest {

    private fun match(page: Int, x: Float) = TextMatch(page, listOf(Rect(x, 0f, x + 10f, 10f)))

    @Test
    fun rectanglesAreGroupedByDocumentPage() {
        val highlights = SearchHighlights.of(listOf(match(0, 1f), match(2, 2f), match(2, 30f)), current = 1)
        assertEquals(1, highlights.rectsOn(0).size)
        assertTrue(highlights.rectsOn(1).isEmpty())
        assertEquals(listOf(2f, 30f), highlights.rectsOn(2).map { it.left })
    }

    @Test
    fun theCurrentOccurrenceIsOnlyReportedOnItsPage() {
        val highlights = SearchHighlights.of(listOf(match(0, 1f), match(2, 2f)), current = 1)
        assertEquals(listOf(2f), highlights.currentRectsOn(2).map { it.left })
        assertTrue(highlights.currentRectsOn(0).isEmpty())
    }

    @Test
    fun noResultsOrNoCurrentDrawNothingStrong() {
        assertTrue(SearchHighlights.of(emptyList(), -1).isEmpty)
        val withoutCurrent = SearchHighlights.of(listOf(match(0, 1f)), current = -1)
        assertTrue(withoutCurrent.currentRectsOn(0).isEmpty())
        assertEquals(1, withoutCurrent.rectsOn(0).size)
    }

    @Test
    fun aMultiLineOccurrenceKeepsEveryLine() {
        val twoLines = TextMatch(1, listOf(Rect(50f, 0f, 90f, 10f), Rect(0f, 14f, 20f, 24f)))
        val highlights = SearchHighlights.of(listOf(twoLines), current = 0)
        assertEquals(2, highlights.rectsOn(1).size)
        assertEquals(2, highlights.currentRectsOn(1).size)
    }
}
