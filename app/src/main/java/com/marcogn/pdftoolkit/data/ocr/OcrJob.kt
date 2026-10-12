package com.marcogn.pdftoolkit.data.ocr

import android.content.Context
import androidx.core.net.toUri
import com.marcogn.pdftoolkit.data.save.PdfSaver
import com.marcogn.pdftoolkit.data.save.copyFileToUri
import com.marcogn.pdftoolkit.data.save.copyUriToFile
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.pdf.ocr.OcrOutcome
import com.marcogn.pdftoolkit.pdf.ocr.OcrProcessor
import com.marcogn.pdftoolkit.pdf.text.TextExtractionException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * One text recognition outside the UI (plan 10b), the way [PdfSaver] saves: the source is copied to
 * `cacheDir/work/`, [OcrProcessor] writes the searchable PDF there, and only a finished file is
 * copied over the destination (ADR 0003). Cancelling the coroutine stops between pages; the
 * destination is not touched until the very last step, which, once begun, always completes. The
 * temporary files are always deleted.
 */
class OcrJob @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processor: OcrProcessor,
) {

    /** @throws SaveException with the reason; [CancellationException] when cancelled. */
    suspend fun run(request: OcrRequest, onProgress: (Float) -> Unit = {}): OcrResult {
        val dir = PdfSaver.workDir(context)
        val id = UUID.randomUUID().toString()
        val sourceCopy = File(dir, "$id-ocr-source.pdf")
        val result = File(dir, "$id-ocr-result.pdf")
        try {
            withContext(Dispatchers.IO) { context.copyUriToFile(request.sourceUri.toUri(), sourceCopy) }
            onProgress(COPIED_SOURCE)
            val outcome = recognise(sourceCopy, result) { onProgress(COPIED_SOURCE + it * (RECOGNISED - COPIED_SOURCE)) }
            return when (outcome) {
                is OcrOutcome.Written -> {
                    // A cancel that came during the last page still counts; after this point it can't cut the copy:
                    // a half-written destination would be worse than a late stop.
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable + Dispatchers.IO) { context.copyFileToUri(result, request.destinationUri.toUri()) }
                    onProgress(1f)
                    OcrResult.WRITTEN
                }
                OcrOutcome.AlreadyText -> OcrResult.ALREADY_TEXT
                OcrOutcome.NoTextFound -> OcrResult.NO_TEXT
            }
        } finally {
            sourceCopy.delete()
            result.delete()
        }
    }

    private suspend fun recognise(source: File, output: File, onProgress: (Float) -> Unit): OcrOutcome = try {
        processor.run(source, output, onProgress)
    } catch (e: SaveException) {
        throw e
    } catch (e: TextExtractionException) {
        // The processor opens the PDF without a password (scans have none).
        throw SaveException(if (e.passwordRequired) SaveFailure.PROTECTED else SaveFailure.SOURCE_UNREADABLE, e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw SaveException(SaveFailure.FAILED, e)
    } catch (e: RuntimeException) {
        // ML Kit and pdfium report some failures as unchecked exceptions.
        throw SaveException(SaveFailure.FAILED, e)
    } catch (e: OutOfMemoryError) {
        throw SaveException(SaveFailure.OUT_OF_MEMORY, e)
    }

    private companion object {
        const val COPIED_SOURCE = 0.03f

        /** Share of the progress bar before the copy over the destination. */
        const val RECOGNISED = 0.97f
    }
}
