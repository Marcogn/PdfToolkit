package com.marcogn.pdftoolkit.data.ocr

import kotlinx.serialization.Serializable

/**
 * What a background text recognition needs (plan 10b), written as JSON in `cacheDir/work/` like a
 * [com.marcogn.pdftoolkit.data.save.SaveRequest]. [destinationUri] receives the searchable PDF; it is
 * the original itself for "overwrite", and is left untouched unless text was actually written.
 */
@Serializable
data class OcrRequest(val sourceUri: String, val destinationUri: String)

/** How a finished recognition ended. */
enum class OcrResult {
    /** The searchable PDF was written to the destination. */
    WRITTEN,

    /** Every page already has text: nothing was written. */
    ALREADY_TEXT,

    /** Nothing readable was found on the pages: nothing was written. */
    NO_TEXT,
}
