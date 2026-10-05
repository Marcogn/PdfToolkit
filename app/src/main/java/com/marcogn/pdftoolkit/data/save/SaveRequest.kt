package com.marcogn.pdftoolkit.data.save

import kotlinx.serialization.Serializable

/**
 * What a background save needs, written as JSON in `cacheDir/work/` because the pages of a long
 * document don't fit WorkManager's 10 KB input limit. [pages] is `EditSession.encode()`, [fill]
 * `EditSession.encodeFill()` (overlays and form values); [flattenForm] is the "make final" choice;
 * [annotations] is `EditSession.encodeAnnotations()`.
 */
@Serializable
data class SaveRequest(
    val sourceUri: String,
    val destinationUri: String,
    val sourcePageCount: Int,
    val pages: String,
    val extraSources: List<ExtraSource> = emptyList(),
    val fill: String = "",
    val flattenForm: Boolean = false,
    val annotations: String = "",
)

/** A PDF added to the session (document [docId] of its pages), read at save time. */
@Serializable
data class ExtraSource(val docId: Int, val uri: String, val pageCount: Int)
