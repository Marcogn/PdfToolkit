package com.marcogn.pdftoolkit.pdf.annotations

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.DocumentLayout
import com.marcogn.pdftoolkit.pdf.render.Viewport

/**
 * Freehand strokes in a viewport that shows several pages (the viewer, plan V-a).
 *
 * Strokes are made in **document points**: the layout of the whole document ([DocumentLayout]) at one
 * unit per page point, so a page sits at `pageRect / pxPerPoint` and is only moved, never scaled or
 * turned, relative to this space. Brush sizes stay points of the page and the brush keeps its
 * on-screen orientation, as in the single-page pane (stroke space = display points of the page).
 *
 * The page a stroke belongs to is read from the stroke itself, at the height of its first point,
 * when it is finished: nothing has to be remembered between the start of a stroke and the moment the
 * ink library hands it over (it may hand over several strokes at once).
 */
object DocumentStrokes {

    /** Document points → screen pixels for [viewport]: the ink layer's pointer → stroke matrix is its inverse. */
    fun documentToScreen(layout: DocumentLayout, viewport: Viewport): Affine {
        val scale = layout.pxPerPoint * viewport.zoom
        return Affine(scale, 0f, 0f, scale, -viewport.offset.x, -viewport.offset.y)
    }

    /**
     * The page (index in [layout]) that a stroke starting at [first] (document points) belongs to:
     * the page at that height, or the page above when it starts in the gap between two pages
     * ([DocumentLayout.pageAt]). A stroke started beside a page belongs to it too; one that doesn't
     * touch its page at all is dropped later ([FreehandGeometry.touchesPage]).
     */
    fun pageOf(layout: DocumentLayout, first: Offset): Int = layout.pageAt(first.y * layout.pxPerPoint)

    /** Document points → display points of page [pageIndex] (page points, origin top-left). */
    fun documentToPage(layout: DocumentLayout, pageIndex: Int): Affine {
        val page = layout.pageRects[pageIndex]
        return Affine.translate(-page.left / layout.pxPerPoint, -page.top / layout.pxPerPoint)
    }

    /**
     * [stroke] (document points) as a stroke of the page it belongs to, in that page's display points,
     * with the page index; null for a stroke without points.
     */
    fun onPage(layout: DocumentLayout, stroke: FreehandStroke): Pair<Int, FreehandStroke>? {
        val first = stroke.centre.firstOrNull() ?: return null
        val page = pageOf(layout, first)
        val toPage = documentToPage(layout, page)
        return page to stroke.copy(
            centre = stroke.centre.map(toPage::map),
            outlines = stroke.outlines.map { outline -> outline.map(toPage::map) },
        )
    }
}
