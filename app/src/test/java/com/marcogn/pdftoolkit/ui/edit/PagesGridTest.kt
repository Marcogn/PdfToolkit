package com.marcogn.pdftoolkit.ui.edit

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The page grid of "Organize pages" (plan U4). Italian locale set explicitly, see HomeScreenTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class PagesGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pages = List(3) { PageItem.Blank("b$it", 595f, 842f) }
    private val tapped = mutableListOf<String>()

    private fun setGrid(mode: PagesMode) {
        composeRule.setContent {
            PdfToolkitTheme {
                PagesGrid(
                    pages = pages,
                    sources = emptyMap(),
                    imageThumbnail = { null },
                    mode = mode,
                    selection = emptySet(),
                    onTap = { tapped += it.id },
                    onLongPress = {},
                    onCommitMove = { _, _ -> },
                    actions = PageActions({}, {}, {}, {}, {}),
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    @Test
    fun `every page has a handle to drag it, no long press needed`() {
        setGrid(PagesMode.ORGANIZE)
        for (number in 1..3) {
            composeRule.onNodeWithContentDescription("Trascina la pagina $number per spostarla").assertIsDisplayed()
        }
    }

    @Test
    fun `a tap on a page selects it`() {
        setGrid(PagesMode.ORGANIZE)
        composeRule.onNodeWithContentDescription("Pagina 2", substring = true).performClick()
        assertEquals(listOf("b1"), tapped)
    }

    @Test
    fun `the read-only hub grid has neither handles nor taps`() {
        setGrid(PagesMode.VIEW)
        composeRule.onNodeWithContentDescription("Trascina la pagina 1 per spostarla").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pagina 2", substring = true).performClick()
        assertEquals(emptyList<String>(), tapped)
    }
}
