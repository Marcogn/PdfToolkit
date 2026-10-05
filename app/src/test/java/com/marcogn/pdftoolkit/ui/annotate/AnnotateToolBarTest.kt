package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.pdf.text.GlyphRange
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tool bar of the Annotate pane (spec §7.4, phase 7b). Italian locale set explicitly, see HomeScreenTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class AnnotateToolBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val applied = mutableListOf<MarkupKind>()

    private fun setBar(state: AnnotatePaneState, selection: TextSelectionState = TextSelectionState()) {
        composeRule.setContent { PdfToolkitTheme { AnnotateToolBar(state, selection) { applied += it } } }
    }

    @Test
    fun `the pane opens on the highlighter with its hint and colours`() {
        val state = AnnotatePaneState()
        setBar(state)
        composeRule.onNodeWithText("Tieni premuto su una parola, poi trascina le maniglie").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Giallo").assertIsSelected()
        assertEquals(AnnotateTool.HIGHLIGHT, state.tool)
    }

    @Test
    fun `choosing a tool arms it, and lines get their own colours`() {
        val state = AnnotatePaneState()
        setBar(state)
        composeRule.onNodeWithText("Sottolinea").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotateTool.UNDERLINE, state.tool)
        composeRule.onNodeWithContentDescription("Rosso").assertIsSelected()
        composeRule.onNodeWithContentDescription("Nero").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotationColor.BLACK, state.colorFor(MarkupKind.UNDERLINE))
        // The highlighter keeps its own colour.
        assertEquals(AnnotationColor.YELLOW, state.colorFor(MarkupKind.HIGHLIGHT))
    }

    @Test
    fun `the eraser has no colours and says how it works`() {
        val state = AnnotatePaneState(tool = AnnotateTool.ERASER)
        setBar(state)
        composeRule.onNodeWithText("Tocca un'annotazione per rimuoverla").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Giallo").assertDoesNotExist()
    }

    @Test
    fun `with a selection the bar offers to apply the armed tool`() {
        val state = AnnotatePaneState(tool = AnnotateTool.STRIKEOUT)
        val selection = TextSelectionState().apply {
            select("0", TextSelection(PageText(0, emptyList())), GlyphRange(0, 1))
        }
        setBar(state, selection)
        composeRule.onNodeWithText("Barra il testo").performClick()
        assertEquals(listOf(MarkupKind.STRIKEOUT), applied)
    }

    @Test
    fun `cancel drops the selection`() {
        val selection = TextSelectionState().apply { select("0", TextSelection(PageText(0, emptyList())), GlyphRange(0, 1)) }
        setBar(AnnotatePaneState(), selection)
        composeRule.onNodeWithText("Annulla").performClick()
        composeRule.waitForIdle()
        assertFalse(selection.isActive)
    }

}
