package com.marcogn.pdftoolkit.ui.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.data.save.SaveProgress
import com.marcogn.pdftoolkit.data.save.SaveRequest
import com.marcogn.pdftoolkit.data.save.SaveScheduler
import com.marcogn.pdftoolkit.data.settings.SavePreferences
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface EditUiState {
    data object Loading : EditUiState

    data class Error(val failure: OpenFailure) : EditUiState

    /** [session] is what the grids show; [pageSizes] are those of the *source* pages, indexed by `pageIndex`. */
    data class Ready(
        val displayName: String,
        val pageSizes: List<PageSize>,
        val thumbnails: PageThumbnails,
        val session: EditSession,
        val hasUnsavedChanges: Boolean,
    ) : EditUiState
}

/** Where a save stands. [Saved] after a copy offers "open" and "share"; [Overwritten] ends the editing. */
sealed interface SaveUiState {
    data object Idle : SaveUiState
    data class Saving(val fraction: Float) : SaveUiState
    data class Saved(val uri: String) : SaveUiState
    data class Overwritten(val uri: String) : SaveUiState
    data class Failed(val failure: SaveFailure) : SaveUiState
}

/**
 * Owns the edit session of one document for the lifetime of the edit entry (rotation keeps it),
 * its own renderer for the thumbnails and the state of the save in progress.
 *
 * Process death: the page list (not the undo history) is kept in [SavedStateHandle]; the source
 * document is reopened from its URI.
 */
@HiltViewModel
class EditViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val opener: PdfDocumentOpener,
    private val scheduler: SaveScheduler,
    private val savePreferences: SavePreferences,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Destination.Edit>()
    val sourceUri: String = route.uri

    private val _uiState = MutableStateFlow<EditUiState>(EditUiState.Loading)
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveUiState>(SaveUiState.Idle)
    val saveState: StateFlow<SaveUiState> = _saveState.asStateFlow()

    private val _overwriteChoice = MutableStateFlow(false)
    /** Last choice in the save dialog (spec §8). */
    val overwriteChoice: StateFlow<Boolean> = _overwriteChoice.asStateFlow()

    /** Whether the original accepts writes, so "overwrite" is offered. Computed once, after opening. */
    private val _canOverwrite = MutableStateFlow(false)
    val canOverwrite: StateFlow<Boolean> = _canOverwrite.asStateFlow()

    private var renderer: PdfDocumentRenderer? = null
    private var displayName = ""
    private var sourcePageCount = 0
    private var savedEncoded: String? = null
    private var saveJob: Job? = null
    private var pendingOverwrite = false

    init {
        viewModelScope.launch {
            _overwriteChoice.value = savePreferences.overwrite.first()
            open()
        }
    }

    private suspend fun open() {
        try {
            val opened = opener.open(sourceUri.toUri())
            renderer = opened.renderer
            displayName = opened.displayName ?: sourceUri
            sourcePageCount = opened.renderer.pageCount
            _canOverwrite.value = hasWriteAccess(sourceUri.toUri())
            val restored = savedStateHandle.get<String>(KEY_PAGES)?.let { EditSession.decode(it, sourcePageCount) }
            publish(restored ?: EditSession.of(sourcePageCount), PageThumbnails(opened.renderer))
        } catch (e: PdfOpenException) {
            _uiState.value = EditUiState.Error(e.failure)
        }
    }

    // --- Operations: each one swaps the session; the grids react to the new state. ---

    fun remove(ids: Set<String>): Boolean = apply { it.remove(ids) }

    fun move(from: Int, to: Int) = apply { it.move(from, to) }

    fun moveToStart(id: String) = apply { it.moveToStart(id) }

    fun moveToEnd(id: String) = apply { it.moveToEnd(id) }

    fun rotate(ids: Set<String>, degrees: Int = QUARTER) = apply { it.rotate(ids, degrees) }

    fun undo() = apply { it.undo() }

    fun redo() = apply { it.redo() }

    /** True if the operation changed something. */
    private fun apply(operation: (EditSession) -> EditSession): Boolean {
        val ready = _uiState.value as? EditUiState.Ready ?: return false
        val updated = operation(ready.session)
        if (updated === ready.session) return false
        publish(updated, ready.thumbnails)
        return true
    }

    private fun publish(session: EditSession, thumbnails: PageThumbnails) {
        savedStateHandle[KEY_PAGES] = session.encode()
        _uiState.value = EditUiState.Ready(
            displayName = displayName,
            pageSizes = renderer?.pageSizes.orEmpty(),
            thumbnails = thumbnails,
            session = session,
            hasUnsavedChanges = session.isModified && session.encode() != savedEncoded,
        )
    }

    // --- Saving ---

    fun setOverwriteChoice(overwrite: Boolean) {
        _overwriteChoice.value = overwrite
        viewModelScope.launch { savePreferences.setOverwrite(overwrite) }
    }

    /** Suggested name of a copy: `<name>_modificato.pdf` (spec §6.7). */
    fun suggestedCopyName(suffix: String): String = displayName.removeSuffix(".pdf").removeSuffix(".PDF") + suffix + ".pdf"

    /** Writes the session to [destination] in the background. [overwrite]: [destination] is the original. */
    fun save(destination: Uri, overwrite: Boolean) {
        val ready = _uiState.value as? EditUiState.Ready ?: return
        if (saveJob?.isActive == true) return
        if (!overwrite) takeWritePermission(destination)
        val request = SaveRequest(
            sourceUri = sourceUri,
            destinationUri = destination.toString(),
            sourcePageCount = sourcePageCount,
            pages = ready.session.encode(),
        )
        pendingOverwrite = overwrite
        _saveState.value = SaveUiState.Saving(0f)
        val savedPages = ready.session.encode()
        saveJob = viewModelScope.launch {
            val id: UUID = scheduler.enqueue(request)
            scheduler.observe(id).collect { progress ->
                when (progress) {
                    null -> Unit
                    is SaveProgress.Running -> _saveState.value = SaveUiState.Saving(progress.fraction)
                    is SaveProgress.Failed -> {
                        _saveState.value = SaveUiState.Failed(progress.failure)
                        saveJob?.cancel()
                    }
                    is SaveProgress.Done -> {
                        savedEncoded = savedPages
                        (_uiState.value as? EditUiState.Ready)?.let { publish(it.session, it.thumbnails) }
                        _saveState.value = if (pendingOverwrite) SaveUiState.Overwritten(progress.destinationUri) else SaveUiState.Saved(progress.destinationUri)
                        saveJob?.cancel()
                    }
                }
            }
        }
    }

    fun dismissSaveResult() {
        if (_saveState.value !is SaveUiState.Saving) _saveState.value = SaveUiState.Idle
    }

    /** The destination of a copy keeps its write grant, so a save resumed by the system can still write. */
    private fun takeWritePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Temporary grant only: fine while the app lives.
        }
    }

    private fun hasWriteAccess(uri: Uri): Boolean =
        uri.scheme == "content" &&
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
        renderer?.close()
        (_uiState.value as? EditUiState.Ready)?.thumbnails?.clear()
    }

    private companion object {
        const val KEY_PAGES = "pages"
        const val QUARTER = 90
    }
}
