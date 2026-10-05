package com.marcogn.pdftoolkit.domain.edit

import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Annotation edits in the edit session (spec §7.4): one undo history with pages and fill. */
class EditSessionAnnotationTest {

    private val quad = Quad(UserPoint(1f, 12f), UserPoint(9f, 12f), UserPoint(1f, 2f), UserPoint(9f, 2f))
    private val highlight = NewAnnotation(
        id = "a1",
        pageId = "p1",
        shape = AnnotationShape.TextMarkup(MarkupKind.HIGHLIGHT, listOf(quad)),
        style = AnnotationStyle(AnnotationColor.YELLOW, 0.4f),
    )
    private val ink = NewAnnotation("a2", "p0", AnnotationShape.Ink(listOf(listOf(UserPoint(0f, 0f), UserPoint(5f, 5f))), 2f), AnnotationStyle(AnnotationColor.BLACK))
    private val foreign = AnnotationRef(docId = 0, pageIndex = 2, index = 3, fingerprint = "Highlight@1.0,2.0,3.0,4.0")

    @Test
    fun `an added annotation is a change, and undo takes it away`() {
        val session = EditSession.of(3).addAnnotation(highlight)
        assertEquals(listOf(highlight), session.annotations.added)
        assertTrue(session.isModified)
        val undone = session.undo()
        assertTrue(undone.annotations.isEmpty)
        assertFalse(undone.isModified)
        assertEquals(listOf(highlight), undone.redo().annotations.added)
    }

    @Test
    fun `annotations only go on pages of the session and ids are unique`() {
        val session = EditSession.of(2)
        assertSame(session, session.addAnnotation(highlight.copy(pageId = "nope")))
        val one = session.addAnnotation(highlight)
        assertSame(one, one.addAnnotation(highlight.copy(pageId = "p0")))
    }

    @Test
    fun `update and remove an added annotation`() {
        val session = EditSession.of(2).addAnnotation(highlight)
        val green = highlight.copy(style = AnnotationStyle(AnnotationColor(0f, 1f, 0f), 0.4f))
        val edited = session.updateAnnotation(green)
        assertEquals(listOf(green), edited.annotations.added)
        assertSame(edited, edited.updateAnnotation(green))
        assertSame(edited, edited.removeAnnotation("unknown"))
        assertTrue(edited.removeAnnotation("a1").annotations.added.isEmpty())
        assertEquals(listOf(highlight), edited.undo().annotations.added)
    }

    @Test
    fun `removing an annotation of the file is a change, once`() {
        val session = EditSession.of(3).removeExistingAnnotation(foreign)
        assertEquals(setOf(foreign), session.annotations.removed)
        assertTrue(session.isModified)
        assertSame(session, session.removeExistingAnnotation(foreign))
        assertFalse(session.undo().isModified)
    }

    @Test
    fun `pages, fill and annotations share one history`() {
        val tick = MarkOverlay("m", "p0", OverlayBox(5f, 5f, 10f, 10f), MarkKind.CHECK)
        val session = EditSession.of(3).addAnnotation(highlight).addOverlay(tick).move(0, 2).removeExistingAnnotation(foreign)
        val back = session.undo().undo()
        assertEquals(listOf(highlight), back.annotations.added)
        assertEquals(listOf(tick), back.fill.overlays)
        assertEquals(listOf("p0", "p1", "p2"), back.pages.map { it.id })
        assertTrue(back.annotations.removed.isEmpty())
        // Pages change, annotations stay with the page id.
        assertEquals(listOf(highlight), session.annotations.addedOn("p1"))
    }

    @Test
    fun `annotation edits survive encode and decode`() {
        val session = EditSession.of(3).addAnnotation(highlight).addAnnotation(ink).removeExistingAnnotation(foreign)
        val restored = EditSession.decode(session.encode(), 3, session.encodeFill(), session.encodeAnnotations())!!
        assertEquals(session.annotations, restored.annotations)
        assertTrue(restored.isModified)
        assertEquals("", EditSession.of(3).encodeAnnotations())
        assertTrue(EditSession.decode(EditSession.of(3).encode(), 3, "", "")!!.annotations.isEmpty)
    }

    @Test
    fun `broken annotation data is refused, not half read`() {
        val pages = EditSession.of(3).encode()
        assertNull(EditSession.decode(pages, 3, "", "{not json"))
        // A text markup without quads fails its own check.
        assertNull(EditSession.decode(pages, 3, "", """{"added":[{"id":"x","pageId":"p0","shape":{"type":"markup","kind":"HIGHLIGHT","quads":[]},"style":{"color":{"red":1,"green":1,"blue":0}}}]}"""))
    }
}
