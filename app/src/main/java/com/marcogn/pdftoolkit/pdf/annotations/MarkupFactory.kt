package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.text.LineRun

/** Turns a text selection into the annotation to write (spec §7.4). */
object MarkupFactory {

    /**
     * A [kind] annotation over [runs] (what [com.marcogn.pdftoolkit.pdf.text.TextSelection.runs]
     * gives: one per line, in the page points of the page *as its source shows it*), bound to the
     * session page [pageId]. [sourceSpace] is that source page's space, so the quads end up in the
     * user space the writer needs, whatever the page's own `/Rotate`. Null when there are no runs.
     */
    fun build(id: String, pageId: String, kind: MarkupKind, color: AnnotationColor, runs: List<LineRun>, sourceSpace: PdfPageSpace): NewAnnotation? {
        if (runs.isEmpty()) return null
        return NewAnnotation(
            id = id,
            pageId = pageId,
            shape = AnnotationShape.TextMarkup(kind, runs.map { it.toUser(sourceSpace) }),
            style = AnnotationStyle(color),
        )
    }
}
