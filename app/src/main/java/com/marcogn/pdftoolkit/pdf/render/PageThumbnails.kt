package com.marcogn.pdftoolkit.pdf.render

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small page bitmaps for the thumbnail bar, with their own LRU so they never compete with the
 * viewer's page cache. Rendering goes through [PdfDocumentRenderer], whose mutex serialises it
 * with the viewer's own renders (handoff 1a).
 *
 * A thumbnail is [heightPx] tall and as wide as the page proportions say.
 */
class PageThumbnails(
    private val renderer: PdfDocumentRenderer,
    val heightPx: Int = DEFAULT_HEIGHT_PX,
    cacheBytes: Long = CACHE_BYTES,
) {
    private val cache = SizedLruCache<Int, Bitmap>(cacheBytes) { it.allocationByteCount.toLong() }

    /** Cached or freshly rendered thumbnail of [pageIndex]; null if the document was closed. */
    suspend fun get(pageIndex: Int): Bitmap? {
        cache[pageIndex]?.let { return it }
        return renderer.render(keyFor(pageIndex))?.also { cache.put(pageIndex, it) }
    }

    fun clear() = cache.clear()

    internal fun keyFor(pageIndex: Int): PageKey {
        val size = renderer.pageSizes[pageIndex]
        val scale = heightPx / size.height
        return PageKey(pageIndex, max(1, (size.width * scale).roundToInt()), heightPx, scale)
    }

    companion object {
        const val DEFAULT_HEIGHT_PX = 320
        private const val CACHE_BYTES = 16L * 1024 * 1024
    }
}
