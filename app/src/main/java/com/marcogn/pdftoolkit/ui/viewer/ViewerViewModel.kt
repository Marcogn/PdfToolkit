package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.data.recents.RecentsRepository
import com.marcogn.pdftoolkit.data.settings.ReadingPreferences
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationReader
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.render.PageKey
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.pdf.render.RenderScheduler
import com.marcogn.pdftoolkit.pdf.text.DocumentSearch
import com.marcogn.pdftoolkit.pdf.text.PdfTextExtractor
import com.marcogn.pdftoolkit.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

sealed interface ViewerUiState {
    data object Loading : ViewerUiState

    /** The file is protected (Android 15+: ask for the password); [wrongPassword] after a failed attempt. */
    data class PasswordRequired(val wrongPassword: Boolean) : ViewerUiState

    data class Ready(
        val uri: String,
        val displayName: String?,
        val sizeBytes: Long?,
        val pageSizes: List<PageSize>,
        val bitmaps: RenderScheduler<ImageBitmap>,
        val thumbnails: PageThumbnails,
        /** Text search in this document (spec §5.1); indexing starts when the reader opens it. */
        val search: DocumentSearch,
        /** Zero-based page to open on: the last one read, or 0. */
        val startPage: Int,
        /**
         * The document's annotations, drawn by the app because the renderer doesn't (spec §7.4,
         * ADR 0004); null until read, and if they can't be read.
         */
        val annotations: StateFlow<DocumentAnnotations?>,
    ) : ViewerUiState

    /** [inRecents]: the document is in the recents list, so "remove from recents" makes sense. */
    data class Error(val failure: OpenFailure, val inRecents: Boolean) : ViewerUiState
}

/**
 * Owns the open document for the lifetime of the viewer entry, so that a screen rotation doesn't
 * reopen the file or throw away the rendered pages. Also records the opening in recents and keeps
 * the last page read up to date.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val opener: PdfDocumentOpener,
    private val recents: RecentsRepository,
    private val readingPreferences: ReadingPreferences,
    private val textExtractor: PdfTextExtractor,
    private val annotationReader: AnnotationReader,
    val budget: RenderBudget,
) : ViewModel() {

    private val uriString = savedStateHandle.toRoute<Destination.Viewer>().uri
    private val uri = uriString.toUri()
    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingMode.CONTINUOUS)
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private var renderer: PdfDocumentRenderer? = null
    private var openJob: Job? = null
    private var pageSaveJob: Job? = null
    private var latestPage = -1

    init {
        openJob = viewModelScope.launch {
            _readingMode.value = readingPreferences.readingMode.first()
            open(password = null)
        }
    }

    /** Second attempt for a protected file. */
    fun submitPassword(password: String) {
        if (openJob?.isActive == true || renderer != null) return
        _uiState.value = ViewerUiState.Loading
        openJob = viewModelScope.launch { open(password) }
    }

    fun setReadingMode(mode: ReadingMode) {
        _readingMode.value = mode
        viewModelScope.launch { readingPreferences.setReadingMode(mode) }
    }

    /** The reader is now on [page]; saved a moment later, so scrolling doesn't hit the database. */
    fun onPageChanged(page: Int) {
        latestPage = page
        pageSaveJob?.cancel()
        pageSaveJob = viewModelScope.launch {
            delay(PAGE_SAVE_DELAY_MS)
            recents.saveLastPage(uriString, page)
        }
    }

    fun removeFromRecents(onDone: () -> Unit) {
        viewModelScope.launch {
            recents.remove(uriString)
            onDone()
        }
    }

    private suspend fun open(password: String?) {
        _uiState.value = try {
            val opened = opener.open(uri, password)
            renderer = opened.renderer
            val pageSizes = opened.renderer.pageSizes
            val name = opened.displayName ?: uriString
            val startPage = recents.recordOpened(uriString, name, opened.sizeBytes, pageSizes.size)
            val scheduler = RenderScheduler(
                scope = viewModelScope,
                dispatcher = Dispatchers.Default,
                budget = budget,
                bytesOf = { it.width.toLong() * it.height * 4 },
                render = { key -> opened.renderer.render(key)?.asImageBitmap() },
            )
            viewModelScope.launch { saveFirstPageThumbnail(opened.renderer) }
            val annotations = MutableStateFlow<DocumentAnnotations?>(null)
            viewModelScope.launch {
                // After the first pages: reading parses the whole file.
                delay(ANNOTATIONS_DELAY_MS)
                annotations.value = annotationReader.read({ opener.openStream(uri) }, password)
            }
            ViewerUiState.Ready(
                uri = uriString,
                displayName = opened.displayName,
                sizeBytes = opened.sizeBytes,
                pageSizes = pageSizes,
                bitmaps = scheduler,
                thumbnails = PageThumbnails(opened.renderer),
                search = DocumentSearch(
                    scope = viewModelScope,
                    extractor = textExtractor,
                    open = { opener.openStream(uri) },
                    // Kept in memory only, like the viewer's own copy (never saved).
                    password = password,
                    pageCount = pageSizes.size,
                ),
                startPage = startPage,
                annotations = annotations.asStateFlow(),
            )
        } catch (e: PdfOpenException) {
            when (e.failure) {
                OpenFailure.PASSWORD_PROTECTED -> ViewerUiState.PasswordRequired(wrongPassword = password != null)
                else -> ViewerUiState.Error(e.failure, inRecents = recents.contains(uriString))
            }
        }
    }

    /** After a moment, so it doesn't compete with the first pages for the renderer. */
    private suspend fun saveFirstPageThumbnail(renderer: PdfDocumentRenderer) {
        delay(THUMBNAIL_DELAY_MS)
        val size = renderer.pageSizes.first()
        val scale = RECENT_THUMBNAIL_HEIGHT_PX / size.height
        val key = PageKey(0, (size.width * scale).roundToInt().coerceAtLeast(1), RECENT_THUMBNAIL_HEIGHT_PX, scale)
        renderer.render(key)?.let { recents.saveThumbnail(uriString, it) }
    }

    override fun onCleared() {
        if (latestPage >= 0) recents.saveLastPage(uriString, latestPage)
        // Non-blocking: if a page is being rendered the renderer closes right after it.
        renderer?.close()
        (_uiState.value as? ViewerUiState.Ready)?.let {
            it.bitmaps.clear()
            it.thumbnails.clear()
        }
    }

    private companion object {
        const val PAGE_SAVE_DELAY_MS = 500L
        const val THUMBNAIL_DELAY_MS = 1_000L
        const val ANNOTATIONS_DELAY_MS = 300L
        const val RECENT_THUMBNAIL_HEIGHT_PX = 360
    }
}
