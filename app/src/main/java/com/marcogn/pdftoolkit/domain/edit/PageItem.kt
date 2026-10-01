package com.marcogn.pdftoolkit.domain.edit

/** Which source document a page comes from. Phase 3 adds PDFs picked later; [MAIN] is the open one. */
@JvmInline
value class DocRef(val id: Int) {
    companion object {
        val MAIN = DocRef(0)
    }
}

/**
 * One page of the document being edited (spec §6.1). Phase 3 adds the blank and image variants.
 */
sealed interface PageItem {
    /** Stable for the whole session: animations, drag & drop and, later, overlays bind to it. */
    val id: String
    val rotation: Int

    /**
     * Page [pageIndex] (zero-based) of [docRef]. [rotation] is the quarter-turn clockwise rotation
     * the user added on top of the one the page already has: 0, 90, 180 or 270.
     */
    data class FromPdf(
        override val id: String,
        val docRef: DocRef,
        val pageIndex: Int,
        override val rotation: Int = 0,
    ) : PageItem

    fun withRotation(rotation: Int): PageItem = when (this) {
        is FromPdf -> copy(rotation = Math.floorMod(rotation, FULL_TURN))
    }

    companion object {
        const val QUARTER_TURN = 90
        const val FULL_TURN = 360
    }
}
