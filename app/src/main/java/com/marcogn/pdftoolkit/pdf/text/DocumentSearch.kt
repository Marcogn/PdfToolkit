package com.marcogn.pdftoolkit.pdf.text

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

enum class IndexStatus { IDLE, RUNNING, DONE, FAILED }

/**
 * What the search UI shows (spec §5.1). [matches] are in document order and only ever grow while
 * a query stays the same, so [current] keeps pointing at the same occurrence as pages get indexed.
 *
 * [focusToken] changes when the viewer should scroll to [current]: the first result of a query, and
 * every move with [DocumentSearch.next] / [DocumentSearch.previous]. Rotating the screen or a new
 * page being indexed does not change it.
 */
data class SearchState(
    val pageCount: Int,
    val query: SearchQuery? = null,
    val matches: List<TextMatch> = emptyList(),
    val current: Int = -1,
    val focusToken: Int = 0,
    val indexedPages: Int = 0,
    val pagesWithText: Int = 0,
    val status: IndexStatus = IndexStatus.IDLE,
) {
    val isIndexing: Boolean get() = status == IndexStatus.RUNNING

    /** The whole document was read and no page has text: probably a scan (spec §5.1). */
    val noSearchableText: Boolean get() = status == IndexStatus.DONE && pagesWithText == 0

    val currentMatch: TextMatch? get() = matches.getOrNull(current)
}

/**
 * Text search in one open document (spec §5.1): builds the index page by page in the background and
 * matches the query against each page as soon as it is ready, so results appear while indexing is
 * still going. Plain Kotlin, no Android: the stream comes from [open] and the text from
 * [extractor].
 *
 * - [start] is idempotent; indexing runs once, to the end, whatever the user does with the search
 *   (the index stays in memory while the document is open, spec §5.1).
 * - [setQuery] debounces; a newer query cancels the one still waiting or scanning.
 * - [close] drops the query, and with it the results and the highlights.
 *
 * Index and results are changed under one lock, so a page indexed while a query is being scanned
 * is neither missed nor counted twice.
 */
class DocumentSearch(
    private val scope: CoroutineScope,
    private val extractor: PdfTextExtractor,
    private val open: () -> InputStream?,
    private val password: String?,
    pageCount: Int,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(SearchState(pageCount))
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val lock = Mutex()
    private val indexes = ArrayList<PageTextIndex>()
    private var activeQuery: SearchQuery? = null
    private var requestedQuery: SearchQuery? = null
    private var indexJob: Job? = null
    private var queryJob: Job? = null

    /** Starts reading the document's text, once. */
    fun start() {
        if (indexJob != null) return
        _state.update { it.copy(status = IndexStatus.RUNNING) }
        indexJob = scope.launch {
            try {
                extractor.pages(open, password).collect { page ->
                    val index = withContext(workDispatcher) { PageTextIndex.build(page) }
                    lock.withLock { add(index) }
                }
                _state.update { it.copy(status = IndexStatus.DONE) }
            } catch (e: TextExtractionException) {
                _state.update { it.copy(status = IndexStatus.FAILED) }
            }
        }
    }

    /** Looks for [raw] after a short pause; blank clears the results at once. The same query again does nothing. */
    fun setQuery(raw: String) {
        val query = SearchQuery.of(raw)
        if (query == requestedQuery) return
        requestedQuery = query
        queryJob?.cancel()
        queryJob = scope.launch {
            if (query != null) delay(debounceMs)
            lock.withLock { apply(query) }
        }
    }

    /** The search was closed: results and highlights go away; the index stays. */
    fun close() {
        requestedQuery = null
        queryJob?.cancel()
        queryJob = scope.launch { lock.withLock { apply(null) } }
    }

    fun next() = move(+1)

    fun previous() = move(-1)

    private fun move(step: Int) {
        _state.update {
            if (it.matches.isEmpty()) {
                it
            } else {
                it.copy(current = Math.floorMod(it.current + step, it.matches.size), focusToken = it.focusToken + 1)
            }
        }
    }

    /** Under [lock]. */
    private fun add(index: PageTextIndex) {
        indexes += index
        val query = activeQuery
        val found = if (query != null) index.find(query) else emptyList()
        _state.update {
            val matches = if (found.isEmpty()) it.matches else it.matches + found
            val first = it.matches.isEmpty() && found.isNotEmpty()
            it.copy(
                matches = matches,
                current = if (first) 0 else it.current,
                focusToken = if (first) it.focusToken + 1 else it.focusToken,
                indexedPages = indexes.size,
                pagesWithText = it.pagesWithText + if (index.isEmpty) 0 else 1,
            )
        }
    }

    /** Under [lock]: makes [query] the active one and matches it against every page indexed so far. */
    private suspend fun apply(query: SearchQuery?) {
        val matches = if (query == null) {
            emptyList()
        } else {
            withContext(workDispatcher) {
                indexes.flatMap { index ->
                    ensureActive()
                    index.find(query)
                }
            }
        }
        activeQuery = query
        _state.update {
            it.copy(
                query = query,
                matches = matches,
                current = if (matches.isEmpty()) -1 else 0,
                focusToken = if (matches.isEmpty()) it.focusToken else it.focusToken + 1,
            )
        }
    }

    companion object {
        /** Spec §5.1. */
        const val DEBOUNCE_MS = 250L
    }
}
