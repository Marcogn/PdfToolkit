package com.marcogn.pdftoolkit.domain.signature

/** A rectangle of pixels, [right] and [bottom] exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Turns a photographed or scanned signature into one with a transparent background (spec §6.5:
 * "soglia sul bianco → trasparente"). Works on ARGB ints, so it is plain JVM code.
 */
object BackgroundRemoval {
    const val DEFAULT_THRESHOLD = 0.75f

    /** Width of the ramp below the threshold where pixels go from opaque to transparent, so edges stay smooth. */
    const val SOFTNESS = 0.12f

    /** Alpha above which a pixel counts as part of the signature when trimming. */
    private const val CONTENT_ALPHA = 16

    /** Brightness 0..1 of an ARGB pixel (Rec. 601 luma). */
    fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    /**
     * Pixels at least as bright as [threshold] become fully transparent, pixels darker by
     * [SOFTNESS] or more keep their alpha, in between it ramps. Colour is kept as it is; a pixel
     * already transparent stays so.
     */
    fun removeBackground(pixels: IntArray, threshold: Float): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val keep = ((threshold - luminance(pixel)) / SOFTNESS).coerceIn(0f, 1f)
            val alpha = ((pixel ushr 24) * keep + 0.5f).toInt()
            out[i] = (alpha shl 24) or (pixel and 0x00FFFFFF)
        }
        return out
    }

    /** The smallest rectangle holding every visible pixel, grown by [margin]; null if nothing is visible. */
    fun contentBounds(pixels: IntArray, width: Int, height: Int, margin: Int = 0): PixelRect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((pixels[y * width + x] ushr 24) >= CONTENT_ALPHA) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        return PixelRect(
            (left - margin).coerceAtLeast(0),
            (top - margin).coerceAtLeast(0),
            (right + 1 + margin).coerceAtMost(width),
            (bottom + 1 + margin).coerceAtMost(height),
        )
    }
}
