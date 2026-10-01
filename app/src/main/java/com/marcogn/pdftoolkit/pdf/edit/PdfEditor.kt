package com.marcogn.pdftoolkit.pdf.edit

import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import java.io.File

/**
 * Every write to a PDF goes through here (ADR 0002): the UI never touches the PDF library, which
 * can therefore be replaced. Overlays (fill and sign, phase 4) join [applySession] later.
 */
interface PdfEditor {

    /**
     * Writes the document described by [session] to [output]: its pages in its order, with the
     * rotations it holds. [sources] maps each [DocRef] used by the session to a local file.
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
}
