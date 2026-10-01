package com.marcogn.pdftoolkit.domain.edit

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

    /** The pages as a compact string, to survive process death through `SavedStateHandle`. History is not kept. */
    fun encode(): String = pages.joinToString(SEPARATOR) { page ->
        when (page) {
            is PageItem.FromPdf -> listOf(page.id, page.docRef.id, page.pageIndex, page.rotation).joinToString(FIELD)
        }
    }

    companion object {
        private const val MAX_HISTORY = 100
        private const val SEPARATOR = ";"
        private const val FIELD = ","

        /** Session over the [pageCount] pages of the document, in their original order. */
        fun of(pageCount: Int): EditSession {
            val pages = List(pageCount) { PageItem.FromPdf(id = "p$it", docRef = DocRef.MAIN, pageIndex = it) }
            return EditSession(pages, pages, emptyList(), emptyList())
        }

        /**
         * Restores what [encode] wrote, over the same document: [original] pages are rebuilt from
         * [pageCount]. Null if the string doesn't match this document.
         */
        fun decode(encoded: String, pageCount: Int): EditSession? {
            val base = of(pageCount)
            if (encoded.isEmpty()) return null
            val pages = encoded.split(SEPARATOR).map { entry ->
                val fields = entry.split(FIELD)
                if (fields.size != 4) return null
                val pageIndex = fields[2].toIntOrNull() ?: return null
                val rotation = fields[3].toIntOrNull() ?: return null
                val doc = fields[1].toIntOrNull() ?: return null
                if (doc != DocRef.MAIN.id || pageIndex !in 0 until pageCount || rotation % PageItem.QUARTER_TURN != 0) return null
                PageItem.FromPdf(fields[0], DocRef(doc), pageIndex, Math.floorMod(rotation, PageItem.FULL_TURN))
            }
            if (pages.map { it.id }.toSet().size != pages.size) return null
            return EditSession(pages, base.pages, emptyList(), emptyList())
        }
    }
}
