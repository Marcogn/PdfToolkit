package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.ui.fill.FieldControl
import com.marcogn.pdftoolkit.ui.fill.OverlayGrab
import com.marcogn.pdftoolkit.ui.fill.detectOverlayGestures
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.pdf.annotations.DocumentStrokes
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import com.marcogn.pdftoolkit.ui.annotate.FreehandGrab
import com.marcogn.pdftoolkit.ui.annotate.ViewportInkLayer
import com.marcogn.pdftoolkit.ui.annotate.detectFreehandGestures
import com.marcogn.pdftoolkit.ui.annotate.setAffine
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.pdf.render.RenderPlanner
import com.marcogn.pdftoolkit.pdf.render.RenderScheduler
import com.marcogn.pdftoolkit.ui.annotate.AnnotationLayer
import com.marcogn.pdftoolkit.ui.annotate.HandleMetrics
import com.marcogn.pdftoolkit.ui.annotate.SelectionBarPlacement
import com.marcogn.pdftoolkit.ui.annotate.SelectionGrab
import com.marcogn.pdftoolkit.ui.annotate.SelectionHandles
import com.marcogn.pdftoolkit.ui.annotate.TextSelectionState
import com.marcogn.pdftoolkit.ui.annotate.detectSelectionGestures
import com.marcogn.pdftoolkit.ui.annotate.drawAnnotations
import com.marcogn.pdftoolkit.ui.annotate.drawTextSelection
import com.marcogn.pdftoolkit.ui.search.SearchHighlights
import com.marcogn.pdftoolkit.ui.theme.SearchCurrentHighlightColor
import com.marcogn.pdftoolkit.ui.theme.SearchHighlightColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Space between pages, and above the first and below the last one. */
internal val PageGap = 8.dp

/** Spec §5: the sharp re-render starts when the zoom has been still for about 150 ms. */
private const val SETTLE_DELAY_MS = 150L

