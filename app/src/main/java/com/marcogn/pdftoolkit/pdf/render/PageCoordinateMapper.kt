package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** A point on a page: [point] in page points, origin top-left (see [PageCoordinateMapper]). */
data class PagePoint(val pageIndex: Int, val point: Offset)

/**
 * The single place that converts between page coordinates and screen pixels (spec §12).
 *
 * Coordinate spaces:
 * - **page points**: 1/72", origin at the **top-left** of the page as displayed, y down. This is
 *   the space `PdfRenderer` draws in, with the page rotation already applied.
 * - **layout pixels**: the document at zoom 1 ([DocumentLayout]).
 * - **screen pixels**: the viewport, `layout * zoom - offset` ([Viewport]).
 *
 * Phase 1 needs only these. Phases 4 and 5 extend this class with PDF user space (origin
 * bottom-left, y up) and the page `/Rotate`, so that PdfBox coordinates go through here too.
 *
 * Immutable: build a new one when the layout or the viewport changes (it is two references).
 */
class PageCoordinateMapper(val layout: DocumentLayout, val viewport: Viewport) {

    /** Screen pixels per page point at the current zoom. */
    val screenPxPerPoint: Float get() = layout.pxPerPoint * viewport.zoom

    fun layoutToScreen(point: Offset): Offset = point * viewport.zoom - viewport.offset

    fun screenToLayout(point: Offset): Offset = (point + viewport.offset) / viewport.zoom

    fun pageToScreen(pageIndex: Int, point: Offset): Offset {
        val page = layout.pageRects[pageIndex]
        return layoutToScreen(page.topLeft + point * layout.pxPerPoint)
    }

    /** Inverse of [pageToScreen]; the result can fall outside the page. */
    fun screenToPage(pageIndex: Int, point: Offset): Offset {
        val page = layout.pageRects[pageIndex]
        return (screenToLayout(point) - page.topLeft) / layout.pxPerPoint
    }

    fun pageRectToScreen(pageIndex: Int, rect: Rect): Rect =
        Rect(pageToScreen(pageIndex, rect.topLeft), pageToScreen(pageIndex, rect.bottomRight))

    /** The whole page on screen. */
    fun pageBoundsOnScreen(pageIndex: Int): Rect {
        val page = layout.pageRects[pageIndex]
        return Rect(layoutToScreen(page.topLeft), layoutToScreen(page.bottomRight))
    }

    /** The page under a screen point, or null in the gaps and outside the pages. */
    fun hitTest(point: Offset): PagePoint? {
        val p = screenToLayout(point)
        val index = layout.pageAt(p.y)
        val page = layout.pageRects[index]
        if (!page.contains(p)) return null
        return PagePoint(index, (p - page.topLeft) / layout.pxPerPoint)
    }
}
