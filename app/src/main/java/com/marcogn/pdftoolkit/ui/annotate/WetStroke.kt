package com.marcogn.pdftoolkit.ui.annotate

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/** One pointer sample of a stroke being drawn, already in stroke space; [timeMillis] in the uptime base. */
internal data class InkSample(
    val x: Float,
    val y: Float,
    val timeMillis: Long,
    val tool: InputToolType,
    val pressure: Float = StrokeInput.NO_PRESSURE,
)

/**
 * The stroke under the pen while it is drawn (spec §7.4), built with the same `androidx.ink` brush
 * as the finished stroke, so what is drawn while drawing is what gets saved. The app draws it
 * itself, in the page's Canvas: `androidx.ink`'s own low-latency layer (a front-buffered surface
 * above the window) showed nothing until the pen lifted on a Galaxy Tab S7+ (`docs/decisions.md`).
 *
 * Inputs that the library would reject (out of order, repeated, another tool) are dropped here
 * rather than thrown: real devices send them now and then.
 */
internal class WetStroke {

    private val stroke = InProgressStroke()
    private val batch = MutableStrokeInputBatch()
    private val noPrediction = MutableStrokeInputBatch()
    private var startMillis = 0L
    private var last: InkSample? = null

    /** True between [start] and [finish] or [cancel]. */
    var isActive = false
        private set

    /** Starts a stroke with [brush] at [first]. */
    fun start(brush: Brush, first: InkSample) {
        stroke.start(brush)
        startMillis = first.timeMillis
        last = null
        isActive = true
        add(listOf(first))
    }

    /** Adds [samples] (oldest first); false if none could be used. */
    fun add(samples: List<InkSample>): Boolean {
        if (!isActive) return false
        val usable = usable(last, samples)
        if (usable.isEmpty()) return false
        batch.clear()
        for (sample in usable) {
            batch.add(sample.tool, sample.x, sample.y, sample.timeMillis - startMillis, pressure = sample.pressure)
        }
        try {
            stroke.enqueueInputs(batch, noPrediction)
        } catch (e: IllegalArgumentException) {
            return false
        }
        last = usable.last()
        stroke.updateShape()
        return true
    }

    /** The finished stroke, or null if it never got an input. */
    fun finish(): Stroke? {
        if (!isActive) return null
        isActive = false
        if (last == null) return null
        stroke.finishInput()
        stroke.updateShape()
        return stroke.toImmutable()
    }

    /** Drops the stroke (it stops being drawn; the next [start] reuses the native stroke). */
    fun cancel() {
        isActive = false
        last = null
    }

    /** The stroke as it is now, for drawing; null when there is nothing to draw. */
    val current: InProgressStroke? get() = stroke.takeIf { isActive && last != null }

    companion object {
        /**
         * The [samples] that may follow [last] in one stroke, as `InProgressStroke.enqueueInputs`
         * requires: the same tool as the stroke, time never going back, no repeat of the previous input.
         */
        fun usable(last: InkSample?, samples: List<InkSample>): List<InkSample> {
            var previous = last
            val tool = last?.tool ?: samples.firstOrNull()?.tool
            return samples.filter { sample ->
                val ok = sample.tool == tool &&
                    (previous == null || (sample.timeMillis >= previous.timeMillis && !sample.repeats(previous)))
                if (ok) previous = sample
                ok
            }
        }

        private fun InkSample.repeats(other: InkSample) = x == other.x && y == other.y && timeMillis == other.timeMillis
    }
}
