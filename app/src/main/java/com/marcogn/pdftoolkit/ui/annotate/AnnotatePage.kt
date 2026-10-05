package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.annotate.AnnotationEdits
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.ExistingAnnotation
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationEraser
import com.marcogn.pdftoolkit.pdf.annotations.ErasePick
import com.marcogn.pdftoolkit.pdf.annotations.FreehandGeometry
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.ui.fill.rememberPageBackdrop
import com.marcogn.pdftoolkit.ui.viewer.PageGap
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState
import com.marcogn.pdftoolkit.ui.viewer.detectZoomPanFling
import kotlinx.coroutines.launch

/** How far from an annotation the eraser still takes it, so a thin underline isn't a precision job. */
private val ERASE_TOLERANCE = 14.dp
private val HANDLE_RADIUS = 9.dp
private val HANDLE_SLOP = 14.dp
private const val SELECTION_ALPHA = 0.3f

/**
 * One page of the session for "Annotate" (spec §7.4): zoom and pan like the viewer, the page drawn
 * in the rotation the session gives it, its annotations on top (the ones in the file that haven't
 * been erased, then the new ones) and the text selection. With a freehand tool, the ink layer on
 * top draws ([isCurrent] page only) and each finished stroke becomes an Ink annotation.
 *
 * Text is selected in the page *as its source shows it* ([sourceSpace]); the page on screen may be
 * turned further by the user ([space]), and the two are related through user space.
 */
