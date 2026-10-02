package com.marcogn.pdftoolkit.domain.edit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where new pages go (spec §6.2): at the start, at the end, or next to the page with this 1-based [pageNumber]. */
data class InsertionPoint(val kind: Kind, val pageNumber: Int = 1) {
    enum class Kind { START, END, BEFORE_PAGE, AFTER_PAGE }

    /** Index in a list of [pageCount] pages where the new pages start; a page number out of range is clamped. */
    fun toIndex(pageCount: Int): Int = when (kind) {
        Kind.START -> 0
        Kind.END -> pageCount
        Kind.BEFORE_PAGE -> (pageNumber - 1).coerceIn(0, pageCount)
        Kind.AFTER_PAGE -> pageNumber.coerceIn(0, pageCount)
    }

    companion object {
        val END_OF_DOCUMENT = InsertionPoint(Kind.END)
    }
}

/** An image's size in pixels (as it appears, EXIF orientation already applied) and its DPI if the file says. */
data class ImageDimensions(val widthPx: Int, val heightPx: Int, val dpi: Float? = null)

/** Where the image goes on its page, in points from the bottom-left corner (PDF convention). */
data class ImagePlacement(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The size rules of spec §6.2 for pages that are added, as pure functions so they can be tested
 * without a PDF. Sizes are in points; rotations the user added are taken into account where the
 * *visible* size matters (the page next to the insertion point).
 */
object PageSizing {
    const val POINTS_PER_INCH = 72f

    /** Used when the image carries no usable DPI (spec §6.2 [ASSUNZIONE]). */
    const val DEFAULT_DPI = 150f

    /**
     * DPIs below this are not taken from the metadata: cameras and phones routinely write 72, which
     * would turn a 4000 px photo into a 1.4 m page, the very problem the 150 DPI default avoids.
     */
    const val MIN_TRUSTED_DPI = 100f

    /** Long side, in pixels, images are scaled down to in [ImageFit.FIT_PAGE] (spec §6.2 [ASSUNZIONE]). */
    const val FIT_MAX_LONG_SIDE_PX = 3000

    /** Safety cap in [ImageFit.ORIGINAL_SIZE], where the spec sets none, to avoid decoding huge bitmaps. */
    const val ORIGINAL_MAX_LONG_SIDE_PX = 8000

    const val MIN_BLANK_PAGES = 1
    const val MAX_BLANK_PAGES = 50

    val A4 = SizePt(595.28f, 841.89f)

    /**
     * Size of the page next to [index], where the new pages will start: the one before it, or, at
     * the start of the document, the one after. This is the visible size, after the user's
     * rotation. [A4] if there is no page (not expected: a document always keeps one).
     */
    fun referenceSize(pages: List<PageItem>, index: Int, visibleSizeOf: (PageItem) -> SizePt?): SizePt {
        val neighbour = pages.getOrNull(index - 1) ?: pages.firstOrNull()
        return neighbour?.let(visibleSizeOf) ?: A4
    }

    /** Whether the pages of the document don't all share one size, so the dialog says which one is used. */
    fun hasMixedSizes(sizes: List<SizePt>): Boolean = sizes.map { it.width.roundToInt() to it.height.roundToInt() }.toSet().size > 1

    /** The page for [image] in [ImageFit.FIT_PAGE]: the reference size, turned to follow the image's orientation. */
    fun fitPageSize(image: ImageDimensions, reference: SizePt): SizePt {
        val long = max(reference.width, reference.height)
        val short = min(reference.width, reference.height)
        return when {
            image.widthPx > image.heightPx -> SizePt(long, short)
            image.widthPx < image.heightPx -> SizePt(short, long)
            else -> reference // square: no orientation of its own, keep the document's
        }
    }

    /** DPI to convert pixels to points: the file's if it is plausible, else [DEFAULT_DPI]. */
    fun effectiveDpi(dpi: Float?): Float = dpi?.takeIf { it >= MIN_TRUSTED_DPI && it.isFinite() } ?: DEFAULT_DPI

    /** The page for [image] in [ImageFit.ORIGINAL_SIZE]. */
    fun originalPageSize(image: ImageDimensions): SizePt {
        val factor = POINTS_PER_INCH / effectiveDpi(image.dpi)
        return SizePt(image.widthPx * factor, image.heightPx * factor)
    }

    /** The page size for [image] under [mode]. */
    fun pageSizeFor(image: ImageDimensions, mode: ImageFit, reference: SizePt): SizePt = when (mode) {
        ImageFit.FIT_PAGE -> fitPageSize(image, reference)
        ImageFit.ORIGINAL_SIZE -> originalPageSize(image)
    }

    /** Where the image is drawn on a [page]: scaled to fit keeping its proportions, centred, never cropped. */
    fun placement(image: ImageDimensions, page: SizePt): ImagePlacement {
        val scale = min(page.width / image.widthPx, page.height / image.heightPx)
        val width = image.widthPx * scale
        val height = image.heightPx * scale
        return ImagePlacement((page.width - width) / 2f, (page.height - height) / 2f, width, height)
    }

    /** The size, in pixels, to decode the image at: its long side is capped at what [mode] allows. */
    fun decodeSize(image: ImageDimensions, mode: ImageFit): Pair<Int, Int> {
        val cap = if (mode == ImageFit.FIT_PAGE) FIT_MAX_LONG_SIDE_PX else ORIGINAL_MAX_LONG_SIDE_PX
        val long = max(image.widthPx, image.heightPx)
        if (long <= cap) return image.widthPx to image.heightPx
        val scale = cap.toFloat() / long
        return max(1, (image.widthPx * scale).roundToInt()) to max(1, (image.heightPx * scale).roundToInt())
    }
}
