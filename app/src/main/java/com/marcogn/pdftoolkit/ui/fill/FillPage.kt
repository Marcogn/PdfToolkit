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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.PageSizing
import com.marcogn.pdftoolkit.domain.edit.SizePt
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.ui.viewer.PageGap
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState
import com.marcogn.pdftoolkit.ui.viewer.detectZoomPanFling
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.sqrt

/** Spec §5: re-render once the zoom has been still for a moment. */
private const val SETTLE_MS = 150L

/** At most this many pixels per page bitmap (16 MB): past that a deep zoom shows a softer page. */
internal const val MAX_PAGE_PIXELS = 4_000_000

/** A new render is asked only when the wanted resolution differs from the current one by more than this. */
private const val RESOLUTION_SLACK = 0.15f

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
    onTap: (user: Offset, display: Offset) -> Unit,
    onFieldChange: (FormField, FieldValue, typing: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = remember(item.id, space) { PdfViewportState() }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val gapPx = with(LocalDensity.current) { PageGap.toPx() }
    var pageBitmap by remember(item) { mutableStateOf<Pair<Bitmap, Float>?>(null) }
    var pageImage by remember(item) { mutableStateOf<Bitmap?>(null) }
    val overlayImages = remember { mutableStateMapOf<String, Bitmap>() }
    // The tap detector outlives recompositions: it must call the latest callback (current overlays, tool).
    val currentOnTap by rememberUpdatedState(onTap)

    // The source page, sharp enough for the current zoom once it settles.
    if (item is PageItem.FromPdf) {
        LaunchedEffect(item, viewport) {
            snapshotFlow { Triple(viewport.viewport.zoom, viewport.layout?.pxPerPoint, viewport.isInteracting) }
                .collectLatest { (zoom, pxPerPoint, interacting) ->
                    if (pxPerPoint == null || interacting) return@collectLatest
                    if (pageBitmap != null) delay(SETTLE_MS)
                    val size = sourceSpace.displaySize
                    val wanted = minOf(pxPerPoint * zoom, sqrt(MAX_PAGE_PIXELS / (size.width * size.height)))
                    val current = pageBitmap?.second
                    if (current != null && abs(current - wanted) / wanted < RESOLUTION_SLACK) return@collectLatest
                    content.renderPage(item, wanted)?.let { bitmap -> pageBitmap = bitmap to bitmap.width / sourceSpace.displaySize.width }
                }
        }
    }
    if (item is PageItem.FromImage) {
        LaunchedEffect(item.imageUri) { pageImage = content.image(item.imageUri) }
    }
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
            .pointerInput(viewport) { detectZoomPanFling(viewport, scope, decay, yieldHorizontalToParent = true) },
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
                pageBitmap?.let { (bitmap, pxPerPoint) ->
                    // Bitmap pixels → the source's display points → user space → screen.
                    val toScreen = userToScreen * sourceSpace.displayToUser * Affine.scale(1f / pxPerPoint, 1f / pxPerPoint)
                    painter.drawBitmap(native, bitmap, toScreen)
                }
                pageImage?.let { bitmap ->
                    val page = item as PageItem.FromImage
                    val box = PageSizing.placement(ImageDimensions(bitmap.width, bitmap.height), SizePt(page.widthPt, page.heightPt))
                    // Bitmap pixels (y down) → user space (y up), as the PDF writer places the image.
                    val toUser = Affine(box.width / bitmap.width, 0f, 0f, -box.height / bitmap.height, box.x, box.y + box.height)
                    painter.drawBitmap(native, bitmap, userToScreen * toUser)
                }
                overlays.forEach { overlay ->
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
