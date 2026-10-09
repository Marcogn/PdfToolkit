package com.marcogn.pdftoolkit.ui.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.save.PdfSaver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
 */
@HiltViewModel
class ScanViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    fun stage(source: Uri) {
        viewModelScope.launch {
            val staged = File(PdfSaver.workDir(context), "scan-${UUID.randomUUID()}.pdf")
            val ok = runCatchingIo { copy(source, staged.toUri()) }
            if (ok) {
                _events.trySend(ScanEvent.Staged(staged.absolutePath))
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
