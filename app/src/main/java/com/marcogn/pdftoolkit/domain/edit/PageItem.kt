package com.marcogn.pdftoolkit.domain.edit

/** Which source document a page comes from. [MAIN] is the open one; the others are PDFs added later (spec §6.1). */
@JvmInline
value class DocRef(val id: Int) {
    companion object {
        val MAIN = DocRef(0)
    }
}

/** How an image fills its page (spec §6.2). */
enum class ImageFit {
    /** The page has the size of the reference page; the image is scaled to fit it, centred, no cropping. */
    FIT_PAGE,

    /** The page takes the size of the image (pixels to points through the image's DPI). */
    ORIGINAL_SIZE,
}

/** A page size in PDF points (1/72 inch), as the page appears before any rotation the user added. */
data class SizePt(val width: Float, val height: Float) {
    val isLandscape: Boolean get() = width > height

    /** The size as seen after turning the page by [rotation] degrees (a multiple of 90). */
    fun rotated(rotation: Int): SizePt = if (rotation % PageItem.HALF_TURN != 0) SizePt(height, width) else this
}

/**
 * One page of the document being edited (spec §6.1).
 *
 * [rotation] is the quarter-turn clockwise rotation the user added: for pages of a PDF it comes on
 * top of the rotation the page already has, for blank and image pages it is the whole rotation.
 */
sealed interface PageItem {
    /** Stable for the whole session: animations, drag & drop and, later, overlays bind to it. */
    val id: String
    val rotation: Int

    /** Page [pageIndex] (zero-based) of [docRef]. */
    data class FromPdf(
        override val id: String,
        val docRef: DocRef,
        val pageIndex: Int,
        override val rotation: Int = 0,
    ) : PageItem

    /** An empty page of the given size in points. */
    data class Blank(
        override val id: String,
        val widthPt: Float,
        val heightPt: Float,
        override val rotation: Int = 0,
    ) : PageItem

    /**
     * A page showing the image at [imageUri] (a copy the app owns, see `ImageImporter`). The page
     * is [widthPt] x [heightPt] points, already computed from [mode] when the page was added.
     */
    data class FromImage(
        override val id: String,
        val imageUri: String,
        val mode: ImageFit,
        val widthPt: Float,
        val heightPt: Float,
        override val rotation: Int = 0,
    ) : PageItem

    fun withRotation(rotation: Int): PageItem {
        val turned = Math.floorMod(rotation, FULL_TURN)
        return when (this) {
            is FromPdf -> copy(rotation = turned)
            is Blank -> copy(rotation = turned)
            is FromImage -> copy(rotation = turned)
        }
    }

    companion object {
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val FULL_TURN = 360
    }
}
