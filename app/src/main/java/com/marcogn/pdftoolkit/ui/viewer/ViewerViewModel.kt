package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.pdf.render.RenderScheduler
import com.marcogn.pdftoolkit.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ViewerUiState {
    data object Loading : ViewerUiState

    data class Ready(
        val displayName: String?,
        val pageSizes: List<PageSize>,
        val bitmaps: RenderScheduler<ImageBitmap>,
    ) : ViewerUiState

    data class Error(val failure: OpenFailure) : ViewerUiState
}

/**
 * Owns the open document for the lifetime of the viewer entry, so that a screen rotation doesn't
 * reopen the file or throw away the rendered pages.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    opener: PdfDocumentOpener,
    val budget: RenderBudget,
) : ViewModel() {

    private val uri = savedStateHandle.toRoute<Destination.Viewer>().uri.toUri()
    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var renderer: PdfDocumentRenderer? = null

    init {
        viewModelScope.launch {
            _uiState.value = try {
                val opened = opener.open(uri)
                renderer = opened.renderer
                val scheduler = RenderScheduler(
                    scope = viewModelScope,
                    dispatcher = Dispatchers.Default,
                    budget = budget,
                    bytesOf = { it.width.toLong() * it.height * 4 },
                    render = { key -> opened.renderer.render(key)?.asImageBitmap() },
                )
                ViewerUiState.Ready(opened.displayName, opened.renderer.pageSizes, scheduler)
            } catch (e: PdfOpenException) {
                ViewerUiState.Error(e.failure)
            }
        }
    }

    override fun onCleared() {
        // Non-blocking: if a page is being rendered the renderer closes right after it.
        renderer?.close()
        (_uiState.value as? ViewerUiState.Ready)?.bitmaps?.clear()
    }
}
