package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import com.marcogn.pdftoolkit.domain.fill.UserRect

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
 * - **PDF user space** (phase 4): the page's own coordinates, origin bottom-left, y up, before
 *   `/Rotate`; [PdfPageSpace] relates it to page points, so PdfBox coordinates go through here too.
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

    /** [pageToScreen] as a transform (a scale and a translation), for drawing with a matrix. */
    fun pageToScreenTransform(pageIndex: Int): Affine {
        val page = layout.pageRects[pageIndex]
        val scale = screenPxPerPoint
        return Affine(scale, 0f, 0f, scale, page.left * viewport.zoom - viewport.offset.x, page.top * viewport.zoom - viewport.offset.y)
    }

    // --- PDF user space (phase 4): [space] describes the page as displayed at [pageIndex]. ---

    /** User space of the page → screen pixels. */
    fun userToScreen(pageIndex: Int, space: PdfPageSpace): Affine = pageToScreenTransform(pageIndex) * space.userToDisplay

    fun userToScreen(pageIndex: Int, space: PdfPageSpace, point: Offset): Offset = userToScreen(pageIndex, space).map(point)

    fun screenToUser(pageIndex: Int, space: PdfPageSpace, point: Offset): Offset = userToScreen(pageIndex, space).inverse().map(point)

    /** A rectangle of user space on screen. Pages turn by quarter turns, so it stays axis-aligned. */
    fun userRectToScreen(pageIndex: Int, space: PdfPageSpace, rect: UserRect): Rect {
        val transform = userToScreen(pageIndex, space)
        val a = transform.map(Offset(rect.left, rect.bottom))
        val b = transform.map(Offset(rect.right, rect.top))
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    /** Local space of an overlay ([OverlayGeometry]) → screen pixels. */
    fun overlayToScreen(pageIndex: Int, space: PdfPageSpace, box: OverlayBox): Affine =
        userToScreen(pageIndex, space) * OverlayGeometry.localToUser(box)

    /** The page under a screen point, or null in the gaps and outside the pages. */
    fun hitTest(point: Offset): PagePoint? {
        val p = screenToLayout(point)
        val index = layout.pageAt(p.y)
        val page = layout.pageRects[index]
        if (!page.contains(p)) return null
        return PagePoint(index, (p - page.topLeft) / layout.pxPerPoint)
    }
}