/**
 * Continuous viewer (spec §4.2, §5): pages stacked vertically, pinch zoom, double tap, pan with
 * fling. Draws on a single Canvas only the pages on screen: white placeholder with the page
 * proportions, the page bitmap scaled to the current zoom and, once the zoom settles, the sharp
 * tiles of the visible area on top; then the document's annotations, the fill overlays and the
 * search highlights. Form controls (plan V-c) are Compose children laid over their widgets.
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
    /** Search occurrences to draw over the pages (spec §5.1); keyed by document page index. */
    highlights: SearchHighlights = SearchHighlights.None,
    /** The document's annotations, which the renderer doesn't draw (spec §7.4); keyed by document page index. */
    annotations: AnnotationLayer = AnnotationLayer.None,
    /** The text selection to draw and edit (spec §7.4); null where there is none (no text to select). */
    selection: TextSelectionState? = null,
    /** A press and hold at [point] (page points) of document page [documentPage]: start a selection there. */
    onLongPress: (documentPage: Int, point: Offset) -> Unit = { _, _ -> },
    /**
     * A single tap (after the double-tap timeout, so a double tap stays "zoom"): where it fell, or null
     * off the pages. Full-screen reading (plan U20), the eraser (plan V-a).
     */
    onTap: (PageTap?) -> Unit = {},
    /** The finger that was selecting text lifted (after a long press, or after dragging a handle). */
    onSelectionReleased: () -> Unit = {},
    /** The bar that floats by the selection (Copy, Highlight; plan U21); null for none. */
    selectionBar: (@Composable () -> Unit)? = null,
    /** A freehand brush armed (plan V-a): one finger or a stylus draws, two fingers zoom and pan. Null to read. */
    drawing: ViewportDrawing? = null,
    /** Fill and sign (plan V-c): overlays, form controls and their gestures. Null for none. */
    fill: ViewportFillContent? = null,
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

    val selectionGrab = remember { SelectionGrab() }
    val density = LocalDensity.current
    val handleMetrics = remember(density) { HandleMetrics(radius = with(density) { HANDLE_RADIUS.toPx() }, slop = with(density) { HANDLE_SLOP.toPx() }) }
    val selectionFill = MaterialTheme.colorScheme.primary.copy(alpha = SELECTION_ALPHA)
    val handleColor = MaterialTheme.colorScheme.primary
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnSelectionReleased by rememberUpdatedState(onSelectionReleased)
    val currentOnTap by rememberUpdatedState(onTap)
    val freehandGrab = remember { FreehandGrab() }
    // Pointer → stroke space (document points), set as each stroke starts; the page the stroke starts on, for the mask.
    val pointerToStroke = remember { Matrix() }
    var strokePage by remember { mutableIntStateOf(0) }

    // Fill and sign (plan V-c): the overlay under the fingers is shown from here until they lift, so a
    // gesture is one undo step; the overlay detector tells zoom and pan to stand down while it holds one.
    val overlayGrab = remember { OverlayGrab() }
    val currentFill by rememberUpdatedState(fill)
    var live by remember { mutableStateOf<Overlay?>(null) }
    val shownOverlays = fill?.overlays?.map { overlay -> live?.takeIf { it.id == overlay.id } ?: overlay }.orEmpty()
    val currentOverlays by rememberUpdatedState(shownOverlays)
    val overlaysByPage = remember(shownOverlays) { shownOverlays.groupBy { it.pageId } }
    val haptic = LocalHapticFeedback.current
    val overlayMarginPx = with(density) { OVERLAY_HIT_MARGIN.toPx() }
    val overlayHandleTouchPx = with(density) { OVERLAY_HANDLE_TOUCH_RADIUS.toPx() }
    val overlayHandleRadiusPx = with(density) { OVERLAY_HANDLE_RADIUS.toPx() }
    val overlaySelectionColor = MaterialTheme.colorScheme.primary

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

    // The detectors sit on the box, so the ink layer (a child) gets the events after the freehand
    // detector has seen them in the initial pass (ADR 0004, "Freehand ink").
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.setContent(pageSizes, it.toSize(), gapPx) }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { tap ->
                        val mapper = state.mapper
                        currentOnTap(mapper?.hitTest(tap)?.let { PageTap(pageIndexOffset + it.pageIndex, it.point, mapper.screenPxPerPoint) })
                    },
                    onDoubleTap = { tap -> state.launchAnimation(scope) { state.animateDoubleTap(tap) } },
                )
            }
            .pointerInput(state, drawing != null) {
                if (drawing == null) return@pointerInput
                detectFreehandGestures(state, freehandGrab) { down ->
                    state.mapper?.let { mapper ->
                        pointerToStroke.setAffine(DocumentStrokes.documentToScreen(mapper.layout, mapper.viewport).inverse())
                        strokePage = mapper.layout.pageAt(mapper.screenToLayout(down).y)
                    }
                }
            }
            .pointerInput(state, yieldHorizontalToParent) {
                detectZoomPanFling(state, scope, decay, yieldHorizontalToParent, suppressed = { selectionGrab.active || freehandGrab.active || overlayGrab.active })
            }
            .pointerInput(state, fill != null) {
                if (fill == null) return@pointerInput
                // The geometry of the moment: zoom and pan change between events.
                fun geometry(): ViewportFill? {
                    val f = currentFill ?: return null
                    val mapper = state.mapper ?: return null
                    return ViewportFill(mapper, pageIndexOffset, f.spaceOf)
                }
                fun selected(): Overlay? = currentFill?.selected?.let { id -> currentOverlays.firstOrNull { it.id == id } }
                detectOverlayGestures(
                    grab = overlayGrab,
                    hit = { screen, anyOverlay ->
                        val geometry = geometry()
                        if (geometry == null || currentFill?.grabbing != true) {
                            null
                        } else if (anyOverlay) {
                            geometry.overlayAt(currentOverlays, screen)
                        } else {
                            selected()?.takeIf { geometry.grabs(it, screen, overlayMarginPx) }
                        }
                    },
                    toUser = { overlay, screen ->
                        ViewerEditSession.pageIndexOf(overlay.pageId)?.let { page -> geometry()?.toUser(page, screen) }
                    },
                    handleHit = { screen ->
                        val geometry = geometry()
                        if (geometry == null || currentFill?.grabbing != true) {
                            null
                        } else {
                            selected()?.takeIf { overlay -> geometry.handle(overlay)?.let { (it - screen).getDistance() <= overlayHandleTouchPx } == true }
                        }
                    },
                    onGrabbed = { overlay ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentFill?.onSelect(overlay.id)
                    },
                    apply = { overlay, transform ->
                        val space = ViewerEditSession.pageIndexOf(overlay.pageId)?.let { currentFill?.spaceOf(it) }
                        val maxSide = space?.displaySize?.let { maxOf(it.width, it.height) * MAX_OVERLAY_PAGES } ?: Float.MAX_VALUE
                        OverlayGeometry.transformed(overlay, transform, maxSide)
                    },
                    onLive = { live = it },
                    onCommit = {
                        currentFill?.onOverlayChange(it)
                        live = null
                    },
                )
            }
            .pointerInput(state, selection) {
                if (selection == null) return@pointerInput
                detectSelectionGestures(
                    grab = selectionGrab,
                    handleAt = { screen ->
                        val mapper = state.mapper
                        val local = selection.key?.toIntOrNull()?.minus(pageIndexOffset)
                        if (mapper == null || local == null || local !in mapper.layout.pageRects.indices) {
                            null
                        } else {
                            SelectionHandles.grabAt(selection.runs, mapper.pageToScreenTransform(local), screen, handleMetrics)
                        }
                    },
                    onLongPress = { screen ->
                        state.mapper?.hitTest(screen)?.let { currentOnLongPress(pageIndexOffset + it.pageIndex, it.point) }
                    },
                    onDrag = { handle, screen ->
                        val mapper = state.mapper
                        val local = selection.key?.toIntOrNull()?.minus(pageIndexOffset)
                        if (mapper != null && local != null && local in mapper.layout.pageRects.indices) {
                            selection.drag(handle, mapper.screenToPage(local, screen))
                        }
                    },
                    onRelease = { currentOnSelectionReleased() },
                )
            },
    ) {
    Canvas(
        modifier = Modifier.fillMaxSize(),
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
            annotations.on(pageIndexOffset + index)?.let { page ->
                // Clipped to the page, as a reader clips what an annotation draws outside it.
                clipRect(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom) {
                    drawAnnotations(page.annotations, mapper.userToScreen(index, page.space), mapper.screenPxPerPoint)
                }
            }
            if (fill != null) {
                val documentPage = pageIndexOffset + index
                val onPage = overlaysByPage[ViewerEditSession.pageId(documentPage)]
                val space = fill.spaceOf(documentPage)
                if (onPage != null && space != null) {
                    clipRect(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom) {
                        drawIntoCanvas { canvas ->
                            onPage.forEach { overlay ->
                                val image = (overlay as? ImageOverlay)?.let { fill.images[it.imageUri] }
                                val selected = if (fill.editing && overlay.id == fill.selected) overlaySelectionColor.toArgb() else null
                                fill.painter.draw(canvas.nativeCanvas, overlay, mapper.overlayToScreen(index, space, overlay.box), mapper.screenPxPerPoint, image, selected)
                            }
                        }
                    }
                }
            }
            if (!highlights.isEmpty) drawHighlights(index, pageIndexOffset + index, mapper, highlights)
            if (selection != null && selection.key == (pageIndexOffset + index).toString()) {
                drawTextSelection(selection.runs, mapper.pageToScreenTransform(index), selectionFill, handleColor, handleMetrics)
            }
        }
        // The handle that scales and turns the selected overlay with one finger (plan U11).
        if (fill != null && fill.grabbing) {
            shownOverlays.firstOrNull { it.id == fill.selected }?.let { selected ->
                ViewportFill(mapper, pageIndexOffset, fill.spaceOf).handle(selected)?.let { corner ->
                    drawCircle(Color.White, overlayHandleRadiusPx + OVERLAY_HANDLE_RIM_PX, corner)
                    drawCircle(overlaySelectionColor, overlayHandleRadiusPx, corner)
                }
            }
        }
    }
    if (fill != null) FormControls(fill, state, pageIndexOffset)
    if (drawing != null) {
        ViewportInkLayer(
            state = state,
            kind = drawing.kind,
            color = drawing.color,
            width = drawing.width,
            pointerToStroke = pointerToStroke,
            maskPage = strokePage,
            onStroke = { local, stroke -> drawing.onStroke(pageIndexOffset + local, stroke) },
        )
    }
    if (selection != null && selectionBar != null) {
        SelectionBarHost(selection, state, pageIndexOffset, pageSizes.size, selectionBar)
    }
    }
}

