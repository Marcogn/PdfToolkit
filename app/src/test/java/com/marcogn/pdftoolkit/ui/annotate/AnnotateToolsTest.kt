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

    private fun setStyle(state: AnnotatePaneState) {
        composeRule.setContent { PdfToolkitTheme { StyleButton(state) } }
    }

    private fun openStyle() {
        composeRule.onNodeWithText("Stile").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `the tools open on the highlighter and its hint comes from the tool`() {
        val state = AnnotatePaneState()
        assertEquals(AnnotateTool.HIGHLIGHT, state.tool)
        composeRule.setContent {
            PdfToolkitTheme { Box { TransientHint(state.tool.hint(), key = state.tool) } }
        }
        composeRule.onNodeWithText("Tieni premuto su una parola, poi trascina le maniglie").assertIsDisplayed()
    }

    @Test
    fun `colours stay out of the way until the style button is tapped`() {
        setStyle(AnnotatePaneState())
        composeRule.onNodeWithContentDescription("Giallo").assertDoesNotExist()
        openStyle()
        composeRule.onNodeWithContentDescription("Giallo").assertIsSelected()
    }

    @Test
    fun `the style menu picks the other tool of the family and lines get their own colours`() {
        val state = AnnotatePaneState()
        setStyle(state)
        openStyle()
        composeRule.onNodeWithContentDescription("Sottolinea").performClick()
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
    fun `the eraser has no style`() {
        setStyle(AnnotatePaneState(tool = AnnotateTool.ERASER))
        composeRule.onNodeWithText("Stile").assertDoesNotExist()
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
        setStyle(state)
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
