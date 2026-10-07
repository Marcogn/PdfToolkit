package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.ink.authoring.compose.InProgressStrokes
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import com.marcogn.pdftoolkit.pdf.render.Affine
import com.marcogn.pdftoolkit.ui.annotate.FreehandInk.toFreehandStroke
import com.marcogn.pdftoolkit.ui.viewer.PdfViewportState

/** Far enough out to cover the whole pane at any zoom, for the mask around the page. */
private const val FAR = 1e6f

/**
 * The ink layer of one page (spec §7.4): `androidx.ink` draws the stroke under the finger with low
 * latency, in **display points** of the page as shown ([pointerToStroke], set by
 * [detectFreehandGestures] as each stroke starts, so the zoom is the one of that moment). Each
 * finished stroke goes to [onStroke], which must draw it from then on in the same frame (the layer
 * stops drawing it).
 *
 * Nothing shows outside the page while drawing: the surroundings are masked, as the finished
 * annotation is clipped to the page.
 */
@Composable
internal fun FreehandLayer(
    viewport: PdfViewportState,
    kind: FreehandKind,
    pointerToStroke: Matrix,
    onStroke: (FreehandStroke) -> Unit,
) {
    val currentKind by rememberUpdatedState(kind)
    val currentOnStroke by rememberUpdatedState(onStroke)
    // Read in composition on purpose: the mask follows zoom and pan. Only this layer recomposes.
    val page = viewport.mapper?.pageBoundsOnScreen(0)
    val mask = remember(page) { page?.let(::outside) }
    InProgressStrokes(
        defaultBrush = null,
        nextBrush = {
            val pxPerPoint = viewport.mapper?.screenPxPerPoint ?: 1f
            FreehandInk.brush(currentKind, currentKind.color, pxPerPoint)
        },
        pointerEventToWorldTransform = pointerToStroke,
        maskPath = mask,
        onStrokesFinished = { strokes -> strokes.forEach { currentOnStroke(it.toFreehandStroke()) } },
    )
}

/** Everything but [page]. */
private fun outside(page: Rect): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addRect(Rect(-FAR, -FAR, FAR, FAR))
    addRect(page)
}

/** Sets this matrix to the 2D [affine] transform, in place (the ink layer reads it at each stroke start). */
internal fun Matrix.setAffine(affine: Affine) {
    reset()
    values[Matrix.ScaleX] = affine.a
    values[Matrix.SkewY] = affine.b
    values[Matrix.SkewX] = affine.c
    values[Matrix.ScaleY] = affine.d
    values[Matrix.TranslateX] = affine.e
    values[Matrix.TranslateY] = affine.f
}
