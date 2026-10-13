package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.QuickSlots
import com.marcogn.pdftoolkit.ui.annotate.AnnotatePaneState
import com.marcogn.pdftoolkit.ui.annotate.AnnotateTool
import com.marcogn.pdftoolkit.ui.annotate.arm
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.fill.FillTool
import com.marcogn.pdftoolkit.ui.fill.FillToolButtons
import com.marcogn.pdftoolkit.ui.fill.FillToolsState
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tool bar and the drawer of the viewer (plans V-b, V-c, T-b). Italian locale set explicitly, see HomeScreenTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class ViewerToolBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val state = AnnotatePaneState()
    private val drag = ToolDragState()
    private val geometry = ToolBarGeometry()
    private val fillState = FillToolsState()

    private var slots by mutableStateOf(QuickSlots())
    private var armed by mutableStateOf<DrawerTool?>(null)
    private var drawerOpen by mutableStateOf(false)
    private var canUndo by mutableStateOf(false)
    private var filling by mutableStateOf(false)
    private var origin by mutableStateOf<Offset?>(null)
    private var pinHint by mutableStateOf(true)
    private var tutorial by mutableStateOf(false)
    private var tutorialDone = 0
    private var reads = 0
    private var fillsDown = 0
    private var pages = 0
    private var undone = 0
    private val arms = mutableListOf<DrawerTool>()

    private fun setBar(side: Boolean = false, editable: Boolean = true) {
        val callbacks = ToolBarCallbacks(geometry, drag) { tool, from, to, overBar -> slots = slots.drop(tool, from, to, overBar) }
        val onArm: (DrawerTool) -> Unit = {
            arms += it
            armed = it
            drawerOpen = false
            state.arm(it)
        }
        composeRule.setContent {
            PdfToolkitTheme {
                Box(Modifier.fillMaxSize()) {
                    ToolDrawerHost(
                        open = drawerOpen,
                        onClose = { drawerOpen = false },
                        origin = origin,
                        side = side,
                        state = state,
                        armed = armed,
                        editable = editable,
                        showPinHint = pinHint && !slots.anyFilled,
                        showTutorial = tutorial,
                        onTutorialDone = { tutorialDone++; tutorial = false },
                        callbacks = callbacks,
                        onArm = onArm,
                        onRead = { reads++; armed = null; drawerOpen = false },
                    )
                    Box(Modifier.align(if (side) Alignment.CenterEnd else Alignment.BottomCenter)) {
                        ViewerToolBar(
                            state = state,
                            armed = armed,
                            slots = slots,
                            editable = editable,
                            drawerOpen = drawerOpen,
                            undoRedo = UndoRedo(canUndo, false, { undone++ }, {}),
                            side = side,
                            callbacks = callbacks,
                            onDrawer = { drawerOpen = !drawerOpen },
                            onArm = onArm,
                            filling = filling,
                            onFillDown = { fillsDown++; filling = false },
                            onPages = { pages++ },
                            onOrigin = { origin = it },
                            fillTools = { FillToolButtons(fillState, selected = null, onDelete = {}, onSignature = {}, onPickSignature = {}) },
                        )
                    }
                    ToolDragGhost(drag)
                }
            }
        }
    }

    private fun centerOf(description: String) = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center
    private fun center(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    private fun openDrawer() {
        composeRule.onNodeWithText("Strumenti").performClick()
        composeRule.waitForIdle()
    }

    /** Hold the tool at [from] past the long-press time, move to [to] and let go. */
    private fun holdAndDragTo(from: Offset, to: Offset) {
        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().performTouchInput { down(from) }
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MS)
        composeRule.onRoot().performTouchInput {
            moveTo(from + (to - from) * 0.5f)
            moveTo(to)
        }
        composeRule.mainClock.advanceTimeBy(50)
        composeRule.onRoot().performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }


    @Test
    fun `the bar has fixed places and no Style button`() {
        setBar()
        composeRule.onNodeWithText("Strumenti").assertIsDisplayed()
        composeRule.onNodeWithText("Pagine").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Annulla").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Ripeti").assertIsNotEnabled()
        composeRule.onNodeWithText("Stile").assertDoesNotExist()
    }

    @Test
    fun `arming a tool or having something to undo moves no button of the bar`() {
        setBar()
        val tags = listOf("quickSlot0", "quickSlot1")
        fun snapshot() = tags.map { composeRule.onNodeWithTag(it).getBoundsInRoot() } +
            listOf("Annulla", "Ripeti").map { composeRule.onNodeWithContentDescription(it).getBoundsInRoot() } +
            composeRule.onNodeWithText("Strumenti").getBoundsInRoot() + composeRule.onNodeWithText("Pagine").getBoundsInRoot()
        val before = snapshot()
        openDrawer()
        composeRule.onNodeWithContentDescription("Penna").performClick()
        composeRule.waitForIdle()
        canUndo = true
        composeRule.waitForIdle()
        assertEquals(before, snapshot())
    }

    @Test
    fun `an armed tool that is not pinned shows on the Tools button`() {
        setBar()
        openDrawer()
        composeRule.onNodeWithContentDescription("Penna").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(DrawerTool.PEN), arms)
        assertEquals(AnnotateTool.PEN, state.tool)
        composeRule.onNodeWithText("Strumenti").assertIsDisplayed()
    }

    @Test
    fun `the drawer shows every tool and Read`() {
        setBar()
        openDrawer()
        for (name in listOf("Evidenzia", "Sottolinea", "Barra", "Penna", "Pennarello", "Gomma", "Gomma parziale", "Compila", "Leggi, senza strumento")) {
            composeRule.onNodeWithContentDescription(name).assertExists()
        }
    }

    @Test
    fun `a tap on a tool in the drawer arms it and closes the drawer`() {
        setBar()
        openDrawer()
        composeRule.onNodeWithContentDescription("Pennarello").performClick()
        composeRule.waitForIdle()
        assertFalse(drawerOpen)
        assertEquals(AnnotateTool.MARKER, state.tool)
        // The marker draws as a highlighter.
        assertEquals(com.marcogn.pdftoolkit.domain.annotate.FreehandKind.HIGHLIGHTER, state.tool.freehand)
    }

    @Test
    fun `Read puts the tool down`() {
        setBar()
        openDrawer()
        composeRule.onNodeWithContentDescription("Leggi, senza strumento").performClick()
        composeRule.waitForIdle()
        assertEquals(1, reads)
        assertEquals(null, armed)
    }

    @Test
    fun `a long press on the Tools button does nothing`() {
        setBar()
        composeRule.onNodeWithText("Strumenti").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertFalse(drawerOpen)
    }

    @Test
    fun `the hint shows while a slot is empty and is gone once one is filled`() {
        setBar()
        openDrawer()
        composeRule.onNodeWithText("Tieni premuto uno strumento e trascinalo su un riquadro tratteggiato della barra per averlo sempre a portata di mano").assertIsDisplayed()
        slots = QuickSlots(listOf(DrawerTool.PEN, null))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tieni premuto uno strumento e trascinalo su un riquadro tratteggiato della barra per averlo sempre a portata di mano").assertDoesNotExist()
    }

    @Test
    fun `holding a tool in the drawer and moving it onto an empty slot pins it`() {
        setBar()
        openDrawer()
        holdAndDragTo(centerOf("Penna"), center("quickSlot1"))
        assertEquals(listOf(null, DrawerTool.PEN), slots.tools)
        assertEquals("a drag is not a tap", emptyList<DrawerTool>(), arms)
        assertFalse(drag.active)
    }

    @Test
    fun `a drop anywhere else pins nothing`() {
        setBar()
        openDrawer()
        holdAndDragTo(centerOf("Penna"), Offset(40f, 200f))
        assertEquals(QuickSlots(), slots)
        assertEquals(emptyList<DrawerTool>(), arms)
    }

    @Test
    fun `a drop on a full slot replaces its tool`() {
        slots = QuickSlots(listOf(DrawerTool.PEN, DrawerTool.MARKER))
        setBar()
        openDrawer()
        holdAndDragTo(centerOf("Evidenzia"), center("quickSlot0"))
        assertEquals(listOf(DrawerTool.HIGHLIGHT, DrawerTool.MARKER), slots.tools)
    }

    @Test
    fun `dragging a quick tool off the bar unpins it`() {
        slots = QuickSlots(listOf(DrawerTool.PEN, DrawerTool.MARKER))
        setBar()
        holdAndDragTo(center("quickSlot0"), Offset(200f, 300f))
        assertEquals(listOf(null, DrawerTool.MARKER), slots.tools)
    }

    @Test
    fun `holding a tool still opens its bubble without arming it`() {
        setBar()
        openDrawer()
        val at = centerOf("Penna")
        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().performTouchInput { down(at) }
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MS)
        composeRule.onRoot().performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Nero").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Spessore 6 pt").assertIsDisplayed()
        assertEquals(emptyList<DrawerTool>(), arms)
        // A choice in the bubble reaches the state of the tool.
        composeRule.onNodeWithContentDescription("Rosso").performClick()
        assertEquals(com.marcogn.pdftoolkit.domain.annotate.AnnotationColor.RED, state.colorFor(com.marcogn.pdftoolkit.domain.annotate.FreehandKind.PEN))
    }

    @Test
    fun `a quick slot arms on a tap and opens the bubble on a tap while armed`() {
        slots = QuickSlots(listOf(DrawerTool.PEN, null))
        setBar()
        composeRule.onNodeWithContentDescription("Strumento rapido: Penna").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(DrawerTool.PEN), arms)
        composeRule.onNodeWithContentDescription("Nero").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Strumento rapido: Penna").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Nero").assertIsDisplayed()
        assertEquals("the second tap does not arm again", listOf(DrawerTool.PEN), arms)
    }

    @Test
    fun `a tap on an empty slot opens the drawer`() {
        setBar()
        composeRule.onNodeWithTag("quickSlot0").performClick()
        composeRule.waitForIdle()
        assertTrue(drawerOpen)
    }

    @Test
    fun `the tutorial shows over the drawer, steps through and ends once`() {
        tutorial = true
        setBar()
        openDrawer()
        composeRule.onNodeWithText("Passo 1 di 3").assertIsDisplayed()
        composeRule.onNodeWithText("Avanti").performClick()
        composeRule.onNodeWithText("Passo 2 di 3").assertIsDisplayed()
        composeRule.onNodeWithText("Avanti").performClick()
        composeRule.onNodeWithText("Passo 3 di 3").assertIsDisplayed()
        composeRule.onNodeWithText("Ho capito").performClick()
        composeRule.waitForIdle()
        assertEquals(1, tutorialDone)
        composeRule.onNodeWithText("Passo 3 di 3").assertDoesNotExist()
    }

    @Test
    fun `the tutorial can be skipped`() {
        tutorial = true
        setBar()
        openDrawer()
        composeRule.onNodeWithText("Salta").performClick()
        assertEquals(1, tutorialDone)
    }

    @Test
    fun `a drop on a slot that is not shown pins nothing`() {
        setBar()
        openDrawer()
        filling = true
        composeRule.waitForIdle()
        // The slots left the screen with Fill and sign: their old places are no longer targets.
        assertEquals(null, geometry.slotAt(Offset(1f, 1f)))
        assertEquals(null, geometry.slots[0])
        assertEquals(null, geometry.slots[1])
    }

    @Test
    fun `Back closes the drawer`() {
        setBar()
        openDrawer()
        assertTrue(drawerOpen)
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(drawerOpen)
    }

    @Test
    fun `undo and redo are always there and undo acts`() {
        canUndo = true
        setBar()
        composeRule.onNodeWithContentDescription("Annulla").performClick()
        composeRule.onNodeWithContentDescription("Ripeti").assertIsNotEnabled()
        assertEquals(1, undone)
    }

    @Test
    fun `Pages acts`() {
        setBar()
        composeRule.onNodeWithText("Pagine").performClick()
        assertEquals(1, pages)
    }

    @Test
    fun `the side rail offers the same places`() {
        slots = QuickSlots(listOf(DrawerTool.PEN, null))
        setBar(side = true)
        composeRule.onNodeWithContentDescription("Strumento rapido: Penna").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(DrawerTool.PEN), arms)
        openDrawer()
        composeRule.onNodeWithContentDescription("Leggi, senza strumento").assertIsDisplayed()
    }

    @Test
    fun `while filling the bar holds the fill tools and Fill puts them down`() {
        filling = true
        armed = DrawerTool.FILL
        setBar()
        composeRule.onNodeWithText("Pagine").assertDoesNotExist()
        composeRule.onNodeWithText("Strumenti").assertIsDisplayed()
        composeRule.onNodeWithText("Data").performClick()
        assertEquals(FillTool.DATE, fillState.tool)
        composeRule.onNodeWithText("Data").performClick()
        assertEquals(null, fillState.tool)
        composeRule.onNodeWithText("Compila").performClick()
        assertEquals(1, fillsDown)
    }

    @Test
    fun `tools are dimmed on a protected document and a tap still reaches the screen to explain`() {
        slots = QuickSlots(listOf(DrawerTool.PEN, null))
        setBar(editable = false)
        composeRule.onNodeWithContentDescription("Strumento rapido: Penna").performClick()
        assertEquals(listOf(DrawerTool.PEN), arms)
    }

    private companion object {
        const val LONG_PRESS_MS = 700L
    }
}
