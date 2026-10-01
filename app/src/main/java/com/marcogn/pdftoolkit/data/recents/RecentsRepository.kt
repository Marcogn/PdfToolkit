package com.marcogn.pdftoolkit.data.recents

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recent documents (spec §4.1, §8): at most [MAX_RECENTS], most recent first, each with the page
 * the reader was on and a first-page thumbnail.
 */
@Singleton
class RecentsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: RecentDocumentDao,
    private val thumbnails: ThumbnailStore,
) {
    // Fire-and-forget writes (last page) must outlive the viewer's ViewModel.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val recents: Flow<List<RecentDocument>> = dao.observeAll()

    /**
     * Registers an opening: the document moves to the top and keeps its last page. Returns the
     * page to start from (0 for a document never opened, always inside [0, pageCount)).
     */
    suspend fun recordOpened(uri: String, displayName: String, sizeBytes: Long?, pageCount: Int, now: Long = System.currentTimeMillis()): Int {
        val previous = dao.find(uri)
        val lastPage = (previous?.lastPage ?: 0).coerceIn(0, pageCount - 1)
        dao.upsert(
            RecentDocument(
                uri = uri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                pageCount = pageCount,
                lastPage = lastPage,
                lastOpenedAt = now,
                thumbnailPath = previous?.thumbnailPath,
            ),
        )
        dao.olderThan(MAX_RECENTS).forEach { old ->
            thumbnails.delete(old.thumbnailPath)
            dao.delete(old.uri)
        }
        return lastPage
    }

    suspend fun contains(uri: String): Boolean = dao.find(uri) != null

    /** Doesn't wait for the write and survives the caller (the viewer is usually closing). */
    fun saveLastPage(uri: String, page: Int) {
        writeScope.launch { dao.updateLastPage(uri, page) }
    }

    /** Stores the first-page [bitmap] as the thumbnail of [uri] (no-op if the document left the list). */
    suspend fun saveThumbnail(uri: String, bitmap: android.graphics.Bitmap) = withContext(Dispatchers.IO) {
        if (dao.find(uri) == null) return@withContext
        dao.updateThumbnail(uri, thumbnails.save(uri, bitmap))
    }

    suspend fun remove(uri: String) {
        dao.find(uri)?.let { thumbnails.delete(it.thumbnailPath) }
        dao.delete(uri)
    }

    suspend fun clear() {
        dao.deleteAll()
        thumbnails.clear()
    }

    /** Deletes the thumbnail files; the list stays and thumbnails come back when files are reopened. */
    fun clearThumbnails() = thumbnails.clear()

    /** Whether the document can still be read: the file exists and the permission is still valid. */
    suspend fun isAccessible(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed: Uri = uri.toUri()
            context.contentResolver.openFileDescriptor(parsed, "r")?.use { true } ?: false
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    companion object {
        /** Spec §4.1. */
        const val MAX_RECENTS = 10
    }
}
