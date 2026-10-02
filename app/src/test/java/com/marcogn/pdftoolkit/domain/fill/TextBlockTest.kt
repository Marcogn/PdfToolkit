package com.marcogn.pdftoolkit.domain.fill

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockTest {

    @Test
    fun boxFitsTheWidestLineAndEveryLine() {
        val (width, height) = TextBlock.boxSize(listOf(50f, 80f, 10f), fontSize = 10f)
        assertEquals(80f + 2 * 1.5f, width, 1e-4f)
        assertEquals(3 * 13.62f + 2 * 1.5f, height, 1e-3f)
    }

    @Test
    fun emptyTextStillHasABox() {
        val (width, height) = TextBlock.boxSize(listOf(0f), fontSize = 10f)
        assertEquals(10f + 3f, width, 1e-4f)
        assertEquals(13.62f + 3f, height, 1e-3f)
    }

    @Test
    fun baselinesAreOneLineHeightApart() {
        val (x0, b0) = TextBlock.lineOrigin(0, 20f)
        val (x1, b1) = TextBlock.lineOrigin(1, 20f)
        assertEquals(3f, x0, 1e-4f)
        assertEquals(x0, x1, 0f)
        assertEquals(3f + 21.38f, b0, 1e-3f)
        assertEquals(TextBlock.LINE_HEIGHT * 20f, b1 - b0, 1e-4f)
    }

    @Test
    fun sanitizeComposesAccentsAndDropsWhatTheFontLacks() {
        val decomposed = "perché" // e + combining acute
        assertEquals("perché", TextBlock.sanitize(decomposed) { true })
        assertEquals("a b\nc", TextBlock.sanitize("a\tb\r\nc") { true })
        // An emoji (outside the BMP) the font doesn't have is dropped, the rest stays.
        assertEquals("ok ", TextBlock.sanitize("ok 😀") { it < 0x10000 })
    }
}
