package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.FreehandOptions
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.ToolStyleSettings
import com.marcogn.pdftoolkit.ui.common.TransientHint
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The state and the Style menu of the annotation tools (spec §7.4). Italian locale set explicitly, see HomeScreenTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class AnnotateToolsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setBubble(state: AnnotatePaneState, tool: DrawerTool) {
        composeRule.setContent { PdfToolkitTheme { ToolBubble(tool, state, expanded = true, onDismiss = {}) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `the tools open on the highlighter and its hint comes from the tool`() {
        val state = AnnotatePaneState()
        assertEquals(AnnotateTool.HIGHLIGHT, state.tool)
        composeRule.setContent {
            PdfToolkitTheme { Box { TransientHint(state.tool.hint(), key = state.tool) } }
        }
        composeRule.onNodeWithText("Tieni premuto su una parola e, senza staccare il dito, trascina per estendere: al rilascio viene applicato").assertIsDisplayed()
    }

    @Test
    fun `the bubble of a highlight shows its colours, the one chosen selected`() {
        setBubble(AnnotatePaneState(), DrawerTool.HIGHLIGHT)
        composeRule.onNodeWithContentDescription("Giallo").assertIsSelected()
        composeRule.onNodeWithText("Evidenzia").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Spessore 2 pt").assertDoesNotExist()
    }

    @Test
    fun `lines get their own colours and the highlighter keeps its own`() {
        val state = AnnotatePaneState()
        setBubble(state, DrawerTool.UNDERLINE)
        composeRule.onNodeWithContentDescription("Rosso").assertIsSelected()
        composeRule.onNodeWithContentDescription("Nero").performClick()
        composeRule.waitForIdle()
        assertEquals(AnnotationColor.BLACK, state.colorFor(MarkupKind.UNDERLINE))
        assertEquals(AnnotationColor.YELLOW, state.colorFor(MarkupKind.HIGHLIGHT))
    }

    @Test
    fun `the partial eraser's bubble has its sizes and the stroke eraser's has none`() {
        val state = AnnotatePaneState(tool = AnnotateTool.ERASER)
        assertEquals(false, state.partialEraser)
        assertEquals(2f, state.eraserSize)
        setBubble(state, DrawerTool.ERASER_PARTIAL)
        composeRule.onNodeWithContentDescription("Spessore 2 pt").assertIsSelected()
        composeRule.onNodeWithContentDescription("Spessore 6 pt").performClick()
        composeRule.waitForIdle()
        assertEquals(6f, state.eraserSize)
    }

    @Test
    fun `the stroke eraser's bubble shows only its name`() {
        setBubble(AnnotatePaneState(tool = AnnotateTool.ERASER), DrawerTool.ERASER_STROKE)
        composeRule.onNodeWithText("Gomma").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Spessore 2 pt").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Nero").assertDoesNotExist()
    }

    @Test
    fun `arming a drawer tool sets the eraser's mode and the tool`() {
        val state = AnnotatePaneState()
        state.arm(DrawerTool.ERASER_PARTIAL)
        assertEquals(AnnotateTool.ERASER, state.tool)
        assertTrue(state.partialEraser)
        assertEquals(DrawerTool.ERASER_PARTIAL, state.tool.drawerTool(state.partialEraser))
        state.arm(DrawerTool.ERASER_STROKE)
        assertEquals(false, state.partialEraser)
        state.arm(DrawerTool.MARKER)
        assertEquals(FreehandKind.HIGHLIGHTER, state.tool.freehand)
        // "Fill and sign" is not a tool of this state.
        state.arm(DrawerTool.FILL)
        assertEquals(AnnotateTool.MARKER, state.tool)
    }

    @Test
    fun `the kept style round-trips without arming anything`() {
        val kept = ToolStyleSettings(
            highlightColor = 2, lineColor = 3, penColor = 4, penWidth = 1, markerColor = 2, markerWidth = 3,
            partialEraser = true, eraserWidth = 3, lastMarkup = DrawerTool.STRIKEOUT, lastBrush = DrawerTool.MARKER,
        )
        val state = AnnotatePaneState(tool = AnnotateTool.ERASER)
        state.restore(kept)
        assertEquals(kept, state.style())
        assertEquals(AnnotateTool.ERASER, state.tool)
        assertEquals(AnnotateTool.STRIKEOUT, state.markupTool)
        assertEquals(AnnotateTool.MARKER, state.brushTool)
    }

    @Test
    fun `restoring while a tool is armed keeps that tool as the last of its group`() {
        val state = AnnotatePaneState(tool = AnnotateTool.PEN)
        state.restore(ToolStyleSettings(lastBrush = DrawerTool.MARKER))
        assertEquals(AnnotateTool.PEN, state.tool)
        assertEquals(AnnotateTool.PEN, state.brushTool)
    }

    @Test
    fun `the partial eraser has its own hint`() {
        composeRule.setContent { PdfToolkitTheme { Box { TransientHint(AnnotateTool.ERASER.hint(partialEraser = true), key = 1) } } }
        composeRule.onNodeWithText("Passa il dito dove vuoi cancellare: va via solo quello che tocchi. Con due dita sposti e ingrandisci").assertIsDisplayed()
    }

    @Test
    fun `each family remembers the tool used last`() {
        val state = AnnotatePaneState()
        state.choose(AnnotateTool.STRIKEOUT)
        state.choose(AnnotateTool.MARKER)
        state.choose(AnnotateTool.ERASER)
        assertEquals(AnnotateTool.STRIKEOUT, state.markupTool)
        assertEquals(AnnotateTool.MARKER, state.brushTool)
        assertEquals(AnnotateTool.ERASER, state.tool)
    }

    @Test
    fun `the pen offers its colours and widths and remembers the choice`() {
        val state = AnnotatePaneState(tool = AnnotateTool.PEN)
        setBubble(state, DrawerTool.PEN)
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
