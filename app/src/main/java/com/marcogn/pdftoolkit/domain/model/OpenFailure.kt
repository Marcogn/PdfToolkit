package com.marcogn.pdftoolkit.domain.model

/** Why a PDF could not be opened (spec §5, §12: typed errors in the domain, localised in the UI). */
enum class OpenFailure {
    /** The file is gone or the permission to read it was lost. */
    NOT_FOUND,

    /** The file needs a password (none given or a wrong one), asked for in the viewer from Android 15. */
    PASSWORD_PROTECTED,

    /** The file needs a password but `PdfRenderer` can't take one before Android 15. */
    PASSWORD_UNSUPPORTED,

    /** Damaged file, not a PDF, or a PDF the system renderer can't read. */
    UNREADABLE,
}

class PdfOpenException(val failure: OpenFailure, cause: Throwable? = null) : Exception(failure.name, cause)
