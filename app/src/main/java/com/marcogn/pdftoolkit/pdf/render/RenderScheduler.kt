package com.marcogn.pdftoolkit.pdf.render

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Renders what the viewer currently wants, one bitmap at a time, into two LRU caches.
 *
 * The viewer calls [request] with the full ordered list from [RenderPlanner.plan] whenever the
 * viewport changes; a single worker renders the first entry that is not cached yet, then
 * re-reads the list. So when the user scrolls fast nothing queues up: pages that went off
 * screen are simply no longer in the list. One worker is enough because `PdfRenderer` renders
 * one page at a time anyway (ADR 0001).
 *
 * Each pass over a list renders every key at most once, even if a later render evicted it:
 * if the list doesn't fit in the caches it doesn't loop forever.
 *
 * Generic over the bitmap type so it can be tested without Android.
 *
 * @param render returns null if the bitmap can't be produced (closed document, out of memory).
 */
class RenderScheduler<B : Any>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    budget: RenderBudget,
    bytesOf: (B) -> Long,
    private val render: suspend (RenderKey) -> B?,
) {
    private val pages = SizedLruCache<RenderKey, B>(budget.pageCacheBytes, bytesOf)
    private val tiles = SizedLruCache<RenderKey, B>(budget.tileCacheBytes, bytesOf)
    private val wanted = MutableStateFlow<List<RenderKey>>(emptyList())
    private val requests = LinkedHashMap<Any, List<RenderKey>>() // guarded by itself
    private val _revision = MutableStateFlow(0L)

    /** Incremented after every new bitmap: the viewer redraws when it changes. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        scope.launch(dispatcher) { work() }
    }

    /**
     * Replaces the list of bitmaps wanted by [source]. Several views can share one scheduler (the
     * pages of the single-page pager): the worker takes the lists of all sources, in the order they
     * first asked. An equal list is a no-op.
     */
    fun request(keys: List<RenderKey>, source: Any = Unit) {
        synchronized(requests) {
            if (keys.isEmpty()) requests.remove(source) else requests[source] = keys
            wanted.value = requests.values.flatten()
        }
    }

    /** [source] doesn't need anything any more (its view left the screen). */
    fun release(source: Any) = request(emptyList(), source)

    operator fun get(key: RenderKey): B? = cacheFor(key)[key]

    fun clear() {
        pages.clear()
        tiles.clear()
    }

    private fun cacheFor(key: RenderKey) = when (key) {
        is PageKey -> pages
        is TileKey -> tiles
    }

    private suspend fun work() {
        while (true) {
            val keys = wanted.value
            val done = HashSet<RenderKey>()
            while (wanted.value == keys) {
                val next = keys.firstOrNull { it !in done && it !in cacheFor(it) } ?: break
                done += next
                val bitmap = try {
                    render(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    cacheFor(next).put(next, bitmap)
                    _revision.update { it + 1 }
                }
            }
            if (wanted.value == keys) wanted.first { it != keys }
        }
    }
}
