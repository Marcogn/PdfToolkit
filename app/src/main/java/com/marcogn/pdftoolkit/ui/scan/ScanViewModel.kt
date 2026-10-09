package com.marcogn.pdftoolkit.ui.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.save.PdfSaver
import com.marcogn.pdftoolkit.pdf.ocr.OcrOutcome
import com.marcogn.pdftoolkit.pdf.ocr.OcrProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

sealed interface ScanEvent {
    /** The scan is now a file the app owns, at [path]: time to ask where to keep it. */
    data class Staged(val path: String) : ScanEvent

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
 * Temporary (plan 10a, until 10b makes it an option with cancellation and a background run): the staged
 * scan is made searchable ([OcrProcessor]) before the user is asked where to keep it. If recognition fails
 * the scan is kept as it came from the scanner.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocr: OcrProcessor,
) : ViewModel() {

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    /** Progress (0..1) of the text recognition of the staged scan; null when none is running. */
    private val _recognising = MutableStateFlow<Float?>(null)
    val recognising: StateFlow<Float?> = _recognising.asStateFlow()

    fun stage(source: Uri) {
        viewModelScope.launch {
            val staged = File(PdfSaver.workDir(context), "scan-${UUID.randomUUID()}.pdf")
            val ok = runCatchingIo { copy(source, staged.toUri()) }
            if (ok) {
                _events.trySend(ScanEvent.Staged(recognise(staged).absolutePath))
            } else {
                staged.delete()
                _events.trySend(ScanEvent.Failed)
            }
        }
    }

    /** Writes the staged scan over [target] (a fresh `CreateDocument` file); on failure the empty file is removed. */
    fun save(stagedPath: String, target: Uri) {
        viewModelScope.launch {
            val ok = runCatchingIo { copy(File(stagedPath).toUri(), target) }
            if (ok) {
                File(stagedPath).delete()
                _events.trySend(ScanEvent.Saved(target))
            } else {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) } }
                _events.trySend(ScanEvent.Failed)
            }
        }
    }

    /** The searchable copy of [staged] (which is then deleted), or [staged] itself if there is nothing to add or recognition fails. */
    private suspend fun recognise(staged: File): File {
        val output = File(PdfSaver.workDir(context), "scan-${UUID.randomUUID()}.pdf")
        _recognising.value = 0f
        return try {
            when (ocr.run(staged, output) { _recognising.value = it }) {
                is OcrOutcome.Written -> {
                    staged.delete()
                    output
                }
                OcrOutcome.AlreadyText, OcrOutcome.NoTextFound -> staged
            }
        } catch (e: CancellationException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            // ML Kit, rendering or writing: the scan itself is still good.
            output.delete()
            staged
        } finally {
            _recognising.value = null
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
