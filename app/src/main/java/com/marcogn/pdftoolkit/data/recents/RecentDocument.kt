package com.marcogn.pdftoolkit.data.recents

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A document opened before (spec §8). [uri] is the `content://` or `file://` URI as a string. */
@Entity(tableName = "recent_documents")
data class RecentDocument(
    @PrimaryKey val uri: String,
    val displayName: String,
    /** Null when the provider doesn't say. */
    val sizeBytes: Long?,
    val pageCount: Int,
    /** Zero-based page the reader was on. */
    val lastPage: Int,
    val lastOpenedAt: Long,
    /** Absolute path of the first-page thumbnail under `cacheDir/thumbnails/`; regenerable, may be gone. */
    val thumbnailPath: String?,
)
