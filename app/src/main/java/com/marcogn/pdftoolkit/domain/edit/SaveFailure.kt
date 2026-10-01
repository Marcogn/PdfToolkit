package com.marcogn.pdftoolkit.domain.edit

/** Why saving failed (spec §12: typed errors in the domain, localised in the UI). */
enum class SaveFailure {
    /** The original can't be read any more (moved, permission lost). */
    SOURCE_UNREADABLE,

    /** The file is password-protected: writing protected files is out of scope (spec §14). */
    PROTECTED,

    /** The destination refused the write. The original is untouched. */
    DESTINATION_UNWRITABLE,

    /** Not enough memory for this document. */
    OUT_OF_MEMORY,

    /** Anything else, e.g. a PDF the library can't process. */
    FAILED,
}

class SaveException(val failure: SaveFailure, cause: Throwable? = null) : Exception(failure.name, cause)
