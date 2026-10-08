package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
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

    /** Whether [userPoint] is within [margin] points of [box], so a small overlay can still be grabbed with a finger. */
    fun contains(box: OverlayBox, userPoint: Offset, margin: Float): Boolean {
        val local = localToUser(box).inverse().map(userPoint)
        return local.x in -margin..(box.width + margin) && local.y in -margin..(box.height + margin)
    }

    /**
     * [box] after the plane is rotated by [UserTransform.rotation] and scaled by [UserTransform.scale]
     * about [UserTransform.pivotFrom], then moved so that pivot lands on [UserTransform.pivotTo].
     * Proportions are kept. The scale is limited so the longer side stays within [minSide]..[maxSide].
     */
    fun transformed(box: OverlayBox, transform: UserTransform, minSide: Float, maxSide: Float): OverlayBox {
        val longer = maxOf(box.width, box.height)
        val scale = (longer * transform.scale).coerceIn(minSide, maxSide) / longer
        val radians = Math.toRadians(transform.rotation.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val rx = box.centerX - transform.pivotFrom.x
        val ry = box.centerY - transform.pivotFrom.y
        return OverlayBox(
            centerX = transform.pivotTo.x + scale * (cos * rx - sin * ry),
            centerY = transform.pivotTo.y + scale * (sin * rx + cos * ry),
            width = box.width * scale,
            height = box.height * scale,
            angle = normalizeAngle(box.angle + transform.rotation),
        )
    }

    /**
     * [overlay] after [transform]. Text scales through its font size (kept within the dialog's
     * limits; the box is linear in it, so scaling the box matches re-measuring the text), anything
     * else through its box, whose longer side stays within [MIN_SIDE]..[maxSide] points.
     */
    fun transformed(overlay: Overlay, transform: UserTransform, maxSide: Float): Overlay = when (overlay) {
        is TextOverlay -> {
            val scale = (overlay.fontSize * transform.scale).coerceIn(TextBlock.MIN_FONT_SIZE, TextBlock.MAX_FONT_SIZE) / overlay.fontSize
            overlay.copy(
                box = transformed(overlay.box, transform.copy(scale = scale), 0f, Float.MAX_VALUE),
                fontSize = overlay.fontSize * scale,
            )
        }
        else -> overlay.withBox(transformed(overlay.box, transform, MIN_SIDE, maxSide))
    }

    /** Smallest longer side an overlay can be shrunk to, in points. */
    const val MIN_SIDE = 8f

    /** [degrees] in (-180, 180]. */
    fun normalizeAngle(degrees: Float): Float {
        var angle = degrees % FULL_TURN
        if (angle > HALF_TURN) angle -= FULL_TURN
        if (angle <= -HALF_TURN) angle += FULL_TURN
        return angle
    }

    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f

    /** Local unit square of an image (PDF image space: 0..1, y up) → local space of [box]. */
    fun imageUnitToLocal(box: OverlayBox): Affine = Affine(box.width, 0f, 0f, -box.height, 0f, box.height)

    /**
     * Text space of a line whose baseline starts at ([x], [baseline]) in local space → user space:
     * the text matrix `Tm` (text space has y up, so the local flip is undone).
     */
    fun textMatrix(box: OverlayBox, x: Float, baseline: Float): Affine =
        localToUser(box) * Affine.translate(x, baseline) * Affine.scale(1f, -1f)
}

/**
 * A move, a pinch or a twist of the fingers on a page, in user space (y up, angles counterclockwise):
 * the point [pivotFrom] goes to [pivotTo] while everything turns by [rotation] degrees and grows by [scale].
 */
data class UserTransform(val scale: Float, val rotation: Float, val pivotFrom: Offset, val pivotTo: Offset) {
    companion object {
        /** One finger: the overlay follows it. */
        fun drag(from: Offset, to: Offset) = UserTransform(1f, 0f, from, to)

        /**
         * One finger on a corner handle (plan U11): the finger goes from [from] to [to] and the overlay is
         * scaled and turned about [center], which stays where it is. Scale is the ratio of the distances
         * to the centre, rotation the angle between the two directions. A finger at the centre itself
         * ([from] equal to [center]) changes nothing.
         */
        fun about(center: Offset, from: Offset, to: Offset): UserTransform {
            val before = from - center
            val after = to - center
            val beforeLength = before.getDistance()
            val afterLength = after.getDistance()
            if (beforeLength < MIN_HANDLE_RADIUS || afterLength < MIN_HANDLE_RADIUS) return UserTransform(1f, 0f, center, center)
            val turn = Math.toDegrees((kotlin.math.atan2(after.y, after.x) - kotlin.math.atan2(before.y, before.x)).toDouble()).toFloat()
            return UserTransform(afterLength / beforeLength, OverlayGeometry.normalizeAngle(turn), center, center)
        }

        /** Closer than this to the centre (points) the direction of a finger is noise. */
        private const val MIN_HANDLE_RADIUS = 1e-3f

        /** Two fingers: scale from their distance, rotation from the angle of the line between them, pivot at their midpoint. */
        fun pinch(fromA: Offset, fromB: Offset, toA: Offset, toB: Offset): UserTransform {
            val before = fromB - fromA
            val after = toB - toA
            val beforeLength = before.getDistance()
            val scale = if (beforeLength > 0f) after.getDistance() / beforeLength else 1f
            val turn = Math.toDegrees((kotlin.math.atan2(after.y, after.x) - kotlin.math.atan2(before.y, before.x)).toDouble()).toFloat()
            return UserTransform(scale, OverlayGeometry.normalizeAngle(turn), (fromA + fromB) / 2f, (toA + toB) / 2f)
        }
    }
}

fun PageBox.toPageSpace(): PdfPageSpace = PdfPageSpace(left, bottom, width, height, rotation)
