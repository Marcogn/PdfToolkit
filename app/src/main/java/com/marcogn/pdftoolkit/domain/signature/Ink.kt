package com.marcogn.pdftoolkit.domain.signature

import kotlin.math.hypot

/** The two ink colours of the drawing canvas (spec §6.5: black and blue). */
enum class InkColor(val argb: Int) {
    BLACK(0xFF000000.toInt()),
    BLUE(0xFF1B3FA0.toInt()),
}

/** A touch sample: position in canvas pixels and the time it was reported. */
data class InkPoint(val x: Float, val y: Float, val timeMs: Long)

/** One stroke: from a finger going down to it going up. */
data class InkStroke(val color: InkColor, val points: List<InkPoint>)

/**
 * Line width along a stroke (spec §6.5: "spessore che varia leggermente con la velocità"): the
 * faster the finger, the thinner the line, within [MIN_FACTOR]..[MAX_FACTOR] of the base width, and
 * smoothed so the width never jumps between two samples.
 */
object InkWidth {
    const val MIN_FACTOR = 0.55f
    const val MAX_FACTOR = 1.25f

    /** Speed in pixels per millisecond at which the line is as thin as it gets. */
    const val FAST_SPEED = 3f

    /** How much of the new width a sample takes, the rest comes from the previous one. */
    private const val SMOOTHING = 0.25f

    /** The width at [speed] (px/ms) for a [base] width, before smoothing. */
    fun target(speed: Float, base: Float): Float {
        val t = (speed / FAST_SPEED).coerceIn(0f, 1f)
        return base * (MAX_FACTOR + (MIN_FACTOR - MAX_FACTOR) * t)
    }

    /** One width per point of [points]. A single point is a dot of the base width. */
    fun widths(points: List<InkPoint>, base: Float): FloatArray {
        val out = FloatArray(points.size)
        if (points.isEmpty()) return out
        out[0] = base
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = (b.timeMs - a.timeMs).coerceAtLeast(1L)
            val speed = hypot(b.x - a.x, b.y - a.y) / dt
            out[i] = out[i - 1] + (target(speed, base) - out[i - 1]) * SMOOTHING
        }
        return out
    }
}
