package com.marcogn.pdftoolkit.data.save

import kotlinx.serialization.Serializable

/**
 * What a background save needs, written as JSON in `cacheDir/work/` because the pages of a long
 * document don't fit WorkManager's 10 KB input limit. [pages] is `EditSession.encode()`.
 */
@Serializable
data class SaveRequest(
    val sourceUri: String,
    val destinationUri: String,
    val sourcePageCount: Int,
    val pages: String,
    val extraSources: List<ExtraSource> = emptyList(),
)

/** A PDF added to the session (document [docId] of its pages), read at save time. */
@Serializable
data class ExtraSource(val docId: Int, val uri: String, val pageCount: Int)
