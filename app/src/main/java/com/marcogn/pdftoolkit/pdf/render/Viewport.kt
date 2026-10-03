package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * What part of the document is on screen.
 *
 * [zoom] multiplies layout pixels ([DocumentLayout]); 1 is fit width. [offset] is the position of
 * the viewport's top-left corner in zoomed content pixels, so
 * `screen = layout * zoom - offset` (see [PageCoordinateMapper]).
 */
data class Viewport(val zoom: Float = 1f, val offset: Offset = Offset.Zero)

/**
 * Limits of zoom and pan for a document of [contentSize] layout pixels shown in [viewportSize]
 * screen pixels. Pure functions: the Compose state holder applies them on every gesture frame.
 */
class ViewportBounds(
    val contentSize: Size,
    val viewportSize: Size,
    val minZoom: Float,
    val maxZoom: Float,
) {
    init {
        require(minZoom in 0f..maxZoom) { "minZoom $minZoom must be in 0..$maxZoom" }
    }

    /**
     * Allowed offsets on one axis at [zoom]. When the zoomed content is smaller than the
     * viewport the range collapses to the single value that centres it (a negative offset).
     */
    fun offsetRange(zoom: Float, horizontal: Boolean): ClosedFloatingPointRange<Float> {
        val content = (if (horizontal) contentSize.width else contentSize.height) * zoom
        val view = if (horizontal) viewportSize.width else viewportSize.height
        return if (content <= view) {
            val centred = -(view - content) / 2f
            centred..centred
        } else {
            0f..(content - view)
        }
    }

    fun clamp(viewport: Viewport): Viewport {
        val zoom = viewport.zoom.coerceIn(minZoom, maxZoom)
        return Viewport(
            zoom,
            Offset(
                viewport.offset.x.coerceIn(offsetRange(zoom, horizontal = true)),
                viewport.offset.y.coerceIn(offsetRange(zoom, horizontal = false)),
            ),
        )
    }

    /**
     * Multiplies the zoom by [factor] keeping the content under [focus] (screen pixels) still,
     * as a pinch does, then clamps. At a zoom limit the factor is cut, so the focus stays put.
     */
    fun zoomAround(viewport: Viewport, focus: Offset, factor: Float): Viewport {
        val newZoom = (viewport.zoom * factor).coerceIn(minZoom, maxZoom)
        val content = (focus + viewport.offset) / viewport.zoom
        return clamp(Viewport(newZoom, content * newZoom - focus))
    }

    /** Moves the content by [delta] screen pixels (a finger dragging by [delta]), then clamps. */
    fun panBy(viewport: Viewport, delta: Offset): Viewport =
        clamp(viewport.copy(offset = viewport.offset - delta))

    /**
     * Keeps the zoom and moves so that [layoutPoint] (layout pixels, see [DocumentLayout]) is at the
     * centre of the viewport, as far as the limits allow: a search result is brought to the middle
     * of the screen (spec §5.1).
     */
    fun centerOn(viewport: Viewport, layoutPoint: Offset): Viewport =
        clamp(Viewport(viewport.zoom, layoutPoint * viewport.zoom - Offset(viewportSize.width / 2f, viewportSize.height / 2f)))

    /**
     * Double tap (spec §4.2): from fit width to [DOUBLE_TAP_ZOOM] around the tapped point, from
     * any other zoom back to fit width (zoom 1). The tapped content stays under the finger as far
     * as the limits allow.
     */
    fun doubleTapTarget(viewport: Viewport, tap: Offset): Viewport {
        val target = if (abs(viewport.zoom - 1f) < FIT_WIDTH_TOLERANCE) DOUBLE_TAP_ZOOM else 1f
        return zoomAround(viewport, tap, target / viewport.zoom)
    }

    companion object {
        /** Spec §4.2. */
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f

        /** Within this distance from 1, the zoom counts as fit width for the double tap. */
        const val FIT_WIDTH_TOLERANCE = 0.05f
    }
}
