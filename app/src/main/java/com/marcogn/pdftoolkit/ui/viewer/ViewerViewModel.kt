package com.marcogn.pdftoolkit.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.data.images.ImageImporter
import com.marcogn.pdftoolkit.data.recents.RecentsRepository
import com.marcogn.pdftoolkit.data.save.SaveScheduler
import com.marcogn.pdftoolkit.data.settings.SavePreferences
import com.marcogn.pdftoolkit.data.settings.ReadingPreferences
import com.marcogn.pdftoolkit.domain.fill.FormDocument
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationReader
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.edit.FontCoverage
import com.marcogn.pdftoolkit.pdf.edit.PageImageLoader
import com.marcogn.pdftoolkit.pdf.forms.FormReader
import com.marcogn.pdftoolkit.pdf.render.PageKey
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.pdf.render.RenderScheduler
import com.marcogn.pdftoolkit.pdf.text.DocumentSearch
import com.marcogn.pdftoolkit.pdf.text.PageTextReader
import com.marcogn.pdftoolkit.pdf.text.PdfTextExtractor
import com.marcogn.pdftoolkit.ui.edit.PickedImage
import com.marcogn.pdftoolkit.ui.edit.SaveRunner
import com.marcogn.pdftoolkit.ui.edit.SaveUiState
import com.marcogn.pdftoolkit.ui.edit.hasWriteAccess
import com.marcogn.pdftoolkit.ui.edit.takeWritePermission
import com.marcogn.pdftoolkit.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /** The text of single pages, for selecting and copying (spec §7.4); closed with the view model. */
        val textReader: PageTextReader,
        /** What the reader changed on the pages and hasn't saved yet (plan V-a, ADR 0005). */
        val editing: ViewerEditSession,
        /** Whether the pages can be edited: the annotations must be read first (ADR 0005). */
        val editAvailability: StateFlow<EditAvailability>,
    ) : ViewerUiState

    /** [inRecents]: the document is in the recents list, so "remove from recents" makes sense. */
    data class Error(val failure: OpenFailure, val inRecents: Boolean) : ViewerUiState
}

/** Reading the document's form for "Fill and sign" (plan V-c). */
sealed interface FormLoad {
    data object Loading : FormLoad
    data class Ready(val form: FormDocument) : FormLoad
    data object Failed : FormLoad
}

/** Whether the viewer can edit the document (plan V-a). */
enum class EditAvailability {
    /** The annotations are being read: the tools wait for them (they give every page its user space). */
    LOADING,
    READY,

    /** Opened with a password: PdfBox can't write it (ADR 0003, spec §14). */
    PROTECTED,

    /** The annotations couldn't be read, so edits couldn't be placed or saved safely. */
    UNREADABLE,
}

