package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.ExistingAnnotation
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.fill.PageBox
import com.marcogn.pdftoolkit.domain.fill.UserRect
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.marcogn.pdftoolkit.pdf.text.LineRun
import com.marcogn.pdftoolkit.ui.annotate.AnnotationLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The viewer's edit session (plan V-a, ADR 0005): pages fixed, edits undoable, kept across process
 * death, "unsaved" until a save of exactly what is on screen; the page tools that feed it; the back
 * guard. Robolectric only for [SavedStateHandle].
 */
@RunWith(AndroidJUnit4::class)
class ViewerEditSessionTest {

    private val quad = Quad(UserPoint(1f, 12f), UserPoint(9f, 12f), UserPoint(1f, 2f), UserPoint(9f, 2f))

    private fun highlight(id: String, page: Int) = NewAnnotation(
        id = id,
        pageId = ViewerEditSession.pageId(page),
        shape = AnnotationShape.TextMarkup(MarkupKind.HIGHLIGHT, listOf(quad)),
        style = AnnotationStyle(AnnotationColor.YELLOW),
    )

    private val foreign = AnnotationRef(docId = 0, pageIndex = 1, index = 0, fingerprint = "Highlight@1.0,2.0,9.0,12.0")

    @Test
    fun `the session starts on the document's own pages, unchanged`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 3)
        assertEquals(EditSession.of(3).pages, editing.session.pages)
        assertFalse(editing.hasUnsavedChanges)
        assertEquals("p2", ViewerEditSession.pageId(2))
        assertEquals(2, ViewerEditSession.pageIndexOf("p2"))
        assertNull(ViewerEditSession.pageIndexOf("n1234"))
    }

    @Test
    fun `an edit is unsaved until undone`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 3)
        assertTrue(editing.addAnnotation(highlight("a1", 1)))
        assertTrue(editing.hasUnsavedChanges)
        assertTrue(editing.edits.value.session.canUndo)
        editing.undo()
        assertFalse(editing.hasUnsavedChanges)
        editing.redo()
        assertTrue(editing.hasUnsavedChanges)
        // A page that isn't in the document takes nothing.
        assertFalse(editing.addAnnotation(highlight("a2", 7)))
    }

    @Test
    fun `the edits survive process death, the history does not`() {
        val handle = SavedStateHandle()
        val editing = ViewerEditSession(handle, pageCount = 3)
        editing.addAnnotation(highlight("a1", 0))
        editing.removeExistingAnnotation(foreign)
        // A new process: the same saved state, a new session.
        val restored = ViewerEditSession(SavedStateHandle(mapOf("viewerFill" to handle.get<String>("viewerFill"), "viewerAnnotations" to handle.get<String>("viewerAnnotations"))), pageCount = 3)
        assertEquals(listOf(highlight("a1", 0)), restored.session.annotations.added)
        assertEquals(setOf(foreign), restored.session.annotations.removed)
        assertTrue(restored.hasUnsavedChanges)
        assertFalse(restored.session.canUndo)
    }

    @Test
    fun `saved state that doesn't fit the document is dropped`() {
        val handle = SavedStateHandle(mapOf("viewerAnnotations" to "{not json"))
        val editing = ViewerEditSession(handle, pageCount = 3)
        assertTrue(editing.session.annotations.isEmpty)
        assertFalse(editing.hasUnsavedChanges)
    }

    @Test
    fun `after a save nothing is unsaved until the next change`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 3)
        editing.addAnnotation(highlight("a1", 0))
        val key = editing.currentKey()
        editing.markSaved(key)
        assertFalse(editing.hasUnsavedChanges)
        editing.addAnnotation(highlight("a2", 2))
        assertTrue(editing.hasUnsavedChanges)
        // Undoing back to what was saved leaves nothing to save again.
        editing.undo()
        assertFalse(editing.hasUnsavedChanges)
    }

    @Test
    fun `a save started before a change doesn't hide the change`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 3)
        editing.addAnnotation(highlight("a1", 0))
        val key = editing.currentKey()
        // Drawn while the save runs.
        editing.addAnnotation(highlight("a2", 1))
        editing.markSaved(key)
        assertTrue(editing.hasUnsavedChanges)
    }

    @Test
    fun `discard goes back to the file`() {
        val handle = SavedStateHandle()
        val editing = ViewerEditSession(handle, pageCount = 3)
        editing.addAnnotation(highlight("a1", 0))
        editing.discard()
        assertTrue(editing.session.annotations.isEmpty)
        assertFalse(editing.hasUnsavedChanges)
        assertEquals("", handle.get<String>("viewerAnnotations"))
    }

    @Test
    fun `the save request carries the document's pages and the edits`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 3)
        editing.addAnnotation(highlight("a1", 0))
        val request = editing.saveRequest("content://doc", "content://copy", flattenForm = null, flattenInk = true)
        assertEquals("content://doc", request.sourceUri)
        assertEquals("content://copy", request.destinationUri)
        assertEquals(3, request.sourcePageCount)
        assertEquals(EditSession.of(3).encode(), request.pages)
        assertEquals(editing.session.encodeAnnotations(), request.annotations)
        // No ink, so nothing to make final; no signature, so the form stays editable.
        assertFalse(request.flattenInk)
        assertFalse(request.flattenForm)
        assertTrue(request.extraSources.isEmpty())
    }

    // --- Page tools ---

    /** Page 0 unturned, page 1 turned by its /Rotate 90; one foreign highlight on page 1. */
    private val document = DocumentAnnotations(
        pageBoxes = listOf(PageBox(0f, 0f, 200f, 300f, 0), PageBox(10f, 20f, 300f, 200f, 90)),
        pages = listOf(
            emptyList(),
            listOf(
                ExistingAnnotation(
                    ref = foreign,
                    subtype = "Highlight",
                    shape = AnnotationShape.TextMarkup(MarkupKind.HIGHLIGHT, listOf(Quad(UserPoint(50f, 120f), UserPoint(90f, 120f), UserPoint(50f, 100f), UserPoint(90f, 100f)))),
                    style = AnnotationStyle(AnnotationColor.YELLOW),
                    bounds = UserRect(50f, 100f, 90f, 120f),
                ),
            ),
        ),
    )

    @Test
    fun `a stroke becomes an Ink annotation of its page, in that page's user space`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 2)
        val tools = ViewerPageTools(editing, document)
        val drawn = listOf(Offset(20f, 30f), Offset(60f, 80f))
        val annotation = tools.inkAnnotation(1, FreehandStroke(drawn, emptyList(), 2f, 0.01f), FreehandKind.PEN, AnnotationColor.RED)!!
        assertEquals("p1", annotation.pageId)
        assertEquals(AnnotationColor.RED, annotation.style.color)
        val space = document.pageBoxes[1].toPageSpace()
        val back = (annotation.shape as AnnotationShape.Ink).strokes.single().map { space.userToDisplay.map(Offset(it.x, it.y)) }
        drawn.zip(back).forEach { (d, w) ->
            assertEquals(d.x, w.x, 0.01f)
            assertEquals(d.y, w.y, 0.01f)
        }
        // Entirely off the page (left of it): dropped.
        assertNull(tools.inkAnnotation(0, FreehandStroke(listOf(Offset(-40f, 10f), Offset(-20f, 50f)), emptyList(), 2f, 0.01f), FreehandKind.PEN, AnnotationColor.RED))
        // No page there.
        assertNull(tools.inkAnnotation(5, FreehandStroke(drawn, emptyList(), 2f, 0.01f), FreehandKind.PEN, AnnotationColor.RED))
    }

    @Test
    fun `the selection becomes a markup annotation of its page`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 2)
        val tools = ViewerPageTools(editing, document)
        val run = LineRun(origin = Offset(10f, 50f), end = Offset(80f, 50f), ascent = Offset(0f, -10f), descent = Offset(0f, 3f))
        val annotation = tools.markupAnnotation(0, listOf(run), MarkupKind.UNDERLINE, AnnotationColor.RED)
        assertNotNull(annotation)
        assertEquals("p0", annotation!!.pageId)
        assertEquals(MarkupKind.UNDERLINE, (annotation.shape as AnnotationShape.TextMarkup).kind)
        assertNull(tools.markupAnnotation(0, emptyList(), MarkupKind.HIGHLIGHT, AnnotationColor.YELLOW))
    }

    @Test
    fun `the eraser takes what is under the finger, added ones first, then the file's`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 2)
        val tools = ViewerPageTools(editing, document)
        val space = document.pageBoxes[1].toPageSpace()
        // The foreign highlight's middle, as displayed.
        val onHighlight = space.userToDisplay.map(Offset(70f, 110f))
        val drawn = tools.inkAnnotation(1, FreehandStroke(listOf(onHighlight, onHighlight + Offset(5f, 0f)), emptyList(), 2f, 0.01f), FreehandKind.PEN, AnnotationColor.BLACK)!!
        editing.addAnnotation(drawn)
        assertTrue(tools.erase(1, onHighlight, tolerancePt = 3f))
        assertTrue(editing.session.annotations.added.isEmpty())
        assertTrue(tools.erase(1, onHighlight, tolerancePt = 3f))
        assertEquals(setOf(foreign), editing.session.annotations.removed)
        // Nothing left there; and nothing at all on page 0.
        assertFalse(tools.erase(1, onHighlight, tolerancePt = 3f))
        assertFalse(tools.erase(0, Offset(10f, 10f), tolerancePt = 3f))
        // One undo step each.
        editing.undo()
        assertTrue(editing.session.annotations.removed.isEmpty())
    }

    @Test
    fun `the layer draws the file's annotations as the edits leave them`() {
        val editing = ViewerEditSession(SavedStateHandle(), pageCount = 2)
        assertEquals(1, AnnotationLayer.of(document, editing.session.annotations, ViewerEditSession::pageId).on(1)!!.annotations.size)
        editing.removeExistingAnnotation(foreign)
        editing.addAnnotation(highlight("a1", 0))
        val layer = AnnotationLayer.of(document, editing.session.annotations, ViewerEditSession::pageId)
        assertNull(layer.on(1))
        val page0 = layer.on(0)!!
        assertEquals(listOf(highlight("a1", 0).shape), page0.annotations.map { it.shape })
        assertEquals(document.pageBoxes[0].toPageSpace(), page0.space)
    }

    // --- Back ---

    @Test
    fun `back closes what is open first and asks before leaving unsaved edits`() {
        assertEquals(ViewerBackStep.CLOSE_SEARCH, viewerBackStep(searchOpen = true, selectionActive = true, toolArmed = true, hasUnsavedChanges = true, saving = false))
        assertEquals(ViewerBackStep.CLEAR_SELECTION, viewerBackStep(false, selectionActive = true, toolArmed = true, hasUnsavedChanges = true, saving = false))
        assertEquals(ViewerBackStep.PUT_TOOL_DOWN, viewerBackStep(false, false, toolArmed = true, hasUnsavedChanges = true, saving = false))
        assertEquals(ViewerBackStep.ASK_TO_SAVE, viewerBackStep(false, false, false, hasUnsavedChanges = true, saving = false))
        assertEquals(ViewerBackStep.LEAVE, viewerBackStep(false, false, false, hasUnsavedChanges = false, saving = false))
        // A save already running finishes in the background.
        assertEquals(ViewerBackStep.LEAVE, viewerBackStep(false, false, false, hasUnsavedChanges = true, saving = true))
    }
}
