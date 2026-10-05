package com.marcogn.pdftoolkit.ui.fill

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.PageSizing
import com.marcogn.pdftoolkit.domain.edit.SizePt
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState
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

/**
 * What a page of the edit session shows under what the tools draw (spec §6.5, §7.4): the source
 * page rendered sharp enough for the current zoom, or the image of an image page. Shared by the
 * "Fill and sign" and "Annotate" panes.
 */
internal class PageBackdrop {
    var pageBitmap by mutableStateOf<Pair<Bitmap, Float>?>(null)
    var pageImage by mutableStateOf<Bitmap?>(null)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val values = FloatArray(MATRIX_VALUES)

    /** Draws the page on [canvas]; [userToScreen] maps the page's user space (as shown in the session) to the screen. */
    fun draw(canvas: Canvas, item: PageItem, sourceSpace: PdfPageSpace, userToScreen: Affine) {
        pageBitmap?.let { (bitmap, pxPerPoint) ->
            // Bitmap pixels → the source's display points → user space → screen.
            val toScreen = userToScreen * sourceSpace.displayToUser * Affine.scale(1f / pxPerPoint, 1f / pxPerPoint)
            canvas.drawBitmap(bitmap, toScreen.toMatrix(), paint)
        }
        pageImage?.let { bitmap ->
            val page = item as PageItem.FromImage
            val box = PageSizing.placement(ImageDimensions(bitmap.width, bitmap.height), SizePt(page.widthPt, page.heightPt))
            // Bitmap pixels (y down) → user space (y up), as the PDF writer places the image.
            val toUser = Affine(box.width / bitmap.width, 0f, 0f, -box.height / bitmap.height, box.x, box.y + box.height)
            canvas.drawBitmap(bitmap, (userToScreen * toUser).toMatrix(), paint)
        }
    }

    private fun Affine.toMatrix(): Matrix {
        values[Matrix.MSCALE_X] = a
        values[Matrix.MSKEW_X] = c
        values[Matrix.MTRANS_X] = e
        values[Matrix.MSKEW_Y] = b
        values[Matrix.MSCALE_Y] = d
        values[Matrix.MTRANS_Y] = f
        values[Matrix.MPERSP_0] = 0f
        values[Matrix.MPERSP_1] = 0f
        values[Matrix.MPERSP_2] = 1f
        matrix.setValues(values)
        return matrix
    }

    private companion object {
        const val MATRIX_VALUES = 9
    }
}

/** A [PageBackdrop] for [item], kept sharp for [viewport]'s zoom; [sourceSpace] is the page as its source shows it. */
@Composable
internal fun rememberPageBackdrop(item: PageItem, sourceSpace: PdfPageSpace, viewport: PdfViewportState, content: FillPageContent): PageBackdrop {
    val backdrop = remember(item) { PageBackdrop() }
    // The source page, sharp enough for the current zoom once it settles.
    if (item is PageItem.FromPdf) {
        LaunchedEffect(item, viewport) {
            snapshotFlow { Triple(viewport.viewport.zoom, viewport.layout?.pxPerPoint, viewport.isInteracting) }
                .collectLatest { (zoom, pxPerPoint, interacting) ->
                    if (pxPerPoint == null || interacting) return@collectLatest
                    if (backdrop.pageBitmap != null) delay(SETTLE_MS)
                    val size = sourceSpace.displaySize
                    val wanted = minOf(pxPerPoint * zoom, sqrt(MAX_PAGE_PIXELS / (size.width * size.height)))
                    val current = backdrop.pageBitmap?.second
                    if (current != null && abs(current - wanted) / wanted < RESOLUTION_SLACK) return@collectLatest
                    content.renderPage(item, wanted)?.let { bitmap ->
                        backdrop.pageBitmap = bitmap to bitmap.width / sourceSpace.displaySize.width
                    }
                }
        }
    }
    if (item is PageItem.FromImage) {
        LaunchedEffect(item.imageUri) { backdrop.pageImage = content.image(item.imageUri) }
    }
    return backdrop
}
