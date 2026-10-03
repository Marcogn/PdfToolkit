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
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

/** An open document and the name to show for it. */
class OpenedPdf(val displayName: String?, val sizeBytes: Long?, val renderer: PdfDocumentRenderer)

/**
 * Opens a PDF from a `content://` (SAF) or `file://` URI.
 *
 * `PdfRenderer` needs a seekable file descriptor. Some providers (mail attachments, some cloud
 * apps) hand out a pipe instead: in that case the file is first copied to `cacheDir/open/` and the
 * copy is deleted when the document is closed.
 */
class PdfDocumentOpener @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * @param password for a protected file (Android 15+); null for the first attempt.
     * @throws PdfOpenException with the reason, never the raw platform exception. A protected file
     * with no or a wrong [password] is [OpenFailure.PASSWORD_PROTECTED], or
     * [OpenFailure.PASSWORD_UNSUPPORTED] where `PdfRenderer` can't take a password.
     */
    suspend fun open(uri: Uri, password: String? = null): OpenedPdf = withContext(Dispatchers.IO) {
        val (name, size) = nameAndSize(uri)
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
            OpenedPdf(name, size, PdfDocumentRenderer.open(seekableFd, tempFile, password))
        } catch (e: SecurityException) {
            tempFile?.delete()
            throw PdfOpenException(
                if (PdfDocumentRenderer.SUPPORTS_PASSWORD) OpenFailure.PASSWORD_PROTECTED else OpenFailure.PASSWORD_UNSUPPORTED,
                e,
            )
        } catch (e: IOException) {
            tempFile?.delete()
            throw PdfOpenException(OpenFailure.UNREADABLE, e)
        } catch (e: RuntimeException) {
            tempFile?.delete()
            throw PdfOpenException(OpenFailure.UNREADABLE, e)
        }
    }

    /**
     * A fresh stream over the same file, for reading its text (spec §5.1); null if it can't be
     * opened any more. The caller closes it.
     */
    fun openStream(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun nameAndSize(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    size = if (cursor.isNull(1)) null else cursor.getLong(1)
                }
            }
        } catch (e: RuntimeException) {
            // Provider without the columns: fall back to the URI.
        }
        return (name ?: uri.lastPathSegment) to size
    }

    private fun isSeekable(fd: ParcelFileDescriptor): Boolean = try {
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        true
    } catch (e: ErrnoException) {
        false
    }

    /**
     * Copies a non-seekable source and closes [fd]. Only stale copies (left by a killed process)
     * are removed: several documents can be open at once (merge, added pages), each deletes its
     * own copy when closed.
     */
    private fun copyToCache(fd: ParcelFileDescriptor): File {
        val dir = File(context.cacheDir, OPEN_DIR).apply { mkdirs() }
        val limit = System.currentTimeMillis() - STALE_COPY_MS
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
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
        const val STALE_COPY_MS = 60L * 60 * 1000
    }
}
