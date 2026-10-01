package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.pdf.render.RenderPlanner
import com.marcogn.pdftoolkit.pdf.render.RenderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Space between pages, and above the first and below the last one. */
private val PageGap = 8.dp

/** Spec §5: the sharp re-render starts when the zoom has been still for about 150 ms. */
private const val SETTLE_DELAY_MS = 150L

/**
 * Continuous viewer (spec §4.2, §5): pages stacked vertically, pinch zoom, double tap, pan with
 * fling. Draws on a single Canvas only the pages on screen: white placeholder with the page
 * proportions, the page bitmap scaled to the current zoom and, once the zoom settles, the sharp
 * tiles of the visible area on top.
 */
@Composable
fun PdfViewport(
    pageSizes: List<PageSize>,
    bitmaps: RenderScheduler<ImageBitmap>,
    budget: RenderBudget,
    state: PdfViewportState,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    /** See [RenderPlanner.pageIndexOffset]: [pageSizes] is a slice of the document starting here. */
    pageIndexOffset: Int = 0,
    /** Identifies this viewport in [RenderScheduler.request] when several share the scheduler. */
    requestSource: Any = Unit,
    /**
     * A one-finger horizontal drag that the page can't absorb (fit width, or already at its edge)
     * is left unconsumed, so a parent pager can take it (single-page mode).
     */
    yieldHorizontalToParent: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val gapPx = with(LocalDensity.current) { PageGap.toPx() }
    val revision by bitmaps.revision.collectAsStateWithLifecycle()
    val layout = state.layout
    val planner = remember(layout, budget) { layout?.let { RenderPlanner(it, budget.maxPageBitmapBytes, pageIndexOffset = pageIndexOffset) } }
    // Tile levels drawn: the current one and the previous one, which stays visible (scaled)
    // until the new tiles arrive, so a zoom never flashes back to the blurry page bitmap.
    var tileLevel by remember(planner) { mutableIntStateOf(NO_LEVEL) }
    var previousTileLevel by remember(planner) { mutableIntStateOf(NO_LEVEL) }

    DisposableEffect(bitmaps, requestSource) { onDispose { bitmaps.release(requestSource) } }

    LaunchedEffect(planner) {
        val planner = planner ?: return@LaunchedEffect
        snapshotFlow { Triple(state.viewport, state.viewportSize, state.isInteracting) }
            .collectLatest { (viewport, size, interacting) ->
                // Page bitmaps right away (they are also what gets scaled while zooming)...
                bitmaps.request(planner.plan(viewport, size, tileLevel = null), requestSource)
                if (interacting) return@collectLatest
                // ...tiles only once everything has stood still for a moment.
                delay(SETTLE_DELAY_MS)
                val level = planner.tileLevelFor(viewport.zoom)
                if (level != tileLevel) {
                    previousTileLevel = tileLevel
                    tileLevel = level
                }
                bitmaps.request(planner.plan(viewport, size, tileLevel = level), requestSource)
            }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { state.setContent(pageSizes, it.toSize(), gapPx) }
            .pointerInput(state) {
                detectTapGestures(onDoubleTap = { tap -> state.launchAnimation(scope) { state.animateDoubleTap(tap) } })
            }
            .pointerInput(state, yieldHorizontalToParent) { detectZoomPanFling(state, scope, decay, yieldHorizontalToParent) },
    ) {
        // Read so that a new bitmap triggers a redraw.
        @Suppress("UNUSED_EXPRESSION")
        revision
        drawRect(backgroundColor)
        val mapper = state.mapper ?: return@Canvas
        val planner = planner ?: return@Canvas
        if (planner.layout !== mapper.layout) return@Canvas
        for (index in planner.visiblePages(mapper.viewport, size)) {
            val pageRect = mapper.pageBoundsOnScreen(index)
            drawRect(Color.White, pageRect.topLeft, pageRect.size)
            bitmaps[planner.pageKey(index)]?.let { drawBitmap(it, pageRect) }
            for (level in intArrayOf(previousTileLevel, tileLevel)) {
                if (level == NO_LEVEL) continue
                drawTiles(index, level, planner, mapper, bitmaps)
            }
        }
    }
}

private const val NO_LEVEL = Int.MIN_VALUE

private fun DrawScope.drawTiles(
    pageIndex: Int,
    level: Int,
    planner: RenderPlanner,
    mapper: PageCoordinateMapper,
    bitmaps: RenderScheduler<ImageBitmap>,
) {
    for (tile in planner.visibleTiles(pageIndex, level, mapper.viewport, size)) {
        val bitmap = bitmaps[tile] ?: continue
        drawBitmap(bitmap, mapper.pageRectToScreen(pageIndex, planner.tileRectInPoints(tile)))
    }
}

/** Edges rounded independently, so adjacent tiles share them exactly and no seam shows. */
private fun DrawScope.drawBitmap(bitmap: ImageBitmap, dst: Rect) {
    val left = dst.left.roundToInt()
    val top = dst.top.roundToInt()
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(dst.right.roundToInt() - left, dst.bottom.roundToInt() - top),
    )
}

/**
 * Pinch and pan with any number of fingers, fling when the last finger lifts after a one-finger
 * pan. A touch stops the running animation. Changes are consumed only past the touch slop, so
 * the tap detector still sees taps and double taps.
 */
private suspend fun PointerInputScope.detectZoomPanFling(
    state: PdfViewportState,
    scope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    yieldHorizontalToParent: Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.stopAnimation()
        state.isInteracting = true
        val tracker = VelocityTracker()
        var trackedId = down.id
        var pointerCount = 1
        tracker.addPosition(down.uptimeMillis, down.position)
        var pastSlop = false
        var totalZoom = 1f
        var totalPan = Offset.Zero

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                totalZoom *= zoomChange
                totalPan += panChange
                val zoomMotion = abs(1 - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                pastSlop = zoomMotion > viewConfiguration.touchSlop ||
                    totalPan.getDistance() > viewConfiguration.touchSlop
            }
            if (pastSlop) {
                if (zoomChange != 1f) state.zoomBy(zoomChange, event.calculateCentroid(useCurrent = false))
                val offsetBefore = state.viewport.offset
                if (panChange != Offset.Zero) state.panBy(panChange)
                val absorbedX = state.viewport.offset.x != offsetBefore.x
                val leaveToParent = yieldHorizontalToParent && pressed.size == 1 && zoomChange == 1f &&
                    !absorbedX && abs(panChange.x) > abs(panChange.y)
                if (!leaveToParent) event.changes.forEach { if (it.positionChanged()) it.consume() }
            }

            // Velocity of one finger; restarted when fingers are added or lifted, so a pinch
            // doesn't turn into a jump.
            if (pressed.size != pointerCount || pressed.none { it.id == trackedId }) {
                tracker.resetTracking()
                trackedId = pressed.first().id
                pointerCount = pressed.size
            }
            pressed.first { it.id == trackedId }.let { tracker.addPosition(it.uptimeMillis, it.position) }
        }

        if (pastSlop && pointerCount == 1) {
            val velocity = tracker.calculateVelocity()
            state.launchAnimation(scope) { state.fling(velocity, decay) }
        } else {
            state.isInteracting = false
        }
    }
}
