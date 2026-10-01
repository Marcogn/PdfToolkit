package com.marcogn.pdftoolkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.recents.RecentsRepository
import com.marcogn.pdftoolkit.data.settings.ReadingPreferences
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Viewer-related settings (spec §10): default reading mode, clear recents, clear thumbnail cache. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val readingPreferences: ReadingPreferences,
    private val recents: RecentsRepository,
) : ViewModel() {

    val readingMode: StateFlow<ReadingMode> = readingPreferences.readingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReadingMode.CONTINUOUS)

    fun onReadingModeSelected(mode: ReadingMode) {
        viewModelScope.launch { readingPreferences.setReadingMode(mode) }
    }

    /** [onDone] runs on the main thread when the list is empty. */
    fun clearRecents(onDone: () -> Unit) {
        viewModelScope.launch {
            recents.clear()
            onDone()
        }
    }

    fun clearThumbnails(onDone: () -> Unit) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) { recents.clearThumbnails() }
            onDone()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
