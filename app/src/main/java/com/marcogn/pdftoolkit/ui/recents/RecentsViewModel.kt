package com.marcogn.pdftoolkit.ui.recents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.recents.RecentDocument
import com.marcogn.pdftoolkit.data.recents.RecentsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A recent document and whether it can still be opened (spec §4.1: shown as unavailable if not). */
data class RecentItem(val document: RecentDocument, val accessible: Boolean)

/** Recents for Home and for the "Recent files" screen. Null until the first load, so no empty-state flash. */
@HiltViewModel
class RecentsViewModel @Inject constructor(private val repository: RecentsRepository) : ViewModel() {

    val recents: StateFlow<List<RecentItem>?> = repository.recents
        .map { documents -> documents.map { RecentItem(it, repository.isAccessible(it.uri)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun remove(uri: String) {
        viewModelScope.launch { repository.remove(uri) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
