package com.marcogn.pdftoolkit.domain.edit

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The page list being edited, kept in memory and never written until the user saves (spec §6.1).
 * Immutable: every operation returns a new session, with the previous page list pushed on the
 * undo stack, so undo/redo are just a swap of lists.
 *
 * [original] is the list the session started from; [isModified] compares against it, so undoing
 * everything (or moving a page away and back) leaves nothing to save.
 */
class EditSession private constructor(
    val pages: List<PageItem>,
    private val original: List<PageItem>,
    private val undoStack: List<List<PageItem>>,
    private val redoStack: List<List<PageItem>>,
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isModified: Boolean get() = pages != original
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
        return EditSession(undoStack.last(), original, undoStack.dropLast(1), redoStack + listOf(pages))
    }

    fun redo(): EditSession {
        if (redoStack.isEmpty()) return this
        return EditSession(redoStack.last(), original, undoStack + listOf(pages), redoStack.dropLast(1))
    }

    private fun change(newPages: List<PageItem>) =
        EditSession(newPages, original, (undoStack + listOf(pages)).takeLast(MAX_HISTORY), emptyList())

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
            return EditSession(pages, pages, emptyList(), emptyList())
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
            return EditSession(pages, original, emptyList(), emptyList())
        }

        /** Same as the two-argument form, for a session with only the main document. */
        fun decode(encoded: String, pageCount: Int): EditSession? = decode(encoded, mapOf(DocRef.MAIN to pageCount))

        /**
         * Restores what [encode] wrote. [pageCounts] gives the page count of every document the
         * session may use, [DocRef.MAIN] included: the original pages are rebuilt from the main
         * one. Null if the string doesn't match these documents.
         */
        fun decode(encoded: String, pageCounts: Map<DocRef, Int>): EditSession? {
            val mainCount = pageCounts[DocRef.MAIN] ?: return null
            if (encoded.isEmpty()) return null
            val pages = encoded.split(SEPARATOR).map { entry ->
                decodePage(entry.split(FIELD).map { URLDecoder.decode(it, UTF_8) }, pageCounts) ?: return null
            }
            if (pages.map { it.id }.toSet().size != pages.size) return null
            return EditSession(pages, of(mainCount).pages, emptyList(), emptyList())
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