/**
 * The form controls of the pages on screen (plan V-c), over their widgets: every one while editing, else
 * only those with a pending value, as pictures. A text field that gets the focus is kept above the
 * keyboard: the viewport doesn't resize for it, so it pans the page up instead.
 */
@Composable
private fun FormControls(fill: ViewportFillContent, state: PdfViewportState, pageIndexOffset: Int) {
    val mapper = state.mapper ?: return
    val geometry = ViewportFill(mapper, pageIndexOffset, fill.spaceOf)
    val controls = geometry.controls(state.viewportSize, fill.fieldsOn) { fill.editing || it.name in fill.values }
    var focused by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val marginPx = with(density) { FOCUSED_FIELD_MARGIN.toPx() }
    LaunchedEffect(focused, imeBottom) {
        val target = focused ?: return@LaunchedEffect
        if (imeBottom == 0) return@LaunchedEffect
        val rect = controls.firstOrNull { it.field.name == target.first && it.widgetIndex == target.second }?.rect ?: return@LaunchedEffect
        val visibleBottom = state.viewportSize.height - imeBottom - marginPx
        if (rect.bottom > visibleBottom) state.panBy(Offset(0f, visibleBottom - rect.bottom))
    }
    for (control in controls) {
        key(control.field.name, control.widgetIndex) {
            FieldControl(
                field = control.field,
                widgetIndex = control.widgetIndex,
                rect = control.rect,
                pxPerPoint = mapper.screenPxPerPoint,
                value = fill.values[control.field.name] ?: control.field.value,
                onChange = { value, typing -> fill.onFieldChange(control.field, value, typing) },
                rotation = control.rotation,
                interactive = fill.editing,
                modifier = Modifier.onFocusChanged { focus ->
                    val id = control.field.name to control.widgetIndex
                    if (focus.isFocused) focused = id else if (focused == id) focused = null
                },
            )
        }
    }
}

