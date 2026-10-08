package com.marcogn.pdftoolkit.ui.edit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import com.marcogn.pdftoolkit.data.save.SaveProgress
import com.marcogn.pdftoolkit.data.save.SaveRequest
import com.marcogn.pdftoolkit.data.save.SaveScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One background save at a time, as the screens see it (spec §6.7): hands the request to
 * [SaveScheduler] (ADR 0003) and turns its progress into a [SaveUiState]. Shared by the edit screen
 * and the viewer (ADR 0005), so both go through the same save engine.
 */
class SaveRunner(private val scope: CoroutineScope, private val scheduler: SaveScheduler) {

    private val _state = MutableStateFlow<SaveUiState>(SaveUiState.Idle)
    val state: StateFlow<SaveUiState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts writing [request]; [overwrite] says whether its destination is the original, which ends
     * in [SaveUiState.Overwritten] instead of [SaveUiState.Saved]. [onDone] runs once the file is
     * written, before the state changes. False if a save is already running.
     */
    fun start(request: SaveRequest, overwrite: Boolean, onDone: () -> Unit): Boolean {
        if (isRunning) return false
        _state.value = SaveUiState.Saving(0f)
        job = scope.launch {
            val id: UUID = scheduler.enqueue(request)
            scheduler.observe(id).collect { progress ->
                when (progress) {
                    null -> Unit
                    is SaveProgress.Running -> _state.value = SaveUiState.Saving(progress.fraction)
                    is SaveProgress.Failed -> {
                        _state.value = SaveUiState.Failed(progress.failure)
                        job?.cancel()
                    }
                    is SaveProgress.Done -> {
                        onDone()
                        _state.value = if (overwrite) SaveUiState.Overwritten(progress.destinationUri) else SaveUiState.Saved(progress.destinationUri)
                        job?.cancel()
                    }
                }
            }
        }
        return true
    }

    /** The result has been shown: back to idle (not while saving). */
    fun dismiss() {
        if (_state.value !is SaveUiState.Saving) _state.value = SaveUiState.Idle
    }
}

/** The destination of a copy keeps its write grant, so a save resumed by the system can still write. */
fun Context.takeWritePermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (e: SecurityException) {
        // Temporary grant only: fine while the app lives.
    }
}

/** Whether the app may write [uri], so "overwrite" can be offered (spec §6.7). */
fun Context.hasWriteAccess(uri: Uri): Boolean =
    uri.scheme == "content" &&
        checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
