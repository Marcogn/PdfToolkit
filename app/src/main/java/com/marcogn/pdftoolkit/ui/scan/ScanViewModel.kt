package com.marcogn.pdftoolkit.ui.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.save.PdfSaver
import com.marcogn.pdftoolkit.data.save.UserFileWrites
import com.marcogn.pdftoolkit.data.ocr.OcrRequest
import com.marcogn.pdftoolkit.data.ocr.OcrResult
import com.marcogn.pdftoolkit.data.ocr.OcrScheduler
import com.marcogn.pdftoolkit.ui.ocr.OcrRunner
import com.marcogn.pdftoolkit.ui.ocr.OcrUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** Why a scan is kept without searchable text after the user asked for it. */
enum class ScanNotice { RECOGNITION_FAILED, NO_TEXT_FOUND }

sealed interface ScanEvent {
    /** The scan is now a file the app owns, at [path]: time to ask whether to recognise its text. */
    data class Scanned(val path: String) : ScanEvent

    /** The scan to keep is the file at [path] (searchable or not, see [notice]): time to ask where to keep it. */
    data class Staged(val path: String, val notice: ScanNotice? = null) : ScanEvent

    /** The scan is written to [uri]. */
    data class Saved(val uri: Uri) : ScanEvent

    /** Staging or writing failed; the staged file (if any) is still there to try again. */
    data object Failed : ScanEvent
}

/**
 * Keeps the scanned PDF safe between the scanner and the destination the user picks. Play services' scan
 * folder and its URI grant are not ours to rely on once the app is backgrounded, so the PDF is first copied
 * to `cacheDir/work/` (cleaned at startup after an hour, like the other work files). Copies run in
 * [viewModelScope], so a rotation does not cut one short.
 *
 * Text recognition is an option (plan 10b): [recognise] runs it in the background ([OcrRunner], WorkManager)
 * on the staged scan into a second staged file. When it does not produce one (cancelled, nothing found,
 * failed) the scan as it came from the scanner is kept.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    ocrScheduler: OcrScheduler,
    private val userFileWrites: UserFileWrites,
) : ViewModel() {

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    private val ocr = OcrRunner(viewModelScope, ocrScheduler)

    /** Progress of the text recognition of the staged scan; idle when none is running. */
    val recognising: StateFlow<OcrUiState> = ocr.state

    fun stage(source: Uri) {
        viewModelScope.launch {
            val staged = File(PdfSaver.workDir(context), "scan-${UUID.randomUUID()}.pdf")
            val ok = runCatchingIo { copy(source, staged.toUri()) }
            if (ok) {
                _events.trySend(ScanEvent.Scanned(staged.absolutePath))
            } else {
                staged.delete()
                _events.trySend(ScanEvent.Failed)
            }
        }
    }

    /**
     * Recognises the text of the staged scan at [stagedPath] in the background. Ends in [ScanEvent.Staged]:
     * with the searchable copy (the raw scan is deleted), or with the raw scan and a notice if there is no copy.
     */
    fun recognise(stagedPath: String) {
        val output = File(PdfSaver.workDir(context), "scan-${UUID.randomUUID()}.pdf")
        val started = ocr.start(OcrRequest(File(stagedPath).toUri().toString(), output.toUri().toString()), overwrite = false) { written ->
            if (written) File(stagedPath).delete() else output.delete()
        }
        if (!started) return
        viewModelScope.launch {
            // start() set Running already, so the first other state is how the run ended (Idle: cancelled).
            val end = ocr.state.first { it !is OcrUiState.Running }
            ocr.dismiss()
            _events.trySend(
                when {
                    end is OcrUiState.Done && end.result == OcrResult.WRITTEN -> ScanEvent.Staged(output.absolutePath)
                    end is OcrUiState.Done && end.result == OcrResult.NO_TEXT -> ScanEvent.Staged(stagedPath, ScanNotice.NO_TEXT_FOUND)
                    end is OcrUiState.Failed -> ScanEvent.Staged(stagedPath, ScanNotice.RECOGNITION_FAILED)
                    else -> ScanEvent.Staged(stagedPath)
                },
            )
        }
    }

    /** The user declined recognition: the scan as it is goes on to be saved. */
    fun stageWithoutRecognition(stagedPath: String) {
        _events.trySend(ScanEvent.Staged(stagedPath))
    }

    /** The user chose "Save without text" while recognition runs. */
    fun skipRecognition() = ocr.cancel()

    /** Writes the staged scan over [target] (a fresh `CreateDocument` file); on failure the empty file is removed. */
    fun save(stagedPath: String, target: Uri) {
        viewModelScope.launch {
            // Tracked: an update install ends the process and would cut this copy off (plan A).
            val ok = userFileWrites.track { runCatchingIo { copy(File(stagedPath).toUri(), target) } }
            if (ok) {
                File(stagedPath).delete()
                _events.trySend(ScanEvent.Saved(target))
            } else {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) } }
                _events.trySend(ScanEvent.Failed)
            }
        }
    }

    fun discard(stagedPath: String) {
        File(stagedPath).delete()
    }

    /** "wt" so a provider that reuses a name can't leave old bytes behind. */
    private suspend fun copy(from: Uri, to: Uri) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(from) ?: throw IOException("Cannot read the scan")
        input.use { source ->
            val output = context.contentResolver.openOutputStream(to, "wt") ?: throw IOException("Cannot write the destination")
            output.use { sink -> source.copyTo(sink) }
        }
    }

    /** Providers throw more than [IOException] when a document or its account is gone; none of it should crash. */
    private suspend fun runCatchingIo(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

}
