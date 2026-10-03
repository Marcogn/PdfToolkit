package com.marcogn.pdftoolkit.pdf.text

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSearchTest {

    private fun glyphs(text: String): List<TextGlyph> = text.mapIndexed { i, c ->
        TextGlyph(c.toString(), Offset(i * 5f, 50f), Offset(i * 5f + 5f, 50f), Offset(0f, -8f), Offset(0f, 2f))
    }

    /** Pages as single lines of text; [gate] holds the extraction after page [gateAfter] until it is completed. */
    private class FakeExtractor(
        private val pages: List<String>,
        private val gateAfter: Int = -1,
        private val gate: CompletableDeferred<Unit> = CompletableDeferred(),
        private val fail: Boolean = false,
        private val glyphs: (String) -> List<TextGlyph>,
    ) : PdfTextExtractor {
        var runs = 0
        override fun pages(open: () -> InputStream?, password: String?): Flow<PageText> = flow {
            runs++
            if (fail) throw TextExtractionException(null)
            pages.forEachIndexed { index, text ->
                emit(PageText(index, glyphs(text)))
                if (index == gateAfter) gate.await()
            }
        }
    }

    // Not backgroundScope: advanceUntilIdle() doesn't wait for background work. Not a child of the
    // test either, so a test may leave the extraction waiting at its gate.
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun TestScope.search(extractor: PdfTextExtractor, pageCount: Int) = DocumentSearch(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it },
        extractor = extractor,
        open = { null },
        password = null,
        pageCount = pageCount,
        workDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `results appear while indexing is still going, in document order`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor(listOf("uno due", "tre", "due ancora", "quattro due"), gateAfter = 1, gate = gate, glyphs = ::glyphs)
        val search = search(extractor, 4)
        search.start()
        search.setQuery("DUE")
        advanceTimeBy(DocumentSearch.DEBOUNCE_MS + 1)
        runCurrent()

        // Pages 0 and 1 are in; the extraction waits for the rest.
        var state = search.state.value
        assertEquals(IndexStatus.RUNNING, state.status)
        assertTrue(state.isIndexing)
        assertEquals(2, state.indexedPages)
        assertEquals(listOf(0), state.matches.map { it.pageIndex })
        assertEquals(0, state.current)

        gate.complete(Unit)
        advanceUntilIdle()
        state = search.state.value
        assertEquals(IndexStatus.DONE, state.status)
        assertEquals(4, state.indexedPages)
        assertEquals(listOf(0, 2, 3), state.matches.map { it.pageIndex })
        // Still on the first occurrence, which was not moved by the ones that came later.
        assertEquals(0, state.current)
    }

    @Test
    fun `the first result asks the viewer to scroll once, later pages don't`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor(listOf("niente", "qui c'è", "e anche qui"), gateAfter = 0, gate = gate, glyphs = ::glyphs)
        val search = search(extractor, 3)
        search.start()
        search.setQuery("qui")
        advanceTimeBy(DocumentSearch.DEBOUNCE_MS + 1)
        runCurrent()
        val before = search.state.value
        assertTrue(before.matches.isEmpty())
        assertEquals(-1, before.current)

        gate.complete(Unit)
        advanceUntilIdle()
        val after = search.state.value
        assertEquals(2, after.matches.size)
        assertEquals(0, after.current)
        assertEquals(before.focusToken + 1, after.focusToken)
    }

    @Test
    fun `next and previous wrap around and ask to scroll`() = runTest {
        val search = search(FakeExtractor(listOf("a", "a", "a"), glyphs = ::glyphs), 3)
        search.start()
        search.setQuery("a")
        advanceUntilIdle()
        var state = search.state.value
        assertEquals(3, state.matches.size)
        val token = state.focusToken

        search.next()
        assertEquals(1, search.state.value.current)
        search.next()
        search.next()
        assertEquals(0, search.state.value.current) // wrapped
        search.previous()
        state = search.state.value
        assertEquals(2, state.current)
        assertEquals(token + 4, state.focusToken)
        assertEquals(2, state.currentMatch!!.pageIndex)
    }

    @Test
    fun `next with no results does nothing`() = runTest {
        val search = search(FakeExtractor(listOf("a"), glyphs = ::glyphs), 1)
        search.start()
        search.setQuery("zzz")
        advanceUntilIdle()
        val before = search.state.value
        search.next()
        search.previous()
        assertEquals(before, search.state.value)
        assertNull(before.currentMatch)
    }

    @Test
    fun `a new query cancels the one still waiting`() = runTest {
        val search = search(FakeExtractor(listOf("alfa beta", "beta"), glyphs = ::glyphs), 2)
        search.start()
        advanceUntilIdle()
        search.setQuery("alfa")
        advanceTimeBy(100)
        search.setQuery("beta") // typed before the first pause ended
        advanceUntilIdle()
        val state = search.state.value
        assertEquals("beta", state.query!!.text)
        assertEquals(2, state.matches.size)
    }

    @Test
    fun `the same query again keeps the current result`() = runTest {
        val search = search(FakeExtractor(listOf("a", "a"), glyphs = ::glyphs), 2)
        search.start()
        search.setQuery("a")
        advanceUntilIdle()
        search.next()
        val state = search.state.value
        search.setQuery("A") // folds to the same query: e.g. the screen was rotated
        advanceUntilIdle()
        assertEquals(state, search.state.value)
    }

    @Test
    fun `a blank query clears the results at once and closing removes them`() = runTest {
        val search = search(FakeExtractor(listOf("a b"), glyphs = ::glyphs), 1)
        search.start()
        search.setQuery("a")
        advanceUntilIdle()
        assertEquals(1, search.state.value.matches.size)

        search.setQuery("   ")
        runCurrent() // no pause for a blank query
        assertTrue(search.state.value.matches.isEmpty())
        assertNull(search.state.value.query)

        search.setQuery("b")
        advanceUntilIdle()
        assertEquals(1, search.state.value.matches.size)
        search.close()
        advanceUntilIdle()
        assertTrue(search.state.value.matches.isEmpty())
        assertNull(search.state.value.query)
        assertEquals(-1, search.state.value.current)
        // The index is kept: searching again needs no new extraction.
        search.setQuery("a")
        advanceUntilIdle()
        assertEquals(1, search.state.value.matches.size)
    }

    @Test
    fun `indexing runs once even if start is called again`() = runTest {
        val extractor = FakeExtractor(listOf("a"), glyphs = ::glyphs)
        val search = search(extractor, 1)
        search.start()
        search.start()
        advanceUntilIdle()
        search.close()
        search.start()
        advanceUntilIdle()
        assertEquals(1, extractor.runs)
    }

    @Test
    fun `a document with no text is reported once it has been read`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor(listOf("", ""), gateAfter = 0, gate = gate, glyphs = ::glyphs)
        val search = search(extractor, 2)
        search.start()
        runCurrent()
        // Not known yet: the second page might have text.
        assertFalse(search.state.value.noSearchableText)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(search.state.value.noSearchableText)
        assertEquals(0, search.state.value.pagesWithText)
    }

    @Test
    fun `one page with text is enough to be searchable`() = runTest {
        val search = search(FakeExtractor(listOf("", "testo"), glyphs = ::glyphs), 2)
        search.start()
        advanceUntilIdle()
        assertFalse(search.state.value.noSearchableText)
        assertEquals(1, search.state.value.pagesWithText)
    }

    @Test
    fun `an unreadable document is reported as failed`() = runTest {
        val search = search(FakeExtractor(emptyList(), fail = true, glyphs = ::glyphs), 3)
        search.start()
        advanceUntilIdle()
        assertEquals(IndexStatus.FAILED, search.state.value.status)
        assertFalse(search.state.value.noSearchableText)
    }
}
