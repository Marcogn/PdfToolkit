package com.marcogn.pdftoolkit.ui.annotate

import androidx.ink.brush.InputToolType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The input filter only: androidx.ink's native code doesn't load in JVM tests (the stroke itself is device only). */
class WetStrokeTest {

    private fun touch(x: Float, t: Long) = InkSample(x, 0f, t, InputToolType.TOUCH)

    @Test
    fun `usable keeps inputs in time order`() {
        val samples = listOf(touch(0f, 0), touch(1f, 5), touch(2f, 10))
        assertEquals(samples, WetStroke.usable(null, samples))
    }

    @Test
    fun `usable drops time going back and exact repeats`() {
        val last = touch(1f, 10)
        val samples = listOf(touch(1f, 10), touch(2f, 8), touch(3f, 10), touch(4f, 12))
        assertEquals(listOf(touch(3f, 10), touch(4f, 12)), WetStroke.usable(last, samples))
    }

    @Test
    fun `usable drops another tool than the stroke's`() {
        val samples = listOf(touch(0f, 0), InkSample(1f, 0f, 5, InputToolType.STYLUS), touch(2f, 10))
        assertEquals(listOf(touch(0f, 0), touch(2f, 10)), WetStroke.usable(null, samples))
    }
}
