package com.marcogn.pdftoolkit.ui.fill

import android.graphics.Bitmap
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.ui.viewer.PageGap
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState
import com.marcogn.pdftoolkit.ui.viewer.detectZoomPanFling

/** How far outside an overlay a finger still grabs it, so a tick of 14 pt is not a precision job. */
private val OVERLAY_HIT_MARGIN = 20.dp

/** An overlay can grow to this many times the longer side of its page. */
private const val MAX_OVERLAY_PAGES = 1.5f

/** Longest side of image pages and image overlays as decoded for the screen. */
internal const val FILL_IMAGE_SIDE_PX = 1600

/** How [FillPage] gets what it draws; implemented by the edit screen with its view model. */
interface FillPageContent {
    /** The page of a PDF as its source shows it, at about [pxPerPoint]. */
    suspend fun renderPage(item: PageItem.FromPdf, pxPerPoint: Float): Bitmap?

    /** An image file decoded for the screen; may be cached. */
    suspend fun image(uri: String): Bitmap?
}

/**
 * One page of the session for "Fill and sign" (spec §6.5): zoom and pan like the viewer, the page
 * drawn in the rotation the session gives it, the overlays on top, and a Compose control over every
 * form widget on it. Taps go to [onTap] as a point in user space and in display page points.
 */
@Composable
internal fun FillPage(
    item: PageItem,
    space: PdfPageSpace,
    sourceSpace: PdfPageSpace,
    overlays: List<Overlay>,
    fields: List<FormField>,
    values: Map<String, FieldValue>,
    selectedOverlay: String?,
    painter: OverlayPainter,
    content: FillPageContent,
    pageColor: Color,
    backgroundColor: Color,
    selectionColor: Color,
    toolArmed: Boolean,
    onTap: (user: Offset, display: Offset) -> Unit,
    onOverlaySelect: (String) -> Unit,
    onOverlayChange: (Overlay) -> Unit,
    onFieldChange: (FormField, FieldValue, typing: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = remember(item.id, space) { PdfViewportState() }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val gapPx = with(LocalDensity.current) { PageGap.toPx() }
    val overlayImages = remember { mutableStateMapOf<String, Bitmap>() }
    // The tap detector outlives recompositions: it must call the latest callback (current overlays, tool).
    val currentOnTap by rememberUpdatedState(onTap)

    // The overlay being moved, resized or turned by the fingers: shown in place of the one in the
    // session until the fingers lift, so a gesture is one undo step.
    var live by remember(item.id) { mutableStateOf<Overlay?>(null) }
    val shownOverlays = overlays.map { if (it.id == live?.id) live ?: it else it }
    val currentOverlays by rememberUpdatedState(shownOverlays)
    val currentSelected by rememberUpdatedState(selectedOverlay)
    val currentToolArmed by rememberUpdatedState(toolArmed)
    val currentOnSelect by rememberUpdatedState(onOverlaySelect)
    val currentOnChange by rememberUpdatedState(onOverlayChange)
    val grab = remember(item.id) { OverlayGrab() }
    val haptic = LocalHapticFeedback.current
    val hitMarginPx = with(LocalDensity.current) { OVERLAY_HIT_MARGIN.toPx() }
    val maxSide = space.displaySize.let { maxOf(it.width, it.height) } * MAX_OVERLAY_PAGES

    val backdrop = rememberPageBackdrop(item, sourceSpace, viewport, content)
    val imageUris = overlays.filterIsInstance<ImageOverlay>().map { it.imageUri }.toSet()
    LaunchedEffect(imageUris) {
        imageUris.filterNot { it in overlayImages }.forEach { uri -> content.image(uri)?.let { overlayImages[uri] = it } }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport.setContent(listOf(space.displaySize), it.toSize(), gapPx) }
            .pointerInput(viewport, space) {
                detectTapGestures(
                    onTap = { tap ->
                        val hit = viewport.mapper?.hitTest(tap) ?: return@detectTapGestures
                        currentOnTap(space.displayToUser.map(hit.point), hit.point)
                    },
                    onDoubleTap = { tap -> viewport.launchAnimation(scope) { viewport.animateDoubleTap(tap) } },
                )
            }
            .pointerInput(viewport) { detectZoomPanFling(viewport, scope, decay, yieldHorizontalToParent = true, suppressed = { grab.active }) }
            .pointerInput(viewport, space) {
                detectOverlayGestures(
                    grab = grab,
                    hit = { screen, anyOverlay ->
                        val mapper = viewport.mapper
                        if (mapper == null || currentToolArmed) {
                            null
                        } else {
                            val user = mapper.screenToUser(0, space, screen)
                            val margin = hitMarginPx / mapper.screenPxPerPoint
                            if (anyOverlay) {
                                currentOverlays.lastOrNull { OverlayGeometry.contains(it.box, user) }
                            } else {
                                currentOverlays.firstOrNull { it.id == currentSelected }?.takeIf { OverlayGeometry.contains(it.box, user, margin) }
                            }
                        }
                    },
                    toUser = { screen -> viewport.mapper?.screenToUser(0, space, screen) },
                    onGrabbed = { overlay ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnSelect(overlay.id)
                    },
                    apply = { overlay, transform -> OverlayGeometry.transformed(overlay, transform, maxSide) },
                    onLive = { live = it },
                    onCommit = {
                        currentOnChange(it)
                        live = null
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
                shownOverlays.forEach { overlay ->
                    val image = (overlay as? ImageOverlay)?.let { overlayImages[it.imageUri] }
                    val selection = if (overlay.id == selectedOverlay) selectionColor.toArgb() else null
                    painter.draw(native, overlay, mapper.overlayToScreen(0, space, overlay.box), mapper.screenPxPerPoint, image, selection)
                }
                native.restore()
            }
        }
        val mapper = viewport.mapper
        if (mapper != null && item is PageItem.FromPdf) {
            fields.forEach { field ->
                field.widgets.forEachIndexed { index, widget ->
                    if (widget.pageIndex != item.pageIndex) return@forEachIndexed
                    FieldControl(
                        field = field,
                        widgetIndex = index,
                        rect = mapper.userRectToScreen(0, space, widget.rect),
                        pxPerPoint = mapper.screenPxPerPoint,
                        value = values[field.name] ?: field.value,
                        onChange = { value, typing -> onFieldChange(field, value, typing) },
                        // Field text is upright in user space: on screen it runs at the page's rotation.
                        rotation = space.displayAngle(0f).toInt(),
                    )
                }
            }
        }
    }
}
