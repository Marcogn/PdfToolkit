package com.marcogn.pdftoolkit.pdf.text

import java.text.Normalizer

/**
 * Text folded for search, with the way back to the original: [sourceIndex]`[i]` is the index of
 * the character of the original string that produced `text[i]`.
 */
class NormalizedText(val text: String, val sourceIndex: IntArray) {
    init {
        require(text.length == sourceIndex.size) { "One source index per character" }
    }
}

/**
 * Folding shared by the index and the query (spec §5.1), so "perche" finds "perché":
 * - compatibility decomposition (NFKD, a superset of the spec's NFD) and then every combining
 *   mark (`Mn`) is dropped: accents go, and ligatures such as "ﬁ" become "fi";
 * - case folded per code point (upper, then lower, so "ς", "σ" and "Σ" match);
 * - typographic apostrophes, quotes and dashes become their ASCII form ("l’anno" = "l'anno");
 * - format characters (soft hyphen, zero-width joiners) are dropped;
 * - any run of white space becomes one space, with none at the start (a trailing one can stay).
 *
 * Each output character keeps the index of the input character it comes from, so a match in the
 * folded text can be traced back to the glyphs of the page.
 */
object TextNormalizer {

    fun normalize(input: CharSequence): NormalizedText {
        val out = StringBuilder(input.length)
        val sources = IntArrayBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val codePoint = Character.codePointAt(input, i)
            val source = i
            i += Character.charCount(codePoint)
            if (isSpace(codePoint)) {
                if (out.isNotEmpty() && out[out.length - 1] != ' ') {
                    out.append(' ')
                    sources.add(source)
                }
                continue
            }
            for (folded in fold(codePoint)) {
                out.appendCodePoint(folded)
                repeat(Character.charCount(folded)) { sources.add(source) }
            }
        }
        return NormalizedText(out.toString(), sources.toArray())
    }

    /** The folded code points of one input code point; empty when it is dropped. */
    private fun fold(codePoint: Int): IntArray {
        PUNCTUATION[codePoint]?.let { return intArrayOf(it.code) }
        val decomposed = if (codePoint < ASCII_END) {
            intArrayOf(codePoint)
        } else {
            Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFKD).codePoints().toArray()
        }
        val result = IntArrayBuilder(decomposed.size)
        for (cp in decomposed) {
            when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(), Character.FORMAT.toInt() -> Unit
                else -> {
                    // A compatibility decomposition can yield a space (e.g. "¨" → " ̈").
                    if (isSpace(cp)) return IntArray(0)
                    result.add(Character.toLowerCase(Character.toUpperCase(cp)))
                }
            }
        }
        return result.toArray()
    }

    private fun isSpace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private const val ASCII_END = 0x80

    private val PUNCTUATION: Map<Int, Char> = buildMap {
        listOf(0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x02BC, 0x00B4, 0x0060).forEach { put(it, '\'') }
        listOf(0x201C, 0x201D, 0x201E, 0x201F, 0x2033, 0x00AB, 0x00BB).forEach { put(it, '"') }
        listOf(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212).forEach { put(it, '-') }
    }
}

/** A growable `IntArray`, to avoid boxing one `Int` per character of a page. */
internal class IntArrayBuilder(capacity: Int) {
    private var data = IntArray(maxOf(capacity, 4))
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}
