package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.FreehandOptions
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.pdf.text.GlyphRange
import com.marcogn.pdftoolkit.pdf.text.PageText
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import com.marcogn.pdftoolkit.ui.common.TransientHint
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private var undone = 0
    private var redone = 0

    private fun setBar(state: AnnotatePaneState, selection: TextSelectionState = TextSelectionState(), side: Boolean = false) {
        composeRule.setContent {
            PdfToolkitTheme {
                val undoRedo = UndoRedo(canUndo = true, canRedo = false, onUndo = { undone++ }, onRedo = { redone++ })
                AnnotateToolBar(state, selection, undoRedo, side) { applied += it }
            }
        }
    }

    private fun openStyle() {
        composeRule.onNodeWithText("Stile").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `the pane opens on the highlighter and its hint comes from the tool`() {
        val state = AnnotatePaneState()
        assertEquals(AnnotateTool.HIGHLIGHT, state.tool)
        composeRule.setContent {
            PdfToolkitTheme { Box { TransientHint(state.tool.hint(), key = state.tool) } }
        }
        composeRule.onNodeWithText("Tieni premuto su una parola, poi trascina le maniglie").assertIsDisplayed()
    }

    @Test
    fun `colours stay out of the way until the style button is tapped`() {
        val state = AnnotatePaneState()
        setBar(state)
        composeRule.onNodeWithContentDescription("Giallo").assertDoesNotExist()
        openStyle()
        composeRule.onNodeWithContentDescription("Giallo").assertIsSelected()
    }

    @Test
    fun `choosing a tool arms it, and lines get their own colours`() {
        val state = AnnotatePaneState()
        setBar(state)
        composeRule.onNodeWithText("Sottolinea").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotateTool.UNDERLINE, state.tool)
        openStyle()
        composeRule.onNodeWithContentDescription("Rosso").assertIsSelected()
        composeRule.onNodeWithContentDescription("Nero").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotationColor.BLACK, state.colorFor(MarkupKind.UNDERLINE))
        // The highlighter keeps its own colour.
        assertEquals(AnnotationColor.YELLOW, state.colorFor(MarkupKind.HIGHLIGHT))
    }

    @Test
    fun `the eraser has no style and says how it works`() {
        val state = AnnotatePaneState(tool = AnnotateTool.ERASER)
        setBar(state)
        composeRule.onNodeWithText("Stile").assertDoesNotExist()
        assertEquals(R.string.annotate_hint_erase, hintRes(state.tool))
    }

    private fun hintRes(tool: AnnotateTool): Int = when {
        tool.kind != null -> R.string.annotate_hint_select
        tool.freehand != null -> R.string.annotate_hint_draw
        else -> R.string.annotate_hint_erase
    }

    @Test
    fun `undo and redo are in the bar and redo waits for something to redo`() {
        setBar(AnnotatePaneState())
        composeRule.onNodeWithContentDescription("Annulla").performClick()
        composeRule.onNodeWithContentDescription("Ripeti").assertIsNotEnabled()
        assertEquals(1, undone)
        assertEquals(0, redone)
    }

    @Test
    fun `the side rail offers the same tools`() {
        val state = AnnotatePaneState()
        setBar(state, side = true)
        composeRule.onNodeWithText("Penna").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotateTool.PEN, state.tool)
        composeRule.onNodeWithContentDescription("Annulla").assertIsDisplayed()
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

    @Test
    fun `the pen offers its colours and widths and remembers the choice`() {
        val state = AnnotatePaneState(tool = AnnotateTool.PEN)
        setBar(state)
        openStyle()
        composeRule.onNodeWithContentDescription("Nero").assertIsSelected()
        composeRule.onNodeWithContentDescription("Spessore 2 pt").assertIsSelected()
        composeRule.onNodeWithContentDescription("Rosso").performClick()
        composeRule.onNodeWithContentDescription("Spessore 6 pt").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotationColor.RED, state.colorFor(FreehandKind.PEN))
        assertEquals(6f, state.widthFor(FreehandKind.PEN))
        // The marker keeps its own.
        assertEquals(AnnotationColor.YELLOW, state.colorFor(FreehandKind.HIGHLIGHTER))
        assertEquals(12f, state.widthFor(FreehandKind.HIGHLIGHTER))
    }

    @Test
    fun `every freehand default is one of its options`() {
        for (kind in FreehandKind.entries) {
            assertTrue(FreehandOptions.defaultColorIndex(kind) >= 0)
            assertTrue(FreehandOptions.defaultWidthIndex(kind) >= 0)
        }
    }
}
