package com.marcogn.pdftoolkit.domain.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSessionTest {

    private fun EditSession.indices() = pages.map { (it as PageItem.FromPdf).pageIndex }

    @Test
    fun `starts unmodified with every page in order`() {
        val session = EditSession.of(4)
        assertEquals(listOf(0, 1, 2, 3), session.indices())
        assertFalse(session.isModified)
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
    }

    @Test
    fun `remove drops the pages and keeps the others in order`() {
        val session = EditSession.of(5).remove(setOf("p1", "p3"))
        assertEquals(listOf(0, 2, 4), session.indices())
        assertTrue(session.isModified)
    }

    @Test
    fun `the last page can't be removed`() {
        val session = EditSession.of(2)
        assertSame(session, session.remove(setOf("p0", "p1")))
        val one = session.remove(setOf("p0"))
        assertSame(one, one.remove(setOf("p1")))
    }

    @Test
    fun `remove of unknown ids does nothing`() {
        val session = EditSession.of(3)
        assertSame(session, session.remove(setOf("nope")))
    }

    @Test
    fun `move puts the page at the target index`() {
        assertEquals(listOf(1, 2, 0, 3), EditSession.of(4).move(0, 2).indices())
        assertEquals(listOf(3, 0, 1, 2), EditSession.of(4).move(3, 0).indices())
    }

    @Test
    fun `move to the same place or out of range does nothing`() {
        val session = EditSession.of(3)
        assertSame(session, session.move(1, 1))
        assertSame(session, session.move(-1, 1))
        assertSame(session, session.move(0, 3))
    }

    @Test
    fun `move to start and end`() {
        val session = EditSession.of(4)
        assertEquals(listOf(2, 0, 1, 3), session.moveToStart("p2").indices())
        assertEquals(listOf(1, 2, 3, 0), session.moveToEnd("p0").indices())
        assertSame(session, session.moveToStart("p0"))
        assertSame(session, session.moveToEnd("p3"))
    }

    @Test
    fun `rotation accumulates modulo a full turn, both ways`() {
        var session = EditSession.of(2).rotate(setOf("p0"), 90)
        assertEquals(90, session.pages[0].rotation)
        assertEquals(0, session.pages[1].rotation)
        session = session.rotate(setOf("p0"), 90).rotate(setOf("p0"), 90).rotate(setOf("p0"), 90)
        assertEquals(0, session.pages[0].rotation)
        assertEquals(270, EditSession.of(1).rotate(setOf("p0"), -90).pages[0].rotation)
    }

    @Test
    fun `a full turn or no matching page is not a change`() {
        val session = EditSession.of(2)
        assertSame(session, session.rotate(setOf("p0"), 360))
        assertSame(session, session.rotate(setOf("x"), 90))
    }

    @Test
    fun `undo and redo walk through the operations`() {
        val s0 = EditSession.of(4)
        val s1 = s0.remove(setOf("p0"))
        val s2 = s1.rotate(setOf("p1"), 90)
        val s3 = s2.move(0, 2)

        val back = s3.undo()
        assertEquals(s2.pages, back.pages)
        assertTrue(back.canRedo)
        assertEquals(s1.pages, back.undo().pages)
        assertEquals(s0.pages, back.undo().undo().pages)
        assertFalse(back.undo().undo().canUndo)
        assertEquals(s3.pages, back.undo().undo().redo().redo().redo().pages)
    }

    @Test
    fun `a new operation clears redo`() {
        val session = EditSession.of(3).remove(setOf("p0")).undo()
        assertTrue(session.canRedo)
        assertFalse(session.rotate(setOf("p1"), 90).canRedo)
    }

    @Test
    fun `undoing everything leaves nothing to save`() {
        val session = EditSession.of(3).move(0, 2).undo()
        assertFalse(session.isModified)
    }

    @Test
    fun `moving a page away and back is not a modification`() {
        assertFalse(EditSession.of(3).move(0, 2).move(2, 0).isModified)
    }

    @Test
    fun `history is bounded`() {
        var session = EditSession.of(2)
        repeat(150) { session = session.rotate(setOf("p0"), 90) }
        var steps = 0
        while (session.canUndo) {
            session = session.undo()
            steps++
        }
        assertEquals(100, steps)
    }

    @Test
    fun `ids stay with their page through reordering`() {
        val session = EditSession.of(3).move(0, 2)
        assertEquals(listOf("p1", "p2", "p0"), session.pages.map { it.id })
    }

    @Test
    fun `encode and decode keep order and rotation`() {
        val session = EditSession.of(5).remove(setOf("p1")).move(0, 2).rotate(setOf("p4"), 270)
        val restored = EditSession.decode(session.encode(), 5)
        assertNotNull(restored)
        assertEquals(session.pages, restored!!.pages)
        // Compared with the original document, not with the state at the time of encoding.
        assertTrue(restored.isModified)
        assertFalse(restored.canUndo)
    }

    @Test
    fun `decode rejects what doesn't belong to the document`() {
        assertNull(EditSession.decode("", 3))
        assertNull(EditSession.decode("P,p0,0,5,0", 3)) // page index out of range
        assertNull(EditSession.decode("P,p0,1,0,0", 3)) // unknown document
        assertNull(EditSession.decode("P,p0,0,0,45", 3)) // not a quarter turn
        assertNull(EditSession.decode("P,p0,0,0,0;P,p0,0,1,0", 3)) // repeated id
        assertNull(EditSession.decode("B,b0,0,10,0", 3)) // blank page with no width
        assertNull(EditSession.decode("I,i0,file%3A%2F%2Fx,SIDEWAYS,10,10,0", 3)) // unknown fit mode
        assertNull(EditSession.decode("garbage", 3))
    }

    private val blank = PageItem.Blank("b1", 200f, 300f)
    private val image = PageItem.FromImage("i1", "file:///cache/images/a,b;c", ImageFit.FIT_PAGE, 595.28f, 841.89f)

    @Test
    fun `insert puts the pages at the index, keeping their order, and is one undo step`() {
        val session = EditSession.of(3).insert(1, listOf(blank, image))
        assertEquals(listOf("p0", "b1", "i1", "p1", "p2"), session.pages.map { it.id })
        assertTrue(session.isModified)
        assertEquals(listOf("p0", "p1", "p2"), session.undo().pages.map { it.id })
    }

    @Test
    fun `insert at the ends`() {
        assertEquals(listOf("b1", "p0", "p1"), EditSession.of(2).insert(0, listOf(blank)).pages.map { it.id })
        assertEquals(listOf("p0", "p1", "b1"), EditSession.of(2).insert(2, listOf(blank)).pages.map { it.id })
    }

    @Test
    fun `insert ignores a bad index, nothing to add and ids already in use`() {
        val session = EditSession.of(2)
        assertSame(session, session.insert(3, listOf(blank)))
        assertSame(session, session.insert(-1, listOf(blank)))
        assertSame(session, session.insert(0, emptyList()))
        assertSame(session, session.insert(0, listOf(PageItem.Blank("p1", 1f, 1f))))
    }

    @Test
    fun `blank and image pages rotate and can be removed like any other`() {
        val session = EditSession.of(2).insert(1, listOf(blank, image)).rotate(setOf("b1", "i1"), 90)
        assertEquals(90, session.pages[1].rotation)
        assertEquals(90, session.pages[2].rotation)
        assertEquals(listOf("p0", "p1"), session.remove(setOf("b1", "i1")).pages.map { it.id })
    }

    @Test
    fun `encode and decode keep blank and image pages, even with separators in the URI`() {
        val session = EditSession.of(3).insert(1, listOf(blank, image)).rotate(setOf("i1"), 270).move(0, 3)
        val restored = EditSession.decode(session.encode(), 3)
        assertNotNull(restored)
        assertEquals(session.pages, restored!!.pages)
    }

    @Test
    fun `documents added later are listed, and decoded only if their page count is known`() {
        val added = DocRef(1)
        val session = EditSession.of(2).insert(2, listOf(PageItem.FromPdf("n1", added, 4), PageItem.FromPdf("n2", added, 0)))
        assertEquals(setOf(added), session.extraDocuments)
        assertEquals(session.pages, EditSession.decode(session.encode(), mapOf(DocRef.MAIN to 2, added to 5))!!.pages)
        assertNull(EditSession.decode(session.encode(), mapOf(DocRef.MAIN to 2, added to 4))) // page 4 is out of range
        assertNull(EditSession.decode(session.encode(), mapOf(DocRef.MAIN to 2))) // document 1 unknown
        assertTrue(EditSession.of(2).extraDocuments.isEmpty())
    }

    @Test
    fun `a merge session lists every document in order and always has something to save`() {
        val session = EditSession.ofDocuments(listOf(2, 3, 1))
        assertEquals(
            listOf(DocRef.MAIN to 0, DocRef.MAIN to 1, DocRef(1) to 0, DocRef(1) to 1, DocRef(1) to 2, DocRef(2) to 0),
            session.pages.map { (it as PageItem.FromPdf).let { page -> page.docRef to page.pageIndex } },
        )
        assertEquals(setOf(DocRef(1), DocRef(2)), session.extraDocuments)
        assertTrue(session.isModified)
        val restored = EditSession.decode(session.encode(), mapOf(DocRef.MAIN to 2, DocRef(1) to 3, DocRef(2) to 1))
        assertEquals(session.pages, restored!!.pages)
    }

    @Test
    fun `a single document merge session is the plain one`() {
        assertEquals(EditSession.of(3).pages, EditSession.ofDocuments(listOf(3)).pages)
        assertFalse(EditSession.ofDocuments(listOf(3)).isModified)
    }
}
