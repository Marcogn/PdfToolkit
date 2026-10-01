package com.marcogn.pdftoolkit.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.model.PdfTool
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Locale italiana esplicita: Robolectric parte in inglese e caricherebbe `values-en/`. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val toolClicks = mutableListOf<PdfTool>()
    private var menuClicks = 0
    private var openClicks = 0

    private fun setHome() {
        composeRule.setContent {
            PdfToolkitTheme {
                HomeScreen(
                    onMenuClick = { menuClicks++ },
                    onOpenPdfClick = { openClicks++ },
                    onToolClick = { toolClicks += it },
                )
            }
        }
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
