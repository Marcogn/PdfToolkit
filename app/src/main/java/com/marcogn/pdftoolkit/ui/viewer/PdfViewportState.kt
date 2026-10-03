package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import com.marcogn.pdftoolkit.pdf.render.DocumentLayout
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.Viewport
import com.marcogn.pdftoolkit.pdf.render.ViewportBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A reading position that survives a change of viewport size (rotation) and process death: the
 * point at the top centre of the screen, as page index plus fractions of that page and of the
 * content width, and the zoom.
 */
data class ViewportAnchor(val pageIndex: Int, val pageFractionY: Float, val contentFractionX: Float, val zoom: Float)

/**
 * Zoom and pan state of the continuous viewer. Holds the [DocumentLayout] for the current
 * viewport size and applies [ViewportBounds] on every change, so [viewport] is always valid.
 *
 * Gestures call [panBy] and [zoomBy] directly; animations (fling, double tap) run as a single
 * job that any new touch cancels. [isInteracting] is true while a finger is down or an animation
 * runs: the viewer waits for it to go false (plus a debounce) before asking for sharp tiles.
 */
@Stable
class PdfViewportState(initialAnchor: ViewportAnchor? = null) {

    var layout: DocumentLayout? by mutableStateOf(null)
        private set
    var viewport: Viewport by mutableStateOf(Viewport())
        private set
    var viewportSize: Size by mutableStateOf(Size.Zero)
        private set
    var isInteracting: Boolean by mutableStateOf(false)
        internal set

    private var bounds: ViewportBounds? = null
    private var pendingAnchor: ViewportAnchor? = initialAnchor
    private var pendingReveal: Pair<Int, Rect>? = null
    private var animationJob: Job? = null

    val mapper: PageCoordinateMapper?
        get() = layout?.let { PageCoordinateMapper(it, viewport) }

    /**
     * Called on every layout pass. Rebuilds the layout when the size or the pages change and
     * keeps the reading position ([ViewportAnchor]).
     */
    fun setContent(pageSizes: List<PageSize>, size: Size, gapPx: Float) {
        if (size.width <= 0f || size.height <= 0f) return
        val current = layout
        if (current != null && current.pageSizes == pageSizes && size == viewportSize) return

        val anchor = pendingAnchor ?: currentAnchor()
        val newLayout = DocumentLayout.continuous(pageSizes, size.width, gapPx)
        val newBounds = ViewportBounds(
            contentSize = newLayout.contentSize,
            viewportSize = size,
            minZoom = newLayout.fitPageZoom(size.height),
            maxZoom = ViewportBounds.MAX_ZOOM,
        )
        stopAnimation()
        layout = newLayout
        bounds = newBounds
        viewportSize = size
        viewport = if (anchor != null) restore(anchor, newLayout, newBounds) else newBounds.clamp(Viewport())
        pendingAnchor = null
        pendingReveal?.let { (pageIndex, rect) ->
            pendingReveal = null
            centerOnPageRect(pageIndex, rect)
        }
    }

    /**
     * Page under the centre of the screen, or -1 before the first layout. Reads snapshot state, so
     * `snapshotFlow { currentPage }` emits when the reader moves to another page.
     */
    val currentPage: Int
        get() {
            val layout = layout ?: return -1
            val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            return layout.pageAt(PageCoordinateMapper(layout, viewport).screenToLayout(centre).y)
        }

    /**
     * Brings the top of page [index] to the top of the screen, keeping the zoom and the horizontal
     * position. Before the first layout it is remembered and applied then.
     */
    fun jumpToPage(index: Int) {
        val layout = layout
        val bounds = bounds
        if (layout == null || bounds == null) {
            pendingAnchor = ViewportAnchor(index, 0f, 0.5f, pendingAnchor?.zoom ?: 1f)
            return
        }
        stopAnimation()
        val page = layout.pageRects[index.coerceIn(0, layout.pageCount - 1)]
        val zoom = viewport.zoom
        viewport = bounds.clamp(Viewport(zoom, Offset(viewport.offset.x, (page.top - layout.gap) * zoom)))
    }

    /**
     * Brings [rect] (page points, top-left origin) of page [index] to the centre of the screen,
     * keeping the zoom (a search result, spec §5.1). Before the first layout it is remembered and
     * applied then.
     */
    fun centerOnPageRect(index: Int, rect: Rect) {
        val layout = layout
        val bounds = bounds
        if (layout == null || bounds == null) {
            pendingReveal = index to rect
            return
        }
        stopAnimation()
        val page = layout.pageRects[index.coerceIn(0, layout.pageCount - 1)]
        viewport = bounds.centerOn(viewport, page.topLeft + rect.center * layout.pxPerPoint)
    }