@Composable
internal fun AnnotatePage(
    item: PageItem,
    space: PdfPageSpace,
    sourceSpace: PdfPageSpace,
    existing: List<ExistingAnnotation>,
    edits: AnnotationEdits,
    tool: AnnotateTool,
    isCurrent: Boolean,
    selection: TextSelectionState,
    actions: AnnotateActions,
    pageColor: Color,
    backgroundColor: Color,
    selectionColor: Color,
    modifier: Modifier = Modifier,
) {
    val viewport = remember(item.id, space) { PdfViewportState() }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val density = LocalDensity.current
    val gapPx = with(density) { PageGap.toPx() }
    val handleMetrics = remember(density) { HandleMetrics(radius = with(density) { HANDLE_RADIUS.toPx() }, slop = with(density) { HANDLE_SLOP.toPx() }) }
    val eraseTolerancePx = with(density) { ERASE_TOLERANCE.toPx() }
    val haptic = LocalHapticFeedback.current
    val backdrop = rememberPageBackdrop(item, sourceSpace, viewport, actions)
    val noText = stringResource(R.string.annotate_page_no_text)

    // Strokes just finished, drawn here until the session (a state flow, a frame later) has them:
    // the ink layer stops drawing a stroke as soon as it hands it over.
    val pending = remember(item.id) { mutableStateListOf<NewAnnotation>() }
    LaunchedEffect(edits, item.id) { pending.removeAll { p -> edits.added.any { it.id == p.id } } }
    val drawn = remember(existing, edits, item.id, pending.toList()) {
        val kept = existing.filter { it.ref !in edits.removed }.mapNotNull { a -> a.shape?.let { DrawnAnnotation(it, a.style) } }
        val added = edits.addedOn(item.id)
        kept + (added + pending.filter { p -> added.none { it.id == p.id } }).map { DrawnAnnotation(it.shape, it.style) }
    }
    // The detectors outlive recompositions: they must see the latest tool, edits and selection.
    val currentTool by rememberUpdatedState(tool)
    val currentEdits by rememberUpdatedState(edits)
    val currentExisting by rememberUpdatedState(existing)
    val currentActions by rememberUpdatedState(actions)
    val grab = remember(item.id) { SelectionGrab() }
    val freehandGrab = remember(item.id) { FreehandGrab() }
    // Pointer → stroke space (display points of the page as shown), set as each stroke starts.
    val pointerToStroke = remember(item.id) { Matrix() }

    /** A screen point as a point of the page as its source shows it (what [TextSelection] works in). */
    fun PageCoordinateMapper.screenToSourceDisplay(screen: Offset): Offset = sourceSpace.userToDisplay.map(screenToUser(0, space, screen))

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport.setContent(listOf(space.displaySize), it.toSize(), gapPx) }
            .pointerInput(viewport, space) {
                detectTapGestures(
                    onTap = { tap ->
                        val mapper = viewport.mapper ?: return@detectTapGestures
                        if (currentTool != AnnotateTool.ERASER) {
                            selection.clear()
                            return@detectTapGestures
                        }
                        val user = mapper.screenToUser(0, space, tap)
                        val pick = AnnotationEraser.pick(
                            added = currentEdits.addedOn(item.id),
                            existing = currentExisting,
                            removed = currentEdits.removed,
                            point = UserPoint(user.x, user.y),
                            tolerance = eraseTolerancePx / mapper.screenPxPerPoint,
                        )
                        when (pick) {
                            is ErasePick.Added -> currentActions.removeAnnotation(pick.id)
                            is ErasePick.Existing -> currentActions.removeExistingAnnotation(pick.ref)
                            null -> Unit
                        }
                    },
                    onDoubleTap = { tap -> viewport.launchAnimation(scope) { viewport.animateDoubleTap(tap) } },
                )
            }
            .pointerInput(viewport, tool.freehand != null) {
                if (tool.freehand == null) return@pointerInput
                detectFreehandGestures(viewport, freehandGrab) {
                    viewport.mapper?.let { pointerToStroke.setAffine(it.pageToScreenTransform(0).inverse()) }
                }
            }
            .pointerInput(viewport) {
                detectZoomPanFling(viewport, scope, decay, yieldHorizontalToParent = true, suppressed = { grab.active || freehandGrab.active })
            }
            .pointerInput(viewport, space, item.id, tool.kind != null) {
                // Selecting is for the markup tools only.
                if (item !is PageItem.FromPdf || tool.kind == null) return@pointerInput
                detectSelectionGestures(
                    grab = grab,
                    handleAt = { screen ->
                        val mapper = viewport.mapper
                        if (mapper == null || selection.key != item.id || currentTool.kind == null) {
                            null
                        } else {
                            SelectionHandles.grabAt(selection.runs, mapper.userToScreen(0, space) * sourceSpace.displayToUser, screen, handleMetrics)
                        }
                    },
                    onLongPress = { screen ->
                        val mapper = viewport.mapper
                        if (mapper != null && currentTool.kind != null) {
                            val point = mapper.screenToSourceDisplay(screen)
                            scope.launch {
                                val model = currentActions.pageText(item) ?: return@launch
                                val word = model.wordAt(point)
                                if (word == null) {
                                    if (model.isEmpty) currentActions.message(noText)
                                } else {
                                    selection.select(item.id, model, word)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    },
                    onDrag = { handle, screen ->
                        val mapper = viewport.mapper
                        if (mapper != null && selection.key == item.id) selection.drag(handle, mapper.screenToSourceDisplay(screen))
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(backgroundColor)
            val mapper = viewport.mapper ?: return@Canvas
            val bounds = mapper.pageBoundsOnScreen(0)
            drawRect(pageColor, bounds.topLeft, bounds.size)
            val userToScreen = mapper.userToScreen(0, space)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                backdrop.draw(native, item, sourceSpace, userToScreen)
                native.restore()
            }
            // Clipped to the page, as a reader clips what an annotation draws outside it.
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                drawAnnotations(drawn, userToScreen, mapper.screenPxPerPoint)
            }
            if (selection.key == item.id) {
                drawTextSelection(
                    selection.runs,
                    userToScreen * sourceSpace.displayToUser,
                    selectionColor.copy(alpha = SELECTION_ALPHA),
                    selectionColor,
                    handleMetrics,
                )
            }
        }
        val freehand = tool.freehand
        if (freehand != null && isCurrent) {
            FreehandLayer(
                viewport = viewport,
                kind = freehand,
                pointerToStroke = pointerToStroke,
                onStroke = { stroke ->
                    val ink = FreehandGeometry.toInk(stroke, space.displayToUser, highlighter = freehand == FreehandKind.HIGHLIGHTER)
                    if (ink != null && FreehandGeometry.touchesPage(ink, space)) {
                        val annotation = NewAnnotation(currentActions.newAnnotationId(), item.id, ink, AnnotationStyle(freehand.color))
                        pending += annotation
                        currentActions.addAnnotation(annotation)
                    }
                },
            )
        }
    }
}
