package com.marcogn.pdftoolkit.ui.viewer

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.ui.fill.OverlayPainter

/**
 * One form control to lay over the viewport (plan V-c): widget [widgetIndex] of [field], on document
 * page [page], at [rect] in screen pixels; [rotation] (clockwise, quarter turns) is the direction its
 * text runs on screen, so on a turned page the control turns with it.
 */
data class PlacedControl(val field: FormField, val widgetIndex: Int, val page: Int, val rect: Rect, val rotation: Int)

/**
 * Where fill content sits in a viewport of several pages (plan V-c): form controls, overlays, the
 * corner handle and what a finger touches. Pure geometry over [PageCoordinateMapper]: [mapper] lays
 * out the pages of a viewport whose first page is document page [pageIndexOffset] (single-page mode
 * has one page per viewport), and [spaceOf] gives a document page's user space (null if unknown).
 * Overlays carry their page as the session's page id ([ViewerEditSession.pageId]).
 */
class ViewportFill(
    private val mapper: PageCoordinateMapper,
    private val pageIndexOffset: Int,
    private val spaceOf: (documentPage: Int) -> PdfPageSpace?,
) {

    /** The viewport's index of document page [page], or null if this viewport doesn't lay it out. */
    private fun local(page: Int): Int? = (page - pageIndexOffset).takeIf { it in mapper.layout.pageRects.indices }

    /** Document pages at least partly on a screen of [viewportSize]. */
    fun visiblePages(viewportSize: Size): IntRange {
        val top = mapper.screenToLayout(Offset.Zero).y
        val bottom = mapper.screenToLayout(Offset(0f, viewportSize.height)).y
        val local = mapper.layout.pagesBetween(top, bottom)
        return if (local.isEmpty()) IntRange.EMPTY else (local.first + pageIndexOffset)..(local.last + pageIndexOffset)
    }

    /**
     * The controls of the widgets on the visible pages: [fieldsOn] gives the fields with a widget on a
     * document page, [include] which of them to show.
     */
    fun controls(viewportSize: Size, fieldsOn: (documentPage: Int) -> List<FormField>, include: (FormField) -> Boolean = { true }): List<PlacedControl> {
        val placed = mutableListOf<PlacedControl>()
        for (page in visiblePages(viewportSize)) {
            val local = local(page) ?: continue
            val space = spaceOf(page) ?: continue
            // Field text is upright in user space: on screen it runs at the page's rotation.
            val rotation = space.displayAngle(0f).toInt()
            for (field in fieldsOn(page)) {
                if (!include(field)) continue
                field.widgets.forEachIndexed { index, widget ->
                    if (widget.pageIndex == page) placed += PlacedControl(field, index, page, mapper.userRectToScreen(local, space, widget.rect), rotation)
                }
            }
        }
        return placed
    }

    /** The local space of [overlay] → screen pixels; null if its page isn't in this viewport. */
    fun overlayToScreen(overlay: Overlay): Affine? {
        val page = ViewerEditSession.pageIndexOf(overlay.pageId) ?: return null
        val local = local(page) ?: return null
        val space = spaceOf(page) ?: return null
        return mapper.overlayToScreen(local, space, overlay.box)
    }

    /** A screen point in the user space of document page [page]; null if the page isn't in this viewport. */
    fun toUser(page: Int, screen: Offset): Offset? {
        val local = local(page) ?: return null
        val space = spaceOf(page) ?: return null
        return mapper.screenToUser(local, space, screen)
    }

    /**
     * The topmost of [overlays] under [screen]: on the page under the finger, the last one whose box
     * contains the point (the one drawn on top). Null in the gaps and beside every overlay.
     */
    fun overlayAt(overlays: List<Overlay>, screen: Offset): Overlay? {
        val hit = mapper.hitTest(screen) ?: return null
        val page = hit.pageIndex + pageIndexOffset
        val user = toUser(page, screen) ?: return null
        val pageId = ViewerEditSession.pageId(page)
        return overlays.lastOrNull { it.pageId == pageId && OverlayGeometry.contains(it.box, user) }
    }

    /**
     * Whether a touch at [screen] grabs [overlay], within [marginPx] screen pixels of its box, so a
     * small tick is easy to hit. Measured in the overlay's own page, so a margin that reaches into
     * the gap or the next page still counts.
     */
    fun grabs(overlay: Overlay, screen: Offset, marginPx: Float): Boolean {
        val page = ViewerEditSession.pageIndexOf(overlay.pageId) ?: return false
        val user = toUser(page, screen) ?: return false
        return OverlayGeometry.contains(overlay.box, user, marginPx / mapper.screenPxPerPoint)
    }

    /** The screen position of [overlay]'s handle: the bottom-right corner of its box as drawn. */
    fun handle(overlay: Overlay): Offset? = overlayToScreen(overlay)?.map(Offset(overlay.box.width, overlay.box.height))
}

/**
 * Fill and sign in a [PdfViewport] (plan V-c): [overlays] of the session (drawn on any page, armed or
 * not) and the form controls of [fieldsOn] with their [values]. With [editing] ("Fill and sign" armed)
 * every control on screen takes input, a tap or long press reaches the overlays and the [selected] one
 * shows its handle; otherwise only fields with a pending value show, as pictures.
 *
 * [grabbing]: overlays can be moved (no placement tool armed). [images] are the decoded pictures of
 * image overlays by file; [painter] draws overlays as the PDF writer will.
 */
class ViewportFillContent(
    val overlays: List<Overlay>,
    val images: Map<String, Bitmap>,
    val painter: OverlayPainter,
    val spaceOf: (documentPage: Int) -> PdfPageSpace?,
    val fieldsOn: (documentPage: Int) -> List<FormField>,
    val values: Map<String, FieldValue>,
    val editing: Boolean,
    val grabbing: Boolean,
    val selected: String?,
    val onSelect: (overlayId: String) -> Unit,
    val onOverlayChange: (Overlay) -> Unit,
    val onFieldChange: (FormField, FieldValue, typing: Boolean) -> Unit,
)