    fun currentAnchor(): ViewportAnchor? {
        val layout = layout ?: return null
        val point = PageCoordinateMapper(layout, viewport).screenToLayout(Offset(viewportSize.width / 2f, 0f))
        val index = layout.pageAt(point.y)
        val page = layout.pageRects[index]
        return ViewportAnchor(
            pageIndex = index,
            pageFractionY = (point.y - page.top) / page.height,
            contentFractionX = point.x / layout.contentSize.width,
            zoom = viewport.zoom,
        )
    }

    private fun restore(anchor: ViewportAnchor, layout: DocumentLayout, bounds: ViewportBounds): Viewport {
        val page = layout.pageRects[anchor.pageIndex.coerceIn(0, layout.pageCount - 1)]
        val point = Offset(anchor.contentFractionX * layout.contentSize.width, page.top + anchor.pageFractionY * page.height)
        val zoom = anchor.zoom.coerceIn(bounds.minZoom, bounds.maxZoom)
        return bounds.clamp(Viewport(zoom, point * zoom - Offset(bounds.viewportSize.width / 2f, 0f)))
    }

    fun panBy(delta: Offset) {
        val bounds = bounds ?: return
        viewport = bounds.panBy(viewport, delta)
    }

    fun zoomBy(factor: Float, focus: Offset) {
        val bounds = bounds ?: return
        viewport = bounds.zoomAround(viewport, focus, factor)
    }

    fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    /** Runs [block] as the only animation; a later animation or [stopAnimation] cancels it. */
    fun launchAnimation(scope: CoroutineScope, block: suspend () -> Unit) {
        stopAnimation()
        isInteracting = true
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } finally {
                if (animationJob === job) {
                    animationJob = null
                    isInteracting = false
                }
            }
        }
        animationJob = job
    }

    /** Fling after a pan; each axis decays on its own and stops at its edge. */
    suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) = coroutineScope {
        launch { flingAxis(-velocity.x, decay, horizontal = true) }
        launch { flingAxis(-velocity.y, decay, horizontal = false) }
    }

    private suspend fun flingAxis(velocity: Float, decay: DecayAnimationSpec<Float>, horizontal: Boolean) {
        if (abs(velocity) < MIN_FLING_VELOCITY) return
        val start = if (horizontal) viewport.offset.x else viewport.offset.y
        AnimationState(initialValue = start, initialVelocity = velocity).animateDecay(decay) {
            val bounds = bounds
            if (bounds == null) {
                cancelAnimation()
                return@animateDecay
            }
            val clamped = value.coerceIn(bounds.offsetRange(viewport.zoom, horizontal))
            viewport = viewport.copy(
                offset = if (horizontal) viewport.offset.copy(x = clamped) else viewport.offset.copy(y = clamped),
            )
            if (clamped != value) cancelAnimation()
        }
    }

    /**
     * Double tap (spec §4.2). Zoom and the screen position of the tapped content are interpolated
     * together, so the tapped point slides smoothly to where it ends up after the limits.
     */
    suspend fun animateDoubleTap(tap: Offset) {
        val bounds = bounds ?: return
        val from = viewport
        val to = bounds.doubleTapTarget(from, tap)
        val content = (tap + from.offset) / from.zoom
        val tapEnd = content * to.zoom - to.offset
        animate(0f, 1f, animationSpec = tween(DOUBLE_TAP_MS, easing = FastOutSlowInEasing)) { t, _ ->
            val zoom = lerp(from.zoom, to.zoom, t)
            val screen = lerp(tap, tapEnd, t)
            viewport = bounds.clamp(Viewport(zoom, content * zoom - screen))
        }
    }

    companion object {
        private const val DOUBLE_TAP_MS = 250
        private const val MIN_FLING_VELOCITY = 50f

        val Saver: Saver<PdfViewportState, List<Float>> = Saver(
            save = { state ->
                val anchor = state.currentAnchor() ?: state.pendingAnchor
                anchor?.let { listOf(it.pageIndex.toFloat(), it.pageFractionY, it.contentFractionX, it.zoom) }
            },
            restore = { saved ->
                PdfViewportState(ViewportAnchor(saved[0].toInt(), saved[1], saved[2], saved[3]))
            },
        )
    }
}
