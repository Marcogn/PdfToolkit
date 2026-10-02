package com.marcogn.pdftoolkit.domain.edit

import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FillContent
import com.marcogn.pdftoolkit.domain.fill.Overlay
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The document being edited, kept in memory and never written until the user saves (spec §6.1):
 * the page list and what "Fill and sign" put on the pages ([fill]: overlays bound to page ids and
 * form values). Immutable: every operation returns a new session, with the previous state pushed
 * on the undo stack, so undo/redo are just a swap of states.
 *
 * [original] is the page list the session started from; [isModified] compares against it, so
 * undoing everything (or moving a page away and back) leaves nothing to save.
 */
class EditSession private constructor(
    private val state: State,
    private val original: List<PageItem>,
    private val undoStack: List<State>,
    private val redoStack: List<State>,
    /** Field whose text is being typed: further typing in it replaces the state instead of adding an undo step. */
    private val typingIn: String? = null,
) {
    private data class State(val pages: List<PageItem>, val fill: FillContent)

    val pages: List<PageItem> get() = state.pages
    val fill: FillContent get() = state.fill
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Overlays and form values only exist when the user added them, so any of them is a change. */
    val isModified: Boolean get() = pages != original || !fill.isEmpty
    val pageCount: Int get() = pages.size

    /** Removes the pages with these [ids]. The last remaining page can't be removed (spec §6.3). */
    fun remove(ids: Set<String>): EditSession {
        val kept = pages.filterNot { it.id in ids }
        if (kept.size == pages.size || kept.isEmpty()) return this
        return change(kept)
    }

    /** Moves the page at [from] so that it ends up at index [to] (indices of the current list). */
    fun move(from: Int, to: Int): EditSession {
        if (from !in pages.indices || to !in pages.indices || from == to) return this
        val list = pages.toMutableList()
        list.add(to, list.removeAt(from))
        return change(list)
    }

    fun moveToStart(id: String): EditSession = pages.indexOfFirst { it.id == id }.let { if (it < 0) this else move(it, 0) }

    fun moveToEnd(id: String): EditSession = pages.indexOfFirst { it.id == id }.let { if (it < 0) this else move(it, pages.lastIndex) }

    /** Rotates the pages with these [ids] by [degrees] (a multiple of 90, negative = anticlockwise). */
    fun rotate(ids: Set<String>, degrees: Int): EditSession {
        if (Math.floorMod(degrees, PageItem.FULL_TURN) == 0 || pages.none { it.id in ids }) return this
        return change(pages.map { if (it.id in ids) it.withRotation(it.rotation + degrees) else it })
    }

    fun undo(): EditSession {
        if (undoStack.isEmpty()) return this
        return EditSession(undoStack.last(), original, undoStack.dropLast(1), redoStack + listOf(state))
    }

    fun redo(): EditSession {
        if (redoStack.isEmpty()) return this
        return EditSession(redoStack.last(), original, undoStack + listOf(state), redoStack.dropLast(1))
    }

    private fun change(newPages: List<PageItem>) = change(State(newPages, fill))

    private fun change(newState: State, typing: String? = null) =
        EditSession(newState, original, (undoStack + listOf(state)).takeLast(MAX_HISTORY), emptyList(), typing)

    // --- Fill and sign (spec §6.5) ---

    /** Adds [overlay] on top of the others. Ignored if its id is taken or its page isn't in the session. */
    fun addOverlay(overlay: Overlay): EditSession {
        if (fill.overlays.any { it.id == overlay.id } || pages.none { it.id == overlay.pageId }) return this
        return change(State(pages, fill.copy(overlays = fill.overlays + overlay)))
    }

    /** Replaces the overlay with the same id (new text, new box...). */
    fun updateOverlay(overlay: Overlay): EditSession {
        val index = fill.overlays.indexOfFirst { it.id == overlay.id }
        if (index < 0 || fill.overlays[index] == overlay) return this
        val overlays = fill.overlays.toMutableList().apply { set(index, overlay) }
        return change(State(pages, fill.copy(overlays = overlays)))
    }

    fun removeOverlay(id: String): EditSession {
        if (fill.overlays.none { it.id == id }) return this
        return change(State(pages, fill.copy(overlays = fill.overlays.filterNot { it.id == id })))
    }

    /**
     * Sets the value of form field [name]; null goes back to the value in the file. With [typing],
     * consecutive changes to the same field are one undo step (one per keystroke would be useless).
     */
    fun setField(name: String, value: FieldValue?, typing: Boolean = false): EditSession {
        if (fill.fields[name] == value) return this
        val fields = if (value == null) fill.fields - name else fill.fields + (name to value)
        val newState = State(pages, fill.copy(fields = fields))
        if (typing && typingIn == name && undoStack.isNotEmpty()) {
            return EditSession(newState, original, undoStack, emptyList(), typingIn)
        }
        return change(newState, typing = if (typing) name else null)
    }

    /** The overlays and form values as JSON, alongside [encode]. */
    fun encodeFill(): String = if (fill.isEmpty) "" else json.encodeToString(FillContent.serializer(), fill)

    /** Puts [items] at [index] (0..pageCount), keeping their order. */
    fun insert(index: Int, items: List<PageItem>): EditSession {
        if (items.isEmpty() || index !in 0..pages.size) return this
        if (items.any { new -> pages.any { it.id == new.id } }) return this
        return change(pages.subList(0, index) + items + pages.subList(index, pages.size))
    }

    /** Ids of the documents the pages come from, besides [DocRef.MAIN]. */
    val extraDocuments: Set<DocRef>
        get() = pages.filterIsInstance<PageItem.FromPdf>().map { it.docRef }.filter { it != DocRef.MAIN }.toSet()

    /**
     * The pages as a compact string, to survive process death through `SavedStateHandle` and to
     * travel to the background save. History is not kept. Fields are URL-encoded, so a URI with
     * `,` or `;` is safe.
     */
    fun encode(): String = pages.joinToString(SEPARATOR) { page ->
        when (page) {
            is PageItem.FromPdf -> fields(PDF, page.id, page.docRef.id, page.pageIndex, page.rotation)
            is PageItem.Blank -> fields(BLANK, page.id, page.widthPt, page.heightPt, page.rotation)
            is PageItem.FromImage -> fields(IMAGE, page.id, page.imageUri, page.mode.name, page.widthPt, page.heightPt, page.rotation)
        }
    }

    companion object {
        private const val MAX_HISTORY = 100
        private val json = Json { ignoreUnknownKeys = true }
        private const val SEPARATOR = ";"
        private const val FIELD = ","
        private const val PDF = "P"
        private const val BLANK = "B"
        private const val IMAGE = "I"

        // The charset-name overloads: the Charset ones need API 33 and the minimum here is 26.
        private const val UTF_8 = "UTF-8"

        private fun fields(vararg values: Any): String = values.joinToString(FIELD) { URLEncoder.encode(it.toString(), UTF_8) }

        /** Session over the [pageCount] pages of the document, in their original order. */
        fun of(pageCount: Int): EditSession {
            val pages = List(pageCount) { PageItem.FromPdf(id = "p$it", docRef = DocRef.MAIN, pageIndex = it) }
            return EditSession(State(pages, FillContent()), pages, emptyList(), emptyList())
        }

        /**
         * Session over several documents, one after the other (merge, spec §6.6). [pageCounts] lists
         * the main document first. What counts as unmodified is the main document alone, so a merge
         * always has something to save.
         */
        fun ofDocuments(pageCounts: List<Int>): EditSession {
            val original = of(pageCounts.firstOrNull() ?: 0).pages
            val pages = pageCounts.flatMapIndexed { doc, count ->
                if (doc == 0) original else List(count) { PageItem.FromPdf(id = "d${doc}p$it", docRef = DocRef(doc), pageIndex = it) }
            }
            return EditSession(State(pages, FillContent()), original, emptyList(), emptyList())
        }

        /** Same as the map form, for a session with only the main document. */
        fun decode(encoded: String, pageCount: Int, fill: String = ""): EditSession? = decode(encoded, mapOf(DocRef.MAIN to pageCount), fill)

        /**
         * Restores what [encode] wrote. [pageCounts] gives the page count of every document the
         * session may use, [DocRef.MAIN] included: the original pages are rebuilt from the main
         * one. [fill] is what [encodeFill] wrote. Null if the strings don't match these documents.
         */
        fun decode(encoded: String, pageCounts: Map<DocRef, Int>, fill: String = ""): EditSession? {
            val mainCount = pageCounts[DocRef.MAIN] ?: return null
            if (encoded.isEmpty()) return null
            val pages = encoded.split(SEPARATOR).map { entry ->
                decodePage(entry.split(FIELD).map { URLDecoder.decode(it, UTF_8) }, pageCounts) ?: return null
            }
            if (pages.map { it.id }.toSet().size != pages.size) return null
            val content = decodeFill(fill) ?: return null
            return EditSession(State(pages, content), of(mainCount).pages, emptyList(), emptyList())
        }

        private fun decodeFill(fill: String): FillContent? {
            if (fill.isEmpty()) return FillContent()
            return try {
                json.decodeFromString(FillContent.serializer(), fill)
            } catch (e: IllegalArgumentException) {
                // SerializationException is one, and so is an overlay box failing its own checks.
                null
            }
        }

        private fun decodePage(f: List<String>, pageCounts: Map<DocRef, Int>): PageItem? {
            val rotation = f.lastOrNull()?.toIntOrNull() ?: return null
            if (rotation % PageItem.QUARTER_TURN != 0) return null
            val turned = Math.floorMod(rotation, PageItem.FULL_TURN)
            return when (f.firstOrNull()) {
                PDF -> {
                    if (f.size != 5) return null
                    val doc = DocRef(f[2].toIntOrNull() ?: return null)
                    val pageIndex = f[3].toIntOrNull() ?: return null
                    val count = pageCounts[doc] ?: return null
                    if (pageIndex !in 0 until count) return null
                    PageItem.FromPdf(f[1], doc, pageIndex, turned)
                }
                BLANK -> {
                    if (f.size != 5) return null
                    val (w, h) = positiveSize(f[2], f[3]) ?: return null
                    PageItem.Blank(f[1], w, h, turned)
                }
                IMAGE -> {
                    if (f.size != 7) return null
                    val mode = ImageFit.entries.firstOrNull { it.name == f[3] } ?: return null
                    val (w, h) = positiveSize(f[4], f[5]) ?: return null
                    PageItem.FromImage(f[1], f[2], mode, w, h, turned)
                }
                else -> null
            }
        }

        private fun positiveSize(width: String, height: String): Pair<Float, Float>? {
            val w = width.toFloatOrNull() ?: return null
            val h = height.toFloatOrNull() ?: return null
            return if (w > 0f && h > 0f && w.isFinite() && h.isFinite()) w to h else null
        }
    }
}
