package com.marcogn.pdftoolkit.ui.viewer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Where one item of the fan goes, in dp from the fan's origin (x right, y down). */
data class FanPosition(val x: Float, val y: Float, val ring: Int)

/**
 * The fan ("raggiera") of the tool drawer (plan T-b): items on arcs around the origin, inner arc first. Pure
 * geometry. One arc holds as many items as keep [itemSpacingDp] between neighbours along it, so any number of
 * items fits by adding rings; a ring that is not full is centred in the arc rather than pushed to its ends.
 *
 * @param startDeg direction of the first item and [endDeg] of the last, counter-clockwise from the right as on a
 * page with y up: 90 is straight up (portrait bar), 180 straight left. The rail (landscape) unfolds from 180
 * to 270 (left, then down).
 */
object FanLayout {
    const val FIRST_RADIUS_DP = 96f
    const val RING_STEP_DP = 72f

    fun positions(count: Int, startDeg: Float, endDeg: Float, itemSpacingDp: Float): List<FanPosition> {
        if (count <= 0) return emptyList()
        val result = ArrayList<FanPosition>(count)
        var ring = 0
        while (result.size < count) {
            val radius = FIRST_RADIUS_DP + ring * RING_STEP_DP
            val arcLength = radius * Math.toRadians(abs(endDeg - startDeg).toDouble()).toFloat()
            val capacity = floor(arcLength / itemSpacingDp).toInt() + 1
            val here = minOf(capacity, count - result.size)
            // A full ring runs from one end of the arc to the other; a partial one keeps the same step, centred.
            val step = if (capacity > 1) (endDeg - startDeg) / (capacity - 1) else 0f
            val first = startDeg + (endDeg - startDeg) / 2f - step * (here - 1) / 2f
            repeat(here) { i ->
                val angle = Math.toRadians((first + step * i).toDouble())
                result += FanPosition((radius * cos(angle)).toFloat(), (-radius * sin(angle)).toFloat(), ring)
            }
            ring++
        }
        return result
    }
}
