package com.marcogn.pdftoolkit.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.marcogn.pdftoolkit.data.recents.RecentDocument
import com.marcogn.pdftoolkit.ui.recents.RecentItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.model.PdfTool
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
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val toolClicks = mutableListOf<PdfTool>()
    private var menuClicks = 0
    private var openClicks = 0

    private val recentClicks = mutableListOf<String>()
    private val recentRemovals = mutableListOf<String>()

    private fun recent(name: String, accessible: Boolean = true) = RecentItem(
        RecentDocument(
            uri = "content://docs/$name",
            displayName = name,
            sizeBytes = 1_000,
            pageCount = 3,
            lastPage = 0,
            lastOpenedAt = System.currentTimeMillis(),
            thumbnailPath = null,
        ),
        accessible,
    )

    private fun setHome(recents: List<RecentItem>? = emptyList()) {
        composeRule.setContent {
            PdfToolkitTheme {
                HomeScreen(
                    onMenuClick = { menuClicks++ },
                    onOpenPdfClick = { openClicks++ },
                    onToolClick = { toolClicks += it },
                    recents = recents,
                    onRecentClick = { recentClicks += it.document.uri },
                    onRecentRemove = { recentRemovals += it.document.uri },
                )
            }
        }
    }

    @Test
    fun recentOpensTheDocument() {
        setHome(listOf(recent("contratto.pdf")))
        composeRule.onNodeWithText("contratto.pdf").performClick()
        assertEquals(listOf("content://docs/contratto.pdf"), recentClicks)
    }

    @Test
    fun unavailableRecentSaysSoAndDoesNotOpen() {
        setHome(listOf(recent("perso.pdf", accessible = false)))
        composeRule.onNodeWithText("File non disponibile").assertIsDisplayed()
        composeRule.onNodeWithText("perso.pdf").performClick()
        composeRule.onNodeWithText("Il file non è più disponibile. Tienilo premuto per toglierlo dalla lista.").assertIsDisplayed()
        assertTrue(recentClicks.isEmpty())
    }

    @Test
    fun longPressOffersRemovalFromTheList() {
        setHome(listOf(recent("vecchio.pdf")))
        composeRule.onNodeWithText("vecchio.pdf").performTouchInput { longClick() }
        composeRule.onNodeWithText("Rimuovi dalla lista").performClick()
        assertEquals(listOf("content://docs/vecchio.pdf"), recentRemovals)
    }

    @Test
    fun emptyRecentsShowTheHint() {
        setHome(emptyList())
        composeRule.onNodeWithText("I PDF che apri compariranno qui.").assertIsDisplayed()
    }

    @Test
    fun availableToolNavigates() {
        setHome()
        composeRule.onNodeWithText("Unisci PDF").performClick()
        assertEquals(listOf(PdfTool.MERGE), toolClicks)
    }

    @Test
    fun comingSoonToolShowsSnackbarAndDoesNotNavigate() {
        setHome()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Scansiona"))
        composeRule.onNodeWithText("Scansiona").performClick()
        composeRule.onNodeWithText("Scansiona arriverà in una prossima versione.").assertIsDisplayed()
        assertTrue(toolClicks.isEmpty())
    }

    @Test
    fun openPdfAndMenuAreWired() {
        setHome()
        composeRule.onNodeWithText("Apri PDF").performClick()
        composeRule.onNodeWithContentDescription("Apri il menu").performClick()
        assertEquals(1, openClicks)
        assertEquals(1, menuClicks)
    }
}
