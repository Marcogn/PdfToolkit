package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import java.io.File
import java.io.InputStream

/**
 * Every write to a PDF goes through here (ADR 0002): the UI never touches the PDF library, which
 * can therefore be replaced. Overlays (fill and sign, phase 4) join [applySession] later.
 */
interface PdfEditor {

    /**
     * Writes the document described by [session] to [output]: its pages in its order, with the
     * rotations it holds: pages of the main PDF and of other PDFs, blank pages and images.
     * [sources] maps each [DocRef] used by the session to a local file.
     * [onProgress] receives 0..1.
     *
     * @throws com.marcogn.pdftoolkit.domain.edit.SaveException with the reason; [output] may be
     * left partially written, the caller discards it.
     */
    suspend fun applySession(
        session: EditSession,
        sources: Map<DocRef, File>,
        output: File,
        onProgress: (Float) -> Unit = {},
    )

    /** Whether the PDF read from [open] has AcroForm fields (merge warns about them, spec §6.6); false if it can't be read. */
    suspend fun hasFormFields(open: () -> InputStream?): Boolean
}
