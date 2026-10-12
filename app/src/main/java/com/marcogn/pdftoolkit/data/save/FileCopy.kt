package com.marcogn.pdftoolkit.data.save

import android.content.Context
import android.net.Uri
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Copies the document at [uri] into [target]; any failure is a [SaveFailure.SOURCE_UNREADABLE] [SaveException]. */
internal fun Context.copyUriToFile(uri: Uri, target: File) {
    try {
        val input = contentResolver.openInputStream(uri) ?: throw FileNotFoundException("No stream for $uri")
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
    } catch (e: FileNotFoundException) {
        throw SaveException(SaveFailure.SOURCE_UNREADABLE, e)
    } catch (e: SecurityException) {
        throw SaveException(SaveFailure.SOURCE_UNREADABLE, e)
    } catch (e: IOException) {
        throw SaveException(SaveFailure.SOURCE_UNREADABLE, e)
    }
}

/** "wt" truncates: some providers keep the old tail of the file with plain "w". A failure is [SaveFailure.DESTINATION_UNWRITABLE]. */
internal fun Context.copyFileToUri(file: File, uri: Uri) {
    try {
        val output = contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("No stream for $uri")
        output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    } catch (e: FileNotFoundException) {
        throw SaveException(SaveFailure.DESTINATION_UNWRITABLE, e)
    } catch (e: SecurityException) {
        throw SaveException(SaveFailure.DESTINATION_UNWRITABLE, e)
    } catch (e: IOException) {
        throw SaveException(SaveFailure.DESTINATION_UNWRITABLE, e)
    }
}
