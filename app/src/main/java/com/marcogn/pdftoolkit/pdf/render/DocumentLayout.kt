package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/** Page size in PDF points (1/72"), as reported by `PdfRenderer.Page`, page rotation already applied. */
data class PageSize(val width: Float, val height: Float) {
    init {
        require(width > 0f && height > 0f) { "Page size must be positive: $width x $height" }
    }
}

/**
 * Where every page sits in the document, in **layout pixels**: screen pixels at zoom 1.
 *
 * Continuous mode: pages stacked vertically with [gap] between them and above the first / below
 * the last page. One scale for the whole document ([pxPerPoint]): the widest page fills the
 * viewport width, so pages keep their relative sizes (mixed A4/Letter) and narrower ones are
 * centred. Zoom 1 is therefore "fit width".
 *
 * Built from the page sizes read at opening, so placeholders already have the right proportions
 * and the scroll position doesn't jump when bitmaps arrive (spec §5).
 */
class DocumentLayout private constructor(
    val pageSizes: List<PageSize>,
    val pxPerPoint: Float,
    val gap: Float,
    val pageRects: List<Rect>,
    val contentSize: Size,
) {
    val pageCount: Int get() = pageRects.size

    /**
     * Pages that intersect the vertical band [top, bottom) in layout pixels, in order.
     * Empty when the band falls entirely in a gap or outside the content.
     */
    fun pagesBetween(top: Float, bottom: Float): IntRange {
        if (pageRects.isEmpty() || bottom <= top) return IntRange.EMPTY
        // First page whose bottom edge is below `top`.
        var lo = 0
        var hi = pageRects.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pageRects[mid].bottom <= top) lo = mid + 1 else hi = mid
        }
        val first = lo
        var last = first - 1
        while (last + 1 < pageRects.size && pageRects[last + 1].top < bottom) last++
        return if (last >= first) first..last else IntRange.EMPTY
    }

    /** Page at the vertical layout coordinate [y]; in a gap, the page above it; clamped to the document. */
    fun pageAt(y: Float): Int {
        var lo = 0
        var hi = pageRects.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pageRects[mid].top <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Zoom at which the tallest page, with its gaps, fits the viewport height ("fit page").
     * Never above 1: on a portrait phone fit width already shows the whole page.
     */
    fun fitPageZoom(viewportHeight: Float): Float {
        val tallest = pageRects.maxOf { it.height } + 2 * gap
        return min(1f, viewportHeight / tallest)
    }

    companion object {
        fun continuous(pageSizes: List<PageSize>, viewportWidth: Float, gap: Float): DocumentLayout {
            require(pageSizes.isNotEmpty()) { "A document has at least one page" }
            require(viewportWidth > 0f) { "Viewport width must be positive" }
            val widest = pageSizes.maxOf { it.width }
            val pxPerPoint = viewportWidth / widest
            var y = gap
            val rects = pageSizes.map { size ->
                val w = size.width * pxPerPoint
                val h = size.height * pxPerPoint
                val left = (viewportWidth - w) / 2f
                Rect(left, y, left + w, y + h).also { y += h + gap }
            }
            return DocumentLayout(pageSizes, pxPerPoint, gap, rects, Size(viewportWidth, max(y, gap)))
        }
    }
}
