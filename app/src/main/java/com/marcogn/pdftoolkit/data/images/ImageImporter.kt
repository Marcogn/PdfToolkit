package com.marcogn.pdftoolkit.data.images

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Copies the images picked for new pages into `cacheDir/images/`. The picker's grant may not last
 * until the page is written (a save resumed by the system after the app was killed), and a copy
 * the app owns can't disappear or change under the session. Kept for [STALE_MS] after the last
 * write instead of until the screen closes, because a background save may still need it.
 */
class ImageImporter @Inject constructor(@ApplicationContext private val context: Context) {

    /** The app-owned copy of [source] as a `file://` URI string; null if [source] can't be read. */
    suspend fun import(source: Uri): String? = withContext(Dispatchers.IO) {
        val target = File(imagesDir(context), UUID.randomUUID().toString())
        try {
            val input = context.contentResolver.openInputStream(source) ?: return@withContext null
            input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
            target.toUri().toString()
        } catch (e: IOException) {
            target.delete()
            null
        } catch (e: SecurityException) {
            target.delete()
            null
        }
    }

    companion object {
        private const val IMAGES_DIR = "images"
        private const val STALE_MS = 24L * 60 * 60 * 1000

        fun imagesDir(context: Context): File = File(context.cacheDir, IMAGES_DIR).apply { mkdirs() }

        /** Startup cleanup (spec §8): copies older than a day go. */
        fun cleanImagesDir(context: Context, maxAgeMs: Long = STALE_MS) {
            val limit = System.currentTimeMillis() - maxAgeMs
            imagesDir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }
    }
}
