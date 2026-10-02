package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.fontbox.ttf.TTFParser
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which characters the text font ([FontSource]) can draw, read from its own `cmap`, so the screen
 * drops the same characters the PDF writer would (Android would draw them with a fallback font,
 * the PDF can't). Read once, on first use, off the main thread.
 */
@Singleton
class FontCoverage @Inject constructor(private val fonts: FontSource) {

    private val cmap: CmapLookup? by lazy {
        try {
            fonts.openRegular().use { TTFParser().parse(it).unicodeCmapLookup }
        } catch (e: IOException) {
            null
        }
    }

    /** [TextBlock.sanitize] against the font; if the font can't be read, nothing is dropped. */
    fun sanitize(text: String): String {
        val lookup = cmap ?: return TextBlock.sanitize(text) { true }
        return TextBlock.sanitize(text) { lookup.getGlyphId(it) > 0 }
    }
}
