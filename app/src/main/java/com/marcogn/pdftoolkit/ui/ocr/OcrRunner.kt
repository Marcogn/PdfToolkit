package com.marcogn.pdftoolkit.ui.ocr

import com.marcogn.pdftoolkit.data.ocr.OcrProgress
import com.marcogn.pdftoolkit.data.ocr.OcrQueue
import com.marcogn.pdftoolkit.data.ocr.OcrRequest
import com.marcogn.pdftoolkit.data.ocr.OcrResult
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Where a background text recognition stands, as the screens see it (plan 10b). */
sealed interface OcrUiState {
    data object Idle : OcrUiState

    /** [cancellable] is false for the last moment, the copy over the destination, which must not be cut. */
    data class Running(val fraction: Float, val cancellable: Boolean) : OcrUiState

    /** Finished; [overwrite]: [destinationUri] is the original. */
    data class Done(val destinationUri: String, val overwrite: Boolean, val result: OcrResult) : OcrUiState

    data class Failed(val failure: SaveFailure) : OcrUiState
}

/**
 * One background recognition at a time (plan 10b): hands the request to the [OcrQueue] and turns its
 * progress into an [OcrUiState], like [com.marcogn.pdftoolkit.ui.edit.SaveRunner] for saves. Shared by the
 * viewer and the scan flow.
 */
class OcrRunner(private val scope: CoroutineScope, private val queue: OcrQueue) {

    private val _state = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val state: StateFlow<OcrUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var id: UUID? = null
    private var cancelRequested = false

    val isRunning: Boolean get() = _state.value is OcrUiState.Running

    /**
     * Starts recognising [request]; [overwrite] says whether its destination is the original.
     * [onEnd] runs once the run is over, before the state changes, with whether a file was written
     * (false when the text was there already, none was found, or the run failed or was cancelled:
     * the caller removes the empty file it asked for). False if one is already running.
     */
    fun start(request: OcrRequest, overwrite: Boolean, onEnd: (written: Boolean) -> Unit): Boolean {
        if (isRunning) return false
        _state.value = OcrUiState.Running(0f, cancellable = true)
        cancelRequested = false
        job = scope.launch {
            val queued = try {
                queue.enqueue(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The request file or WorkManager failed: nothing was started.
                finish(OcrUiState.Failed(SaveFailure.FAILED), written = false, onEnd)
                return@launch
            }
            id = queued
            if (cancelRequested) queue.cancel(queued)
            queue.observe(queued).collect { progress ->
                when (progress) {
                    null -> Unit
                    is OcrProgress.Running -> _state.value = OcrUiState.Running(progress.fraction, progress.fraction < COPYING)
                    is OcrProgress.Failed -> finish(OcrUiState.Failed(progress.failure), written = false, onEnd)
                    OcrProgress.Cancelled -> finish(OcrUiState.Idle, written = false, onEnd)
                    is OcrProgress.Done -> finish(
                        OcrUiState.Done(progress.destinationUri, overwrite, progress.result),
                        written = progress.result == OcrResult.WRITTEN,
                        onEnd,
                    )
                }
            }
        }
        return true
    }

    private fun finish(next: OcrUiState, written: Boolean, onEnd: (Boolean) -> Unit) {
        onEnd(written)
        _state.value = next
        job?.cancel()
    }

    /** Stops the run; the state goes back to idle when WorkManager confirms. Ignored in the last moment. */
    fun cancel() {
        val running = _state.value as? OcrUiState.Running ?: return
        if (!running.cancellable) return
        // Before the run is queued there is no id yet: it is cancelled as soon as it has one.
        cancelRequested = true
        id?.let(queue::cancel)
    }

    /** The result has been shown: back to idle (not while running). */
    fun dismiss() {
        if (_state.value !is OcrUiState.Running) _state.value = OcrUiState.Idle
    }

    private companion object {
        /** From here the worker copies over the destination (see [com.marcogn.pdftoolkit.data.ocr.OcrJob]). */
        const val COPYING = 0.96f
    }
}
