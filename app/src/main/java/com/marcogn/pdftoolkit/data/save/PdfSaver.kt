package com.marcogn.pdftoolkit.data.save

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import com.marcogn.pdftoolkit.pdf.edit.WriteOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Saves an edit session (spec §6.7): the source is copied to `cacheDir/work/`, the new PDF is
 * written there too, and only a complete, verified file is copied over the destination. If
 * anything fails before that last copy the original is untouched. Temporary files are always
 * deleted.
 */
class PdfSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val editor: PdfEditor,
) {

    /** @throws SaveException with the reason. */
    suspend fun save(request: SaveRequest, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val counts = mapOf(DocRef.MAIN to request.sourcePageCount) + request.extraSources.associate { DocRef(it.docId) to it.pageCount }
        val session = EditSession.decode(request.pages, counts, request.fill, request.annotations) ?: throw SaveException(SaveFailure.FAILED)
        val dir = workDir(context)
        val id = UUID.randomUUID().toString()
        val sourceCopy = File(dir, "$id-source.pdf")
        val result = File(dir, "$id-result.pdf")
        // Only the added PDFs the final page list still uses are copied.
        val used = session.extraDocuments
        val extraCopies = request.extraSources.filter { DocRef(it.docId) in used }.associate { DocRef(it.docId) to (File(dir, "$id-doc${it.docId}.pdf") to it.uri) }
        try {
            copyToFile(request.sourceUri.toUri(), sourceCopy)
            extraCopies.values.forEach { (file, uri) -> copyToFile(uri.toUri(), file) }
            onProgress(COPIED_SOURCE)
            val sources = mapOf(DocRef.MAIN to sourceCopy) + extraCopies.mapValues { it.value.first }
            editor.applySession(session, sources, result, WriteOptions(request.flattenForm, request.flattenInk)) { onProgress(COPIED_SOURCE + it * (WRITTEN - COPIED_SOURCE)) }
            copyToDestination(result, request.destinationUri.toUri())
            onProgress(1f)
        } finally {
            sourceCopy.delete()
            extraCopies.values.forEach { it.first.delete() }
            result.delete()
        }
    }

    private fun copyToFile(uri: Uri, target: File) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("No stream for $uri")
            input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        } catch (e: FileNotFoundException) {
            throw SaveException(SaveFailure.SOURCE_UNREADABLE, e)
        } catch (e: SecurityException) {
            throw SaveException(SaveFailure.SOURCE_UNREADABLE, e)
        } catch (e: IOException) {
            throw SaveException(SaveFailure.SOURCE_UNREADABLE, e)
        }
    }

    /** "wt" truncates: some providers keep the old tail of the file with plain "w". */
    private fun copyToDestination(file: File, uri: Uri) {
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("No stream for $uri")
            output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
        } catch (e: FileNotFoundException) {
            throw SaveException(SaveFailure.DESTINATION_UNWRITABLE, e)
        } catch (e: SecurityException) {
            throw SaveException(SaveFailure.DESTINATION_UNWRITABLE, e)
        } catch (e: IOException) {
            throw SaveException(SaveFailure.DESTINATION_UNWRITABLE, e)
        }
    }

    companion object {
        private const val COPIED_SOURCE = 0.05f
        private const val WRITTEN = 0.9f
        private const val WORK_DIR = "work"

        /** Temporary files of saves in progress (spec §8). */
        fun workDir(context: Context): File = File(context.cacheDir, WORK_DIR).apply { mkdirs() }

        /**
         * Startup cleanup (spec §8). Only files older than [maxAgeMs] go: a save interrupted by the
         * system is resumed by WorkManager and still needs its request file.
         */
        fun cleanWorkDir(context: Context, maxAgeMs: Long = STALE_MS) {
            val limit = System.currentTimeMillis() - maxAgeMs
            workDir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }

        private const val STALE_MS = 60L * 60 * 1000
    }
}
