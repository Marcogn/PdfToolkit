package com.marcogn.pdftoolkit.pdf.render

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** An open document and the name to show for it. */
class OpenedPdf(val displayName: String?, val renderer: PdfDocumentRenderer)

/**
 * Opens a PDF from a `content://` (SAF) or `file://` URI.
 *
 * `PdfRenderer` needs a seekable file descriptor. Some providers (mail attachments, some cloud
 * apps) hand out a pipe instead: in that case the file is first copied to `cacheDir/open/` and the
 * copy is deleted when the document is closed.
 */
class PdfDocumentOpener @Inject constructor(@ApplicationContext private val context: Context) {

    /** @throws PdfOpenException with the reason, never the raw platform exception. */
    suspend fun open(uri: Uri): OpenedPdf = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val fd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            throw PdfOpenException(OpenFailure.NOT_FOUND, e)
        } catch (e: SecurityException) {
            throw PdfOpenException(OpenFailure.NOT_FOUND, e)
        } catch (e: IllegalArgumentException) {
            throw PdfOpenException(OpenFailure.NOT_FOUND, e)
        } ?: throw PdfOpenException(OpenFailure.NOT_FOUND)

        val tempFile = if (isSeekable(fd)) null else copyToCache(fd)
        val seekableFd = if (tempFile == null) fd else ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            OpenedPdf(name, PdfDocumentRenderer.open(seekableFd, tempFile))
        } catch (e: SecurityException) {
            tempFile?.delete()
            throw PdfOpenException(OpenFailure.PASSWORD_PROTECTED, e)
        } catch (e: IOException) {
            tempFile?.delete()
            throw PdfOpenException(OpenFailure.UNREADABLE, e)
        } catch (e: RuntimeException) {
            tempFile?.delete()
            throw PdfOpenException(OpenFailure.UNREADABLE, e)
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    } ?: uri.lastPathSegment

    private fun isSeekable(fd: ParcelFileDescriptor): Boolean = try {
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        true
    } catch (e: ErrnoException) {
        false
    }

    /** Copies a non-seekable source and closes [fd]. Earlier copies are removed first. */
    private fun copyToCache(fd: ParcelFileDescriptor): File {
        val dir = File(context.cacheDir, OPEN_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${UUID.randomUUID()}.pdf")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            file.delete()
            throw PdfOpenException(OpenFailure.UNREADABLE, e)
        }
        return file
    }

    private companion object {
        const val OPEN_DIR = "open"
    }
}
