package com.marcogn.pdftoolkit.domain.annotate

import com.marcogn.pdftoolkit.domain.fill.UserRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A point in the **PDF user space** of a page (points, origin bottom-left, y up, before `/Rotate`). */
@Serializable
data class UserPoint(val x: Float, val y: Float)

/**
 * The area of one run of text on one line, in user space: four corners named after the text they
 * cover, so "upper" is towards the top of the letters and "left" where the text starts, whatever
 * the page or the text is turned by.
 *
 * Written to `/QuadPoints` in the order Acrobat uses and other readers expect (upper-left,
 * upper-right, lower-left, lower-right), which is not the order the PDF specification describes
 * (ISO 32000-1 §12.5.6.10 says counterclockwise); see [toQuadPoints].
 */
@Serializable
data class Quad(
    val upperLeft: UserPoint,
    val upperRight: UserPoint,
    val lowerLeft: UserPoint,
    val lowerRight: UserPoint,
) {
    val points: List<UserPoint> get() = listOf(upperLeft, upperRight, lowerLeft, lowerRight)

    /** The eight numbers of `/QuadPoints` for this quad. */
    fun toQuadPoints(): FloatArray = floatArrayOf(
        upperLeft.x, upperLeft.y, upperRight.x, upperRight.y,
        lowerLeft.x, lowerLeft.y, lowerRight.x, lowerRight.y,
    )

    companion object {
        /** Quads read back from `/QuadPoints` (Acrobat's order); trailing numbers that don't make a quad are dropped. */
        fun fromQuadPoints(values: FloatArray): List<Quad> = (0 until values.size / QUAD_NUMBERS).map { i ->
            val o = i * QUAD_NUMBERS
            Quad(
                UserPoint(values[o], values[o + 1]),
                UserPoint(values[o + 2], values[o + 3]),
                UserPoint(values[o + 4], values[o + 5]),
                UserPoint(values[o + 6], values[o + 7]),
            )
        }

        private const val QUAD_NUMBERS = 8
    }
}

/** RGB, each component 0..1 (PDF `/C` with three components). */
@Serializable
data class AnnotationColor(val red: Float, val green: Float, val blue: Float) {
    init {
        require(red in 0f..1f && green in 0f..1f && blue in 0f..1f) { "Colour components must be in 0..1: $this" }
    }

    companion object {
        val YELLOW = AnnotationColor(1f, 0.92f, 0.23f)
        val BLACK = AnnotationColor(0f, 0f, 0f)
    }
}

/** How an annotation is painted: colour and constant opacity (`/C`, `/CA`). */
@Serializable
data class AnnotationStyle(val color: AnnotationColor, val opacity: Float = 1f) {
    init {
        require(opacity in 0f..1f) { "Opacity must be in 0..1: $opacity" }
    }
}

/** The text markup annotations (ISO 32000-1 §12.5.6.10), named as their `/Subtype`. */
enum class MarkupKind(val subtype: String) {
    HIGHLIGHT("Highlight"),
    UNDERLINE("Underline"),
    STRIKEOUT("StrikeOut"),
    SQUIGGLY("Squiggly"),
    ;

    companion object {
        fun ofSubtype(subtype: String?): MarkupKind? = entries.firstOrNull { it.subtype == subtype }
    }
}

/** What an annotation looks like, in user space: what both the screen and the PDF writer draw. */
@Serializable
sealed interface AnnotationShape {

    /** Highlight, underline, strikeout or squiggly over [quads], one per line of text. */
    @Serializable
    @SerialName("markup")
    data class TextMarkup(val kind: MarkupKind, val quads: List<Quad>) : AnnotationShape {
        init {
            require(quads.isNotEmpty()) { "A text markup needs at least one quad" }
        }
    }

    /** Freehand strokes (`/InkList`), each a polyline of its centre, drawn [width] points wide. */
    @Serializable
    @SerialName("ink")
    data class Ink(val strokes: List<List<UserPoint>>, val width: Float) : AnnotationShape {
        init {
            require(strokes.isNotEmpty() && strokes.all { it.isNotEmpty() }) { "An ink annotation needs strokes with points" }
            require(width > 0f) { "Stroke width must be positive: $width" }
        }
    }
}

/**
 * An annotation the user added in this edit session, bound to the [pageId] of a `PageItem` (it
 * follows the page when pages move) and written as a standard annotation when saving (spec §7.4).
 */
@Serializable
data class NewAnnotation(
    val id: String,
    val pageId: String,
    val shape: AnnotationShape,
    val style: AnnotationStyle,
)

/**
 * Points at one annotation already in a source PDF: the [index] in the `/Annots` array of page
 * [pageIndex] of document [docId] (`DocRef.id`), as the file was when it was read. [fingerprint]
 * (subtype and rectangle) is checked again when saving, so that a file changed in the meantime
 * never loses an annotation the user didn't pick.
 */
@Serializable
data class AnnotationRef(val docId: Int, val pageIndex: Int, val index: Int, val fingerprint: String)

/**
 * An annotation found in a source PDF. [shape] is null for the kinds the app doesn't draw (notes,
 * stamps, shapes...): they are still listed, so the eraser can tell they exist and saving keeps
 * them. [bounds] is the annotation's `/Rect`.
 */
data class ExistingAnnotation(
    val ref: AnnotationRef,
    val subtype: String,
    val shape: AnnotationShape?,
    val style: AnnotationStyle,
    val bounds: UserRect,
)

/**
 * What the annotate tools changed in an edit session: annotations [added], and annotations of the
 * source files [removed] (made by this app or by any other).
 */
@Serializable
data class AnnotationEdits(
    val added: List<NewAnnotation> = emptyList(),
    val removed: Set<AnnotationRef> = emptySet(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    fun addedOn(pageId: String): List<NewAnnotation> = added.filter { it.pageId == pageId }
}
