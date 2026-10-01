package com.marcogn.pdftoolkit.pdf.render

/**
 * LRU cache bounded by the total size of its values (bytes for bitmaps), thread safe.
 *
 * Plain Kotlin instead of `android.util.LruCache` so it runs in JVM unit tests. Evicted values
 * are only dropped, never recycled: a bitmap may still be on screen for a frame, and since
 * Android 8 bitmap pixels live in the native heap and are freed with the object.
 */
class SizedLruCache<K : Any, V : Any>(
    val maxSize: Long,
    private val sizeOf: (V) -> Long,
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)

    var size: Long = 0
        @Synchronized get
        private set

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun contains(key: K): Boolean = map.containsKey(key)

    /**
     * Stores [value] and evicts the least recently used entries above [maxSize]. A value larger
     * than the whole cache is not stored (and replaces nothing); returns false in that case.
     */
    @Synchronized
    fun put(key: K, value: V): Boolean {
        val valueSize = sizeOf(value)
        require(valueSize >= 0) { "Negative size for $key" }
        if (valueSize > maxSize) return false
        map.put(key, value)?.let { size -= sizeOf(it) }
        size += valueSize
        trimTo(maxSize)
        return true
    }

    @Synchronized
    fun remove(key: K): V? = map.remove(key)?.also { size -= sizeOf(it) }

    @Synchronized
    fun clear() {
        map.clear()
        size = 0
    }

    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    private fun trimTo(limit: Long) {
        val iterator = map.entries.iterator()
        while (size > limit && iterator.hasNext()) {
            val eldest = iterator.next()
            size -= sizeOf(eldest.value)
            iterator.remove()
        }
    }
}

/**
 * Memory for rendered bitmaps (spec §5: LRU sized on `ActivityManager.memoryClass`).
 *
 * [pageCacheBytes]: whole pages at fit-width resolution, the ones visible and the ±2 prefetch.
 * [tileCacheBytes]: high-resolution tiles of the zoomed area. Separate so that zooming in can
 * never evict the pages around the current one.
 * [maxPageBitmapBytes]: largest single page bitmap; above it the page is rendered smaller and
 * tiles make up for the detail (spec §5: "si va a tile").
 */
data class RenderBudget(
    val pageCacheBytes: Long,
    val tileCacheBytes: Long,
    val maxPageBitmapBytes: Long,
) {
    companion object {
        private const val MB = 1024L * 1024L
        private const val MIN_TOTAL = 32 * MB
        private const val MAX_TOTAL = 256 * MB
        private const val MAX_PAGE_BITMAP = 32 * MB

        /**
         * Half of `memoryClass`, clamped to 32–256 MB, two thirds to pages and one third to tiles.
         * `memoryClass` is the Java heap budget, while since Android 8 bitmap pixels are in the
         * native heap: it is used as the spec asks, as a measure of how capable the device is.
         * Example: 256 MB class → 85 MB pages (seven A4 pages 1440 px wide), 43 MB tiles.
         */
        fun forMemoryClass(memoryClassMb: Int): RenderBudget {
            val total = (memoryClassMb * MB / 2).coerceIn(MIN_TOTAL, MAX_TOTAL)
            val pages = total * 2 / 3
            return RenderBudget(
                pageCacheBytes = pages,
                tileCacheBytes = total - pages,
                maxPageBitmapBytes = minOf(pages / 4, MAX_PAGE_BITMAP),
            )
        }
    }
}
