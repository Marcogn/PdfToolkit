package com.marcogn.pdftoolkit.pdf.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SizedLruCacheTest {

    /** Values are their own size. */
    private fun cache(max: Long) = SizedLruCache<String, Long>(max) { it }

    @Test
    fun evictsLeastRecentlyUsedAboveMaxSize() {
        val cache = cache(10)
        cache.put("a", 4)
        cache.put("b", 4)
        cache["a"] // a is now the most recently used
        cache.put("c", 4)
        assertNull(cache["b"])
        assertEquals(4L, cache["a"])
        assertEquals(4L, cache["c"])
        assertEquals(8L, cache.size)
    }

    @Test
    fun replacingAKeyUpdatesTheSize() {
        val cache = cache(10)
        cache.put("a", 6)
        cache.put("a", 2)
        assertEquals(2L, cache.size)
        assertEquals(listOf("a"), cache.keys())
    }

    @Test
    fun valueLargerThanTheCacheIsNotStored() {
        val cache = cache(10)
        cache.put("a", 5)
        assertFalse(cache.put("huge", 11))
        assertFalse("huge" in cache)
        assertTrue("a" in cache)
        assertEquals(5L, cache.size)
    }

    @Test
    fun removeAndClear() {
        val cache = cache(10)
        cache.put("a", 3)
        cache.put("b", 3)
        assertEquals(3L, cache.remove("a"))
        assertEquals(3L, cache.size)
        cache.clear()
        assertEquals(0L, cache.size)
        assertNull(cache["b"])
    }

    @Test
    fun budgetScalesWithMemoryClassWithinLimits() {
        val mb = 1024L * 1024L
        val mid = RenderBudget.forMemoryClass(256)
        assertEquals(128 * mb, mid.pageCacheBytes + mid.tileCacheBytes)
        assertEquals(mid.pageCacheBytes / 4, mid.maxPageBitmapBytes)
        // An A4 page 1440 px wide (1440 x 2036 x 4 bytes) fits, and so do the visible pages plus ±2.
        val a4 = 1440L * 2036 * 4
        assertTrue(a4 <= mid.maxPageBitmapBytes)
        assertTrue(mid.pageCacheBytes >= 6 * a4)

        val tiny = RenderBudget.forMemoryClass(16)
        assertEquals(32 * mb, tiny.pageCacheBytes + tiny.tileCacheBytes)
        val huge = RenderBudget.forMemoryClass(2048)
        assertEquals(256 * mb, huge.pageCacheBytes + huge.tileCacheBytes)
        assertEquals(32 * mb, huge.maxPageBitmapBytes)
    }
}