/** How far outside an overlay a finger still grabs it, so a tick of 14 pt is not a precision job. */
private val OVERLAY_HIT_MARGIN = 20.dp

/** The overlay handle's drawn radius and the radius around it that a finger grabs (48 dp across). */
private val OVERLAY_HANDLE_RADIUS = 11.dp
private val OVERLAY_HANDLE_TOUCH_RADIUS = 24.dp
private const val OVERLAY_HANDLE_RIM_PX = 2f

/** An overlay can grow to this many times the longer side of its page. */
private const val MAX_OVERLAY_PAGES = 1.5f

/** Room kept between a focused field and the keyboard. */
private val FOCUSED_FIELD_MARGIN = 16.dp

/** A single tap on a page: document page [documentPage], [point] in its page points, at [screenPxPerPoint] (the zoom). */
data class PageTap(val documentPage: Int, val point: Offset, val screenPxPerPoint: Float)

/**
 * A freehand brush armed in the viewport (plan V-a): [kind], [color] and [width] (points of the page)
 * of the next stroke, and [onStroke], which gets each finished stroke with the document page it
 * belongs to, in that page's display points, and must draw it from then on (the ink layer stops).
 */
data class ViewportDrawing(
    val kind: FreehandKind,
    val color: AnnotationColor,
    val width: Float,
    val onStroke: (documentPage: Int, stroke: FreehandStroke) -> Unit,
)

/** Puts [content] by the selection when it is on one of this viewport's pages and on screen (plan U21). */
@Composable
private fun SelectionBarHost(
    selection: TextSelectionState,
    state: PdfViewportState,
    pageIndexOffset: Int,
    pageCount: Int,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { SELECTION_BAR_GAP.toPx() }
    val marginPx = with(density) { SELECTION_BAR_MARGIN.toPx() }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val position by remember(selection, state, pageIndexOffset, pageCount) {
        derivedStateOf {
            val local = selection.key?.toIntOrNull()?.minus(pageIndexOffset)
            val mapper = state.mapper
            if (!selection.isActive || local == null || local !in 0 until pageCount || mapper == null || local !in mapper.layout.pageRects.indices) {
                null
            } else {
                SelectionBarPlacement.screenBounds(selection.runs, mapper.pageToScreenTransform(local))?.let { bounds ->
                    SelectionBarPlacement.place(bounds, barSize.toSize(), state.viewportSize, gapPx, marginPx)
                }
            }
        }
    }
    position?.let { at ->
        Box(
            Modifier
                .onSizeChanged { barSize = it }
                .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) },
        ) { content() }
    }
}

private val SELECTION_BAR_GAP = 12.dp
private val SELECTION_BAR_MARGIN = 8.dp

private const val NO_LEVEL = Int.MIN_VALUE

private val HANDLE_RADIUS = 9.dp
private val HANDLE_SLOP = 14.dp
private const val SELECTION_ALPHA = 0.3f

/** Search occurrences of one page over its bitmap: all of them, then the current one stronger. */
private fun DrawScope.drawHighlights(localIndex: Int, documentPage: Int, mapper: PageCoordinateMapper, highlights: SearchHighlights) {
    val all = highlights.rectsOn(documentPage)
    val current = highlights.currentRectsOn(documentPage)
    for ((rects, color) in listOf(all to SearchHighlightColor, current to SearchCurrentHighlightColor)) {
        for (rect in rects) {
            val onScreen = mapper.pageRectToScreen(localIndex, rect)
            drawRect(color, onScreen.topLeft, onScreen.size)
        }
    }
}

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
 * the tap detector still sees taps and double taps. While [suppressed] is true the gesture is
 * left alone: another detector owns it.
 */
internal suspend fun PointerInputScope.detectZoomPanFling(
    state: PdfViewportState,
    scope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    yieldHorizontalToParent: Boolean,
    suppressed: () -> Boolean = { false },
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
            // Something else (an overlay being moved) has taken this gesture.
            if (suppressed()) {
                state.isInteracting = false
                return@awaitEachGesture
            }

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
