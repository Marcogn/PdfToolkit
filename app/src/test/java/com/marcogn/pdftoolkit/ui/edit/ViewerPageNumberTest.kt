package com.marcogn.pdftoolkit.ui.edit

import com.marcogn.pdftoolkit.domain.edit.EditSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerPageNumberTest {

    private val session = EditSession.of(10)

    @Test
    fun `unknown page or no pages gives null`() {
        assertNull(viewerPageNumber(session.pages, -1))
        assertNull(viewerPageNumber(null, 3))
        assertNull(viewerPageNumber(session.pages, 10))
    }

    @Test
    fun `the page keeps its number until something before it is removed`() {
        assertEquals(4, viewerPageNumber(session.pages, 3))
        val removed = session.remove(setOf("p0", "p1"))
        assertEquals(2, viewerPageNumber(removed.pages, 3))
    }

    @Test
    fun `a removed page gives null`() {
        assertNull(viewerPageNumber(session.remove(setOf("p3")).pages, 3))
    }

    @Test
    fun `pages of another document do not count`() {
        val merged = EditSession.ofDocuments(listOf(2, 5))
        assertEquals(2, viewerPageNumber(merged.pages, 1))
    }
}
