package com.marcogn.pdftoolkit.ui.recents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.files.AllFilesAccess
import com.marcogn.pdftoolkit.data.recents.RecentDocument
import com.marcogn.pdftoolkit.data.recents.RecentsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A recent document and whether it can still be opened (spec §4.1: shown as unavailable if not).
 * [accessExpired]: not openable, and it was last opened with a temporary grant only (issue #47), so the
 * grant has most likely ended rather than the file being gone.
 */
data class RecentItem(val document: RecentDocument, val accessible: Boolean, val allFilesAccessOff: Boolean = false) {
    val accessExpired: Boolean get() = !accessible && document.temporaryAccess

    /** Kept by path (ADR 0007) and unreadable while all files access is off: likely still there. */
    val needsAllFilesAccess: Boolean get() = !accessible && allFilesAccessOff && document.uri.startsWith("file:")
}

/** Recents for Home and for the "Recent files" screen. Null until the first load, so no empty-state flash. */
@HiltViewModel
class RecentsViewModel @Inject constructor(
    private val repository: RecentsRepository,
    private val allFilesAccess: AllFilesAccess,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    val recents: StateFlow<List<RecentItem>?> = repository.recents
        .combine(refreshes) { documents, _ -> documents }
        .map { documents ->
            val accessOff = !allFilesAccess.isGranted()
            documents.map { RecentItem(it, repository.isAccessible(it.uri), accessOff) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Checks again which entries open (on resume: files move, all files access changes in Settings). */
    fun refresh() {
        refreshes.value++
    }

    fun remove(uri: String) {
        viewModelScope.launch { repository.remove(uri) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
