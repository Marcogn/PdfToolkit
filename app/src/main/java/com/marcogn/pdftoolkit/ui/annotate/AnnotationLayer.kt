package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.runtime.Immutable
import com.marcogn.pdftoolkit.domain.annotate.AnnotationEdits
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

        /**
         * The annotations of [document] that have a shape to draw (the others are listed but not
         * drawn), as [edits] leave them: the ones [AnnotationEdits.removed] go, the ones added on a
         * page (session page id [pageId] of the page index) are drawn on top, in order.
         */
        fun of(
            document: DocumentAnnotations?,
            edits: AnnotationEdits = AnnotationEdits(),
            pageId: (Int) -> String = { "" },
        ): AnnotationLayer {
            if (document == null) return None
            val byPage = HashMap<Int, PageAnnotations>()
            val addedByPage = edits.added.groupBy { it.pageId }
            document.pageBoxes.forEachIndexed { index, box ->
                val kept = document.on(index)
                    .filter { it.ref !in edits.removed }
                    .mapNotNull { a -> a.shape?.let { DrawnAnnotation(it, a.style) } }
                val added = addedByPage[pageId(index)].orEmpty().map { DrawnAnnotation(it.shape, it.style) }
                val drawn = kept + added
                if (drawn.isNotEmpty()) byPage[index] = PageAnnotations(box.toPageSpace(), drawn)
            }
            return if (byPage.isEmpty()) None else AnnotationLayer(byPage)
        }
    }
}
