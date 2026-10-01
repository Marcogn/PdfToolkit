package com.marcogn.pdftoolkit.data.recents

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** First-page thumbnails of recents in `cacheDir/thumbnails/` (spec §8): regenerable, so a cache. */
@Singleton
class ThumbnailStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File get() = File(context.cacheDir, DIR_NAME)

    /** Writes [bitmap] as the thumbnail of [uri] and returns its path, or null if the disk refuses. */
    fun save(uri: String, bitmap: Bitmap): String? = try {
        dir.mkdirs()
        val file = File(dir, fileName(uri))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        file.absolutePath
    } catch (e: IOException) {
        null
    }

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    internal companion object {
        const val DIR_NAME = "thumbnails"
        private const val QUALITY = 85

        /** Stable, filesystem-safe name for any URI. */
        fun fileName(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) } + ".jpg"
        }
    }
}
