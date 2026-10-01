package com.marcogn.pdftoolkit.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerHelpersTest {

    @Test
    fun scrubberFractionAndPageAreInverse() {
        for (count in listOf(2, 3, 10, 200, 1234)) {
            for (page in listOf(0, 1, count / 2, count - 1)) {
                assertEquals(page, pageForFraction(fractionForPage(page, count), count))
            }
        }
        assertEquals(0f, fractionForPage(0, 50), 0f)
        assertEquals(1f, fractionForPage(49, 50), 0f)
    }

    @Test
    fun scrubberFractionIsClamped() {
        assertEquals(0, pageForFraction(-0.5f, 10))
        assertEquals(9, pageForFraction(1.5f, 10))
        assertEquals(0f, fractionForPage(0, 1), 0f)
    }

    @Test
    fun goToPageIsOneBasedAndValidated() {
        assertEquals(0, parsePageNumber("1", 10))
        assertEquals(9, parsePageNumber("10", 10))
        assertNull(parsePageNumber("0", 10))
        assertNull(parsePageNumber("11", 10))
        assertNull(parsePageNumber("", 10))
        assertNull(parsePageNumber("-3", 10))
    }
}