/**
 * Owns the open document for the lifetime of the viewer entry, so that a screen rotation doesn't
 * reopen the file or throw away the rendered pages. Also records the opening in recents and keeps
 * the last page read up to date.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val opener: PdfDocumentOpener,
    scheduler: SaveScheduler,
    private val savePreferences: SavePreferences,
    private val recents: RecentsRepository,
    private val readingPreferences: ReadingPreferences,
    private val textExtractor: PdfTextExtractor,
    private val annotationReader: AnnotationReader,
    private val formReader: FormReader,
    private val fontCoverage: FontCoverage,
    private val imageImporter: ImageImporter,
    private val imageLoader: PageImageLoader,
    val budget: RenderBudget,
) : ViewModel() {

    private val uriString = savedStateHandle.toRoute<Destination.Viewer>().uri
    private val uri = uriString.toUri()
    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingMode.CONTINUOUS)
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private val saveRunner = SaveRunner(viewModelScope, scheduler)
    /** The save started from the viewer (plan V-a): the same engine as the edit screen (ADR 0005). */
    val saveState: StateFlow<SaveUiState> = saveRunner.state

    private val _overwriteChoice = MutableStateFlow(false)
    /** Last choice in the save dialog (spec §8), shared with the edit screen. */
    val overwriteChoice: StateFlow<Boolean> = _overwriteChoice.asStateFlow()

    private val _canOverwrite = MutableStateFlow(false)
    /** Whether the original accepts writes, so "overwrite" is offered. */
    val canOverwrite: StateFlow<Boolean> = _canOverwrite.asStateFlow()

    private val _flattenInkChoice = MutableStateFlow(false)
    /** "Make final" for drawings as chosen in the save dialog (spec §7.4), off by default. */
    val flattenInkChoice: StateFlow<Boolean> = _flattenInkChoice.asStateFlow()

    private val _flattenFormChoice = MutableStateFlow<Boolean?>(null)
    /** "Make final" for the form as chosen in the save dialog; null = on with a signature (spec §6.5). */
    val flattenFormChoice: StateFlow<Boolean?> = _flattenFormChoice.asStateFlow()

    private val _form = MutableStateFlow<FormLoad?>(null)
    /** The form fields and page boxes for "Fill and sign" (plan V-c); null until first needed ([loadForm]). */
    val form: StateFlow<FormLoad?> = _form.asStateFlow()
    private var formJob: Job? = null

    private var renderer: PdfDocumentRenderer? = null
    private var openJob: Job? = null
    private var pageSaveJob: Job? = null
    private var latestPage = -1

    init {
        openJob = viewModelScope.launch {
            _readingMode.value = readingPreferences.readingMode.first()
            _overwriteChoice.value = savePreferences.overwrite.first()
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
            val availability = MutableStateFlow(if (password != null) EditAvailability.PROTECTED else EditAvailability.LOADING)
            viewModelScope.launch {
                // After the first pages: reading parses the whole file.
                delay(ANNOTATIONS_DELAY_MS)
                val read = annotationReader.read({ opener.openStream(uri) }, password)
                annotations.value = read
                if (availability.value == EditAvailability.LOADING) {
                    availability.value = if (read != null && read.pageBoxes.size == pageSizes.size) EditAvailability.READY else EditAvailability.UNREADABLE
                }
            }
            _canOverwrite.value = password == null && context.hasWriteAccess(uri)
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
                textReader = textExtractor.reader({ opener.openStream(uri) }, password),
                editing = ViewerEditSession(savedStateHandle, pageSizes.size),
                editAvailability = availability.asStateFlow(),
            )
        } catch (e: PdfOpenException) {
            when (e.failure) {
                OpenFailure.PASSWORD_PROTECTED -> ViewerUiState.PasswordRequired(wrongPassword = password != null)
                else -> ViewerUiState.Error(e.failure, inRecents = recents.contains(uriString))
            }
        }
    }

    // --- Fill and sign (plan V-c, spec §6.5) ---

    /**
     * Reads the form (fields, XFA kind, page boxes) once: when "Fill and sign" is first armed, or when
     * restored edits hold field values that must be drawn. A form whose page count differs from the
     * rendered document counts as unreadable.
     */
    fun loadForm() {
        val ready = _uiState.value as? ViewerUiState.Ready ?: return
        if (_form.value is FormLoad.Ready || formJob?.isActive == true) return
        _form.value = FormLoad.Loading
        formJob = viewModelScope.launch {
            val read = formReader.read({ opener.openStream(uri) }, withFields = true)
            // The font's character table, read here rather than on the first keystroke.
            withContext(Dispatchers.IO) { fontCoverage.sanitize("") }
            _form.value = if (read != null && read.pageBoxes.size == ready.pageSizes.size) FormLoad.Ready(read) else FormLoad.Failed
        }
    }

    /** The text as the PDF will hold it: characters the font can't draw are dropped. */
    fun sanitizeText(text: String): String = fontCoverage.sanitize(text)

    /**
     * Copies and measures a signature to place on a page: the copy lives in `cacheDir/images/`, so
     * deleting the signature never breaks an unsaved session.
     */
    suspend fun importOverlayImage(uri: Uri): PickedImage? {
        val copy = imageImporter.import(uri) ?: return null
        val dimensions = withContext(Dispatchers.IO) { imageLoader.probe(copy) } ?: return null
        return PickedImage(copy, dimensions)
    }

    /** An image overlay's file decoded for the screen (cached by the loader); null if it can't be read. */
    suspend fun overlayImage(uri: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) { imageLoader.thumbnail(uri, OVERLAY_IMAGE_SIDE_PX) }

    fun setFlattenFormChoice(flatten: Boolean) {
        _flattenFormChoice.value = flatten
    }

    // --- Saving from the viewer (plan V-a) ---

    fun setOverwriteChoice(overwrite: Boolean) {
        _overwriteChoice.value = overwrite
        viewModelScope.launch { savePreferences.setOverwrite(overwrite) }
    }

    fun setFlattenInkChoice(flatten: Boolean) {
        _flattenInkChoice.value = flatten
    }

    /** Suggested name of a copy: `<name>_modificato.pdf` (spec §6.7). */
    fun suggestedCopyName(suffix: String): String {
        val name = (_uiState.value as? ViewerUiState.Ready)?.displayName ?: "document.pdf"
        return name.removeSuffix(".pdf").removeSuffix(".PDF") + suffix + ".pdf"
    }

    /** Writes the viewer's edits into [destination] in the background. [overwrite]: [destination] is the original. */
    fun save(destination: Uri, overwrite: Boolean) {
        val ready = _uiState.value as? ViewerUiState.Ready ?: return
        if (saveRunner.isRunning || ready.editAvailability.value != EditAvailability.READY) return
        if (!overwrite) context.takeWritePermission(destination)
        val editing = ready.editing
        val request = editing.saveRequest(uriString, destination.toString(), flattenForm = _flattenFormChoice.value, flattenInk = _flattenInkChoice.value)
        val key = editing.currentKey()
        saveRunner.start(request, overwrite) { editing.markSaved(key) }
    }

    fun dismissSaveResult() = saveRunner.dismiss()

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
            it.textReader.close()
            it.bitmaps.clear()
            it.thumbnails.clear()
        }
    }

    private companion object {
        const val PAGE_SAVE_DELAY_MS = 500L
        const val THUMBNAIL_DELAY_MS = 1_000L
        const val ANNOTATIONS_DELAY_MS = 300L
        const val RECENT_THUMBNAIL_HEIGHT_PX = 360

        /** Longest side of a signature as decoded for the screen. */
        const val OVERLAY_IMAGE_SIDE_PX = 1600
    }
}
