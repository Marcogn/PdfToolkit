package com.marcogn.pdftoolkit.ui.search

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.pdf.text.IndexStatus
import com.marcogn.pdftoolkit.pdf.text.SearchQuery
import com.marcogn.pdftoolkit.pdf.text.SearchState
import com.marcogn.pdftoolkit.pdf.text.TextMatch
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Italian locale set explicitly: Robolectric starts in English and would load `values-en/`. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class SearchBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun match(page: Int) = TextMatch(page, listOf(Rect(0f, 0f, 10f, 10f)))

    private fun state(
        matches: List<TextMatch> = emptyList(),
        current: Int = -1,
        status: IndexStatus = IndexStatus.DONE,
        pagesWithText: Int = 1,
        query: String? = "x",
    ) = SearchState(
        pageCount = 10,
        query = query?.let { SearchQuery.of(it) },
        matches = matches,
        current = current,
        pagesWithText = pagesWithText,
        status = status,
    )

    private var previous = 0
    private var next = 0
    private var closed = 0
    private val typed = mutableListOf<String>()

    private fun setBar(state: SearchState, query: String = "x") {
        composeRule.setContent {
            PdfToolkitTheme {
                SearchTopBar(
                    query = query,
                    onQueryChange = { typed += it },
                    state = state,
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    onClose = { closed++ },
                )
            }
        }
    }

    @Test
    fun counterShowsTheCurrentResultOutOfTheTotal() {
        setBar(state(matches = List(17) { match(it) }, current = 2))
        composeRule.onNodeWithText("3 di 17").assertIsDisplayed()
    }

    @Test
    fun noResultsIsShownOnlyOnceTheDocumentHasBeenRead() {
        setBar(state(status = IndexStatus.DONE))
        composeRule.onNodeWithText("Nessun risultato").assertIsDisplayed()
    }

    @Test
    fun nothingIsClaimedWhileTheIndexIsStillBeingBuilt() {
        setBar(state(status = IndexStatus.RUNNING))
        composeRule.onNodeWithText("Nessun risultato").assertDoesNotExist()
    }

    @Test
    fun arrowsAreDisabledWithoutResultsAndCallBackWithThem() {
        setBar(state())
        composeRule.onNodeWithContentDescription("Risultato successivo").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Risultato precedente").assertIsNotEnabled()
    }

    @Test
    fun arrowsAndCloseCallBack() {
        setBar(state(matches = listOf(match(0), match(1)), current = 0))
        composeRule.onNodeWithContentDescription("Risultato successivo").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Risultato precedente").performClick()
        composeRule.onNodeWithContentDescription("Chiudi la ricerca").performClick()
        assertEquals(1, next)
        assertEquals(1, previous)
        assertEquals(1, closed)
    }

    @Test
    fun typingReportsTheNewText() {
        setBar(state(query = null), query = "")
        composeRule.onNodeWithText("Cerca nel documento").assertIsDisplayed()
        composeRule.onNodeWithText("Cerca nel documento").performTextInput("perche")
        assertTrue(typed.isNotEmpty())
        assertEquals("perche", typed.last())
    }

    @Test
    fun aScannedDocumentGetsTheSpecMessage() {
        val message = mutableStateOf(state(pagesWithText = 0))
        composeRule.setContent { PdfToolkitTheme { SearchNotice(message.value) } }
        composeRule.onNodeWithText("Questo documento non contiene testo ricercabile. Potrebbe essere una scansione.").assertIsDisplayed()
        message.value = state(pagesWithText = 3)
        composeRule.onNodeWithText("Questo documento non contiene testo ricercabile. Potrebbe essere una scansione.").assertDoesNotExist()
    }

    @Test
    fun noMessageWhileTheDocumentIsStillBeingRead() {
        composeRule.setContent { PdfToolkitTheme { SearchNotice(state(pagesWithText = 0, status = IndexStatus.RUNNING)) } }
        composeRule.onNodeWithText("Questo documento non contiene testo ricercabile. Potrebbe essere una scansione.").assertDoesNotExist()
    }

    @Test
    fun anUnreadableDocumentIsExplained() {
        composeRule.setContent { PdfToolkitTheme { SearchNotice(state(status = IndexStatus.FAILED)) } }
        composeRule.onNodeWithText("Impossibile leggere il testo del documento.").assertIsDisplayed()
    }
}
