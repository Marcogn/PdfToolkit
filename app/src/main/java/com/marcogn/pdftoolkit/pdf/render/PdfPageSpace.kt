package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2D affine transform in the PDF form `[a b c d e f]`: `x' = a·x + c·y + e`, `y' = b·x + d·y + f`.
 * The same six numbers PdfBox's `Matrix(a, b, c, d, e, f)` and a content stream `cm` take.
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {

    fun map(point: Offset): Offset = Offset(a * point.x + c * point.y + e, b * point.x + d * point.y + f)

    /** Maps a direction: the linear part only, no translation. */
    fun mapVector(vector: Offset): Offset = Offset(a * vector.x + c * vector.y, b * vector.x + d * vector.y)

    /** `this` applied after [other]: `(this * other).map(p) == this.map(other.map(p))`. */
    operator fun times(other: Affine): Affine = Affine(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        e = a * other.e + c * other.f + e,
        f = b * other.e + d * other.f + f,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        require(det != 0f) { "Singular transform $this" }
        return Affine(
            a = d / det,
            b = -b / det,
            c = -c / det,
            d = a / det,
            e = (c * f - d * e) / det,
            f = (b * e - a * f) / det,
        )
    }

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(x: Float, y: Float) = Affine(1f, 0f, 0f, 1f, x, y)

        fun scale(x: Float, y: Float) = Affine(x, 0f, 0f, y, 0f, 0f)

        /**
         * Rotation by [degrees] in the mathematical sense: counterclockwise when y points up (PDF
         * user space), clockwise on a y-down screen. Quarter turns are exact, so a page turned
         * by 90° maps corners to corners without rounding noise.
         */
        fun rotate(degrees: Float): Affine {
            val (cos, sin) = if (degrees % QUARTER_TURN == 0f) {
                when (Math.floorMod(degrees.toInt(), FULL_TURN)) {
                    0 -> 1f to 0f
                    QUARTER_TURN -> 0f to 1f
                    HALF_TURN -> -1f to 0f
                    else -> 0f to -1f
                }
            } else {
                trig(degrees)
            }
            return Affine(cos, sin, -sin, cos, 0f, 0f)
        }

        private fun trig(degrees: Float): Pair<Float, Float> {
            val radians = Math.toRadians(degrees.toDouble())
            return cos(radians).toFloat() to sin(radians).toFloat()
        }
    }
}

/**
 * How the user space of one PDF page relates to the page **as displayed**: the space of
 * [PageCoordinateMapper]'s "page points" (origin top-left, y down, page rotation applied).
 *
 * - **User space**: the coordinates of the page's content stream and of its annotations and form
 *   fields: points, origin bottom-left, y up, before `/Rotate`.
 * - [left], [bottom], [width], [height]: the visible box in user space, the `/CropBox` clipped to
 *   the `/MediaBox`, which is what `PdfRenderer` (pdfium) shows. [rotation]: the page `/Rotate`,
 *   clockwise, normalised by [normalizeRotation].
 *
 * Pages that don't exist in a PDF yet (blank and image pages of an edit session) get [ofSize]:
 * the box starts at the origin and there is no rotation of their own.
 *
 * The matrices follow pdfium's `CPDF_Page::UpdateDimensions`, turned from its y-up display space
 * into our y-down one.
 */
data class PdfPageSpace(
    val left: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
    val rotation: Int = 0,
) {
    init {
        require(width > 0f && height > 0f) { "Page box must be positive: $width x $height" }
        require(rotation in ROTATIONS) { "Rotation must be 0, 90, 180 or 270: $rotation" }
    }

    private val right: Float get() = left + width
    private val top: Float get() = bottom + height

    /** Size of the page as displayed: width and height swap at 90° and 270°. */
    val displaySize: PageSize
        get() = if (rotation % HALF_TURN == 0) PageSize(width, height) else PageSize(height, width)

    /** User space → display points. */
    val userToDisplay: Affine
        get() = when (rotation) {
            0 -> Affine(1f, 0f, 0f, -1f, -left, top)
            QUARTER_TURN -> Affine(0f, 1f, 1f, 0f, -bottom, -left)
            HALF_TURN -> Affine(-1f, 0f, 0f, 1f, right, -bottom)
            else -> Affine(0f, -1f, -1f, 0f, top, right)
        }

    /** Display points → user space. */
    val displayToUser: Affine get() = userToDisplay.inverse()

    /** The same page after the user turns it by [degrees] clockwise (a multiple of 90, may be negative). */
    fun withAddedRotation(degrees: Int): PdfPageSpace = copy(rotation = Math.floorMod(rotation + degrees, FULL_TURN))

    /**
     * Clockwise angle on the display of something drawn at [userAngle] degrees counterclockwise in
     * user space. Text written upright on the display has display angle 0.
     */
    fun displayAngle(userAngle: Float): Float = normalizeAngle(rotation - userAngle)

    /** Inverse of [displayAngle]. */
    fun userAngle(displayAngle: Float): Float = normalizeAngle(rotation - displayAngle)

    companion object {
        private val ROTATIONS = setOf(0, QUARTER_TURN, HALF_TURN, THREE_QUARTERS)

        /** A page with no PDF behind it yet: user space is the page itself. */
        fun ofSize(width: Float, height: Float): PdfPageSpace = PdfPageSpace(0f, 0f, width, height, 0)

        /**
         * `/Rotate` as pdfium reads it: whole quarter turns (the value divided by 90, truncated),
         * negative values counted backwards. PdfBox returns the raw number.
         */
        fun normalizeRotation(raw: Int): Int {
            val quarters = (raw / QUARTER_TURN) % QUARTERS_PER_TURN
            return (if (quarters < 0) quarters + QUARTERS_PER_TURN else quarters) * QUARTER_TURN
        }

        /** In `[0, 360)`. */
        fun normalizeAngle(degrees: Float): Float {
            val turned = degrees % FULL_TURN
            return if (turned < 0f) turned + FULL_TURN else turned
        }
    }
}

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTERS = 270
private const val FULL_TURN = 360
private const val QUARTERS_PER_TURN = 4
