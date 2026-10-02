package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import java.io.File
import java.io.InputStream

/** Choices made when saving that aren't part of the edit session. */
data class WriteOptions(
    /** Spec §6.5 "make final": the AcroForm becomes page content and stops being a form. */
    val flattenForm: Boolean = false,
)

/**
 * Every write to a PDF goes through here (ADR 0002): the UI never touches the PDF library, which
 * can therefore be replaced.
 */
interface PdfEditor {

    /**
     * Writes the document described by [session] to [output]: its pages in its order, with the
     * rotations it holds: pages of the main PDF and of other PDFs, blank pages and images, then
     * the session's fill content (spec §6.5): form values, the optional flattening of [options],
     * and the overlays, written into the page content.
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
        options: WriteOptions = WriteOptions(),
        onProgress: (Float) -> Unit = {},
    )

    /** Whether the PDF read from [open] has AcroForm fields (merge warns about them, spec §6.6); false if it can't be read. */
    suspend fun hasFormFields(open: () -> InputStream?): Boolean
}
