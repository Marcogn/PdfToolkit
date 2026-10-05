package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.ExistingAnnotation
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.UserPoint

/** What a tap of the eraser picked (spec §7.4). */
sealed interface ErasePick {
    /** An annotation added in this session. */
    data class Added(val id: String) : ErasePick

    /** An annotation of the source file, made by this app or any other. */
    data class Existing(val ref: AnnotationRef) : ErasePick
}

/** Which annotation the eraser takes at a point of a page, in user space. */
object AnnotationEraser {

    /**
     * The topmost annotation at [point], within [tolerance] points: annotations added in this
     * session are above the ones of the file, and later ones above earlier ones. Annotations of the
     * file that are in [removed] are gone already. One the app can't draw (a note, a stamp...) is
     * hit inside its rectangle, since there is no shape to test.
     */
    fun pick(
        added: List<NewAnnotation>,
        existing: List<ExistingAnnotation>,
        removed: Set<AnnotationRef>,
        point: UserPoint,
        tolerance: Float,
    ): ErasePick? {
        added.lastOrNull { AnnotationGeometry.hits(it.shape, point, tolerance) }?.let { return ErasePick.Added(it.id) }
        val hit = existing.lastOrNull { it.ref !in removed && it.hits(point, tolerance) } ?: return null
        return ErasePick.Existing(hit.ref)
    }

    private fun ExistingAnnotation.hits(point: UserPoint, tolerance: Float): Boolean {
        val shape = shape
        if (shape != null) return AnnotationGeometry.hits(shape, point, tolerance)
        return point.x in bounds.left - tolerance..bounds.right + tolerance &&
            point.y in bounds.bottom - tolerance..bounds.top + tolerance
    }
}
