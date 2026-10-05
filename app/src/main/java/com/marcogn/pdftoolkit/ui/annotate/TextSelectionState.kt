package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.text.GlyphRange
import com.marcogn.pdftoolkit.pdf.text.LineRun
import com.marcogn.pdftoolkit.pdf.text.TextSelection

/** One of the two handles at the ends of a selection. */
enum class SelectionHandle { START, END }

/**
 * The text selected on one page (spec §7.4): which page ([key]: the viewer's page number, the edit
 * screen's page id) and which glyphs, plus the [text] model of that page the range refers to.
 * Positions are page points as displayed by the page's *source* (see [TextSelection]).
 *
 * Only [key] and the range are saved across a screen rotation; [text] is read again for the page
 * ([ResolveTextSelection]).
 */
@Stable
class TextSelectionState(key: String? = null, range: GlyphRange? = null) {
    var key by mutableStateOf(key)
        private set
    var range by mutableStateOf(range)
        private set
    var text by mutableStateOf<TextSelection?>(null)
        private set

    /** Whether there is a selection, even if its page text is still being read. */
    val isActive: Boolean get() = key != null && range != null

    fun select(key: String, text: TextSelection, range: GlyphRange) {
        this.key = key
        this.text = text
        this.range = range
    }

    fun clear() {
        key = null
        range = null
        text = null
    }

    /** Page text of a restored selection; [text] null means the page can't be read, so the selection goes. */
    fun attach(text: TextSelection?) {
        if (text == null) clear() else this.text = text
    }

    /** Moves [handle] to the boundary nearest to [displayPoint] (page points); the two handles never cross. */
    fun drag(handle: SelectionHandle, displayPoint: Offset) {
        val model = text ?: return
        val current = range ?: return
        val boundary = model.boundaryAt(displayPoint) ?: return
        range = when (handle) {
            SelectionHandle.START -> current.withStart(boundary)
            SelectionHandle.END -> current.withEnd(boundary, model.glyphCount)
        }
    }

    /** The selection's area, one run per line, in page points. Empty until the page text is there. */
    val runs: List<LineRun>
        get() {
            val model = text ?: return emptyList()
            val current = range ?: return emptyList()
            return model.runs(current)
        }

    /** The selected text as the clipboard gets it. */
    val clipboardText: String
        get() {
            val model = text ?: return ""
            val current = range ?: return ""
            return model.text(current)
        }

    companion object {
        val Saver = listSaver<TextSelectionState, Any?>(
            save = { listOf(it.key, it.range?.start, it.range?.end) },
            restore = { saved ->
                val key = saved[0] as String?
                val start = saved[1] as Int?
                val end = saved[2] as Int?
                if (key != null && start != null && end != null && start < end) {
                    TextSelectionState(key, GlyphRange(start, end))
                } else {
                    TextSelectionState()
                }
            },
        )
    }
}

@Composable
fun rememberTextSelectionState(): TextSelectionState = rememberSaveable(saver = TextSelectionState.Saver) { TextSelectionState() }

/**
 * Reads the page text for a selection that was restored without it (after a rotation), with
 * [load] giving the text model of the page named by the key.
 */
@Composable
fun ResolveTextSelection(state: TextSelectionState, load: suspend (key: String) -> TextSelection?) {
    LaunchedEffect(state.key) {
        val key = state.key ?: return@LaunchedEffect
        if (state.text == null) state.attach(load(key))
    }
}
