package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.ui.annotate.AnnotatePaneState
import com.marcogn.pdftoolkit.ui.annotate.AnnotateTool
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tools bar of the viewer (plan V-b). Italian locale set explicitly, see HomeScreenTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class ViewerToolBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val groups = mutableListOf<ViewerToolGroup>()
    private var fills = 0
    private var pages = 0
    private var undone = 0

    private fun setBar(
        state: AnnotatePaneState = AnnotatePaneState(),
        armed: Boolean = false,
        undoRedo: UndoRedo? = null,
        side: Boolean = false,
    ) {
        composeRule.setContent {
            PdfToolkitTheme {
                ViewerToolBar(
                    state = state,
                    armed = armed,
                    editable = true,
                    undoRedo = undoRedo,
                    side = side,
                    onGroup = { groups += it },
                    onFillAndSign = { fills++ },
                    onPages = { pages++ },
                )
            }
        }
    }

    @Test
    fun `the bar offers the page tools and the two screens that open the edit screen`() {
        setBar()
        composeRule.onNodeWithText("Evidenzia").performClick()
        composeRule.onNodeWithText("Penna").performClick()
        composeRule.onNodeWithText("Gomma").performClick()
        composeRule.onNodeWithText("Compila").performClick()
        composeRule.onNodeWithText("Pagine").performClick()
        assertEquals(listOf(ViewerToolGroup.MARKUP, ViewerToolGroup.DRAW, ViewerToolGroup.ERASER), groups)
        assertEquals(1, fills)
        assertEquals(1, pages)
    }

    @Test
    fun `a button is named after the tool of its family that a tap arms`() {
        val state = AnnotatePaneState()
        state.choose(AnnotateTool.UNDERLINE)
        state.choose(AnnotateTool.MARKER)
        state.choose(AnnotateTool.ERASER)
        setBar(state, armed = true)
        composeRule.onNodeWithText("Sottolinea").assertIsDisplayed()
        composeRule.onNodeWithText("Pennarello").assertIsDisplayed()
    }

    @Test
    fun `style appears only while a tool is armed`() {
        setBar(armed = false)
        composeRule.onNodeWithText("Stile").assertDoesNotExist()
    }

    @Test
    fun `style shows for an armed tool and not for the eraser`() {
        setBar(AnnotatePaneState(), armed = true)
        composeRule.onNodeWithText("Stile").assertIsDisplayed()
    }

    @Test
    fun `undo and redo wait for something to undo`() {
        setBar(undoRedo = UndoRedo(canUndo = true, canRedo = false, onUndo = { undone++ }, onRedo = {}))
        composeRule.onNodeWithContentDescription("Annulla").performClick()
        composeRule.onNodeWithContentDescription("Ripeti").assertIsNotEnabled()
        assertEquals(1, undone)
    }

    @Test
    fun `no undo and redo without changes`() {
        setBar(undoRedo = null)
        composeRule.onNodeWithContentDescription("Annulla").assertDoesNotExist()
    }

    @Test
    fun `the side rail offers the same tools`() {
        setBar(side = true)
        composeRule.onNodeWithText("Penna").performClick()
        assertEquals(listOf(ViewerToolGroup.DRAW), groups)
    }

    @Test
    fun `the family of a tool`() {
        assertEquals(ViewerToolGroup.MARKUP, AnnotateTool.STRIKEOUT.group())
        assertEquals(ViewerToolGroup.DRAW, AnnotateTool.MARKER.group())
        assertEquals(ViewerToolGroup.ERASER, AnnotateTool.ERASER.group())
        assertEquals(AnnotateTool.ERASER, AnnotatePaneState().toolOf(ViewerToolGroup.ERASER))
    }
}
