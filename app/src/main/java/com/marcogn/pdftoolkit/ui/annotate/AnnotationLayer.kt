package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.runtime.Immutable
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace

/** One annotation as drawn: its shape in user space and its style. */
data class DrawnAnnotation(val shape: AnnotationShape, val style: AnnotationStyle)

/** The annotations to draw on one page, with the [space] that places its user space on the page. */
data class PageAnnotations(val space: PdfPageSpace, val annotations: List<DrawnAnnotation>)

/**
 * The annotations the viewer draws over the pages (spec §7.4): the system renderer doesn't draw
 * them (ADR 0004), so the app does, from their geometry. Keyed by document page index.
 */
@Immutable
class AnnotationLayer private constructor(private val byPage: Map<Int, PageAnnotations>) {

    fun on(pageIndex: Int): PageAnnotations? = byPage[pageIndex]

    val isEmpty: Boolean get() = byPage.isEmpty()

    companion object {
        val None = AnnotationLayer(emptyMap())

        /** The annotations of [document] that have a shape to draw (the others are listed but not drawn). */
        fun of(document: DocumentAnnotations?): AnnotationLayer {
            if (document == null) return None
            val byPage = HashMap<Int, PageAnnotations>()
            document.pages.forEachIndexed { index, annotations ->
                val drawn = annotations.mapNotNull { a -> a.shape?.let { DrawnAnnotation(it, a.style) } }
                val box = document.pageBoxes.getOrNull(index)
                if (drawn.isNotEmpty() && box != null) byPage[index] = PageAnnotations(box.toPageSpace(), drawn)
            }
            return if (byPage.isEmpty()) None else AnnotationLayer(byPage)
        }
    }
}
