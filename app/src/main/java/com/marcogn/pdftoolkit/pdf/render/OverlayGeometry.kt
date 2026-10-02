package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import com.marcogn.pdftoolkit.domain.fill.PageBox

/**
 * Geometry of fill-and-sign overlays, shared by the screen ([PageCoordinateMapper.overlayToScreen])
 * and the PDF writer, so both put an overlay in the same place.
 *
 * An overlay's **local** space: points, origin at the top-left corner of its box, x along the
 * text, y down, so it reads like the screen when the overlay is upright.
 */
object OverlayGeometry {

    /** Local space of [box] → user space of its page. Includes a flip (local y down, user y up). */
    fun localToUser(box: OverlayBox): Affine =
        Affine.translate(box.centerX, box.centerY) *
            Affine.rotate(box.angle) *
            Affine(1f, 0f, 0f, -1f, -box.width / 2f, box.height / 2f)

    /** Whether [userPoint] falls inside [box]. */
    fun contains(box: OverlayBox, userPoint: Offset): Boolean {
        val local = localToUser(box).inverse().map(userPoint)
        return local.x in 0f..box.width && local.y in 0f..box.height
    }

    /**
     * A box of [width] x [height] points centred on [displayCenter] (page points as displayed),
     * upright on the display: the way a new overlay is placed where the user taps.
     */
    fun uprightAt(space: PdfPageSpace, displayCenter: Offset, width: Float, height: Float): OverlayBox {
        val center = space.displayToUser.map(displayCenter)
        return OverlayBox(center.x, center.y, width, height, space.userAngle(0f))
    }

    /** [box] with a new size, keeping the top-left corner of its local space where it is (text grows right and down). */
    fun resizedFromTopLeft(box: OverlayBox, width: Float, height: Float): OverlayBox {
        val topLeft = localToUser(box).map(Offset.Zero)
        val moved = box.copy(width = width, height = height)
        val shift = topLeft - localToUser(moved).map(Offset.Zero)
        return moved.copy(centerX = moved.centerX + shift.x, centerY = moved.centerY + shift.y)
    }

    /** Local unit square of an image (PDF image space: 0..1, y up) → local space of [box]. */
    fun imageUnitToLocal(box: OverlayBox): Affine = Affine(box.width, 0f, 0f, -box.height, 0f, box.height)

    /**
     * Text space of a line whose baseline starts at ([x], [baseline]) in local space → user space:
     * the text matrix `Tm` (text space has y up, so the local flip is undone).
     */
    fun textMatrix(box: OverlayBox, x: Float, baseline: Float): Affine =
        localToUser(box) * Affine.translate(x, baseline) * Affine.scale(1f, -1f)
}

fun PageBox.toPageSpace(): PdfPageSpace = PdfPageSpace(left, bottom, width, height, rotation)
