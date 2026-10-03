package com.marcogn.pdftoolkit.domain.signature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkAndBackgroundTest {

    private fun points(step: Float, dtMs: Long, count: Int) = List(count) { InkPoint(it * step, 0f, it * dtMs) }

    @Test
    fun slowStrokesAreWiderThanFastOnes() {
        val slow = InkWidth.widths(points(step = 0.1f, dtMs = 10, count = 60), base = 10f).last()
        val fast = InkWidth.widths(points(step = 50f, dtMs = 10, count = 60), base = 10f).last()
        assertTrue("slow $slow should be wider than fast $fast", slow > fast)
    }

    @Test
    fun widthStaysWithinTheLimits() {
        val base = 10f
        listOf(0.01f, 1f, 10f, 1_000f).forEach { step ->
            InkWidth.widths(points(step, 5, 80), base).forEach {
                assertTrue(it >= base * InkWidth.MIN_FACTOR - 0.001f)
                assertTrue(it <= base * InkWidth.MAX_FACTOR + 0.001f)
            }
        }
    }

    @Test
    fun widthChangesGraduallyBetweenSamples() {
        // From a slow stretch to a very fast one: no sample jumps by more than a fraction of the range.
        val slow = points(0.1f, 10, 20)
        val fast = List(20) { InkPoint(2f + it * 100f, 0f, 200L + it * 10L) }
        val widths = InkWidth.widths(slow + fast, base = 10f)
        val range = 10f * (InkWidth.MAX_FACTOR - InkWidth.MIN_FACTOR)
        for (i in 1 until widths.size) assertTrue(kotlin.math.abs(widths[i] - widths[i - 1]) < range * 0.3f)
    }

    @Test
    fun aSinglePointIsADotOfTheBaseWidth() {
        assertEquals(10f, InkWidth.widths(listOf(InkPoint(1f, 1f, 0)), 10f).single(), 0f)
        assertEquals(0, InkWidth.widths(emptyList(), 10f).size)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun whiteBecomesTransparentAndBlackStays() {
        val out = BackgroundRemoval.removeBackground(
            intArrayOf(argb(255, 255, 255, 255), argb(255, 0, 0, 0), argb(255, 20, 30, 120)),
            BackgroundRemoval.DEFAULT_THRESHOLD,
        )
        assertEquals(0, out[0] ushr 24)
        assertEquals(255, out[1] ushr 24)
        assertEquals(255, out[2] ushr 24)
        // The colour of the ink is kept.
        assertEquals(argb(0, 20, 30, 120) and 0xFFFFFF, out[2] and 0xFFFFFF)
    }

    @Test
    fun edgesRampBetweenOpaqueAndTransparent() {
        val threshold = 0.75f
        val halfway = (255 * (threshold - BackgroundRemoval.SOFTNESS / 2)).toInt()
        val alpha = BackgroundRemoval.removeBackground(intArrayOf(argb(255, halfway, halfway, halfway)), threshold)[0] ushr 24
        assertTrue("alpha $alpha", alpha in 100..155)
    }

    @Test
    fun existingTransparencyIsKept() {
        val out = BackgroundRemoval.removeBackground(intArrayOf(argb(0, 0, 0, 0), argb(128, 0, 0, 0)), 0.75f)
        assertEquals(0, out[0] ushr 24)
        assertEquals(128, out[1] ushr 24)
    }

    @Test
    fun boundsFollowTheVisiblePixels() {
        val w = 6
        val h = 5
        val pixels = IntArray(w * h)
        pixels[1 * w + 2] = argb(255, 0, 0, 0)
        pixels[3 * w + 4] = argb(255, 0, 0, 0)
        assertEquals(PixelRect(2, 1, 5, 4), BackgroundRemoval.contentBounds(pixels, w, h))
        assertEquals(PixelRect(1, 0, 6, 5), BackgroundRemoval.contentBounds(pixels, w, h, margin = 1))
        assertNull(BackgroundRemoval.contentBounds(IntArray(w * h), w, h))
    }
}
