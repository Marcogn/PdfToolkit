package com.marcogn.pdftoolkit.pdf.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextNormalizerTest {

    private fun fold(text: String) = TextNormalizer.normalize(text).text

    @Test
    fun `accents and case are folded`() {
        assertEquals("perche", fold("Perché"))
        assertEquals("perche", fold("PERCHÉ"))
        assertEquals("citta e piu", fold("Città è più"))
        assertEquals("ca va, garcon", fold("Ça va, garçon"))
        assertEquals("uber straße", fold("Über Straße"))
    }

    @Test
    fun `precomposed and decomposed accents give the same text`() {
        assertEquals(fold("perché"), fold("perché"))
    }

    @Test
    fun `each folded character points back to the character it comes from`() {
        val result = TextNormalizer.normalize("Perché")
        assertEquals("perche", result.text)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), result.sourceIndex)

        // A decomposed accent is two characters in; the mark is dropped, the base keeps its index.
        val decomposed = TextNormalizer.normalize("éx")
        assertEquals("ex", decomposed.text)
        assertArrayEquals(intArrayOf(0, 2), decomposed.sourceIndex)
    }

    @Test
    fun `ligatures expand and both letters point to the ligature`() {
        val result = TextNormalizer.normalize("aﬁb") // "aﬁb"
        assertEquals("afib", result.text)
        assertArrayEquals(intArrayOf(0, 1, 1, 2), result.sourceIndex)
    }

    @Test
    fun `white space runs collapse into one space, none at the start`() {
        val result = TextNormalizer.normalize("  a \t  b\nc ")
        assertEquals("a b c ", result.text)
        assertArrayEquals(intArrayOf(2, 3, 7, 8, 9, 10), result.sourceIndex)
    }

    @Test
    fun `soft hyphens and zero-width characters are dropped`() {
        assertEquals("parola", fold("pa­ro​la"))
    }

    @Test
    fun `typographic punctuation folds to ASCII`() {
        assertEquals("l'anno \"bello\" - fine", fold("l’anno “bello” – fine"))
        assertEquals("l'anno", fold("L'Anno"))
    }

    @Test
    fun `greek final sigma matches sigma`() {
        assertEquals(fold("ΛΟΓΟΣ"), fold("λογος"))
        assertEquals(fold("λογοσ"), fold("λογος"))
    }

    @Test
    fun `letters outside the Basic Multilingual Plane keep their mapping`() {
        val result = TextNormalizer.normalize("a𝐀b") // MATHEMATICAL BOLD CAPITAL A
        assertEquals("aab", result.text)
        assertArrayEquals(intArrayOf(0, 1, 3), result.sourceIndex)
    }

    @Test
    fun `queries are folded like the index and trimmed`() {
        assertEquals("perche no", SearchQuery.of("  PERCHÉ   no ")!!.text)
        assertNull(SearchQuery.of(""))
        assertNull(SearchQuery.of(" \t "))
        assertNull(SearchQuery.of("́")) // only a combining mark
    }
}
