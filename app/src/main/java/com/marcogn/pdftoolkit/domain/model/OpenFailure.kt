package com.marcogn.pdftoolkit.domain.model

/** Why a PDF could not be opened (spec §5, §12: typed errors in the domain, localised in the UI). */
enum class OpenFailure {
    /** The file is gone or the permission to read it was lost. */
    NOT_FOUND,

    /** The file needs a password (handled for real in phase 1b) or uses an unsupported security scheme. */
    PASSWORD_PROTECTED,

    /** Damaged file, not a PDF, or a PDF the system renderer can't read. */
    UNREADABLE,
}

class PdfOpenException(val failure: OpenFailure, cause: Throwable? = null) : Exception(failure.name, cause)
