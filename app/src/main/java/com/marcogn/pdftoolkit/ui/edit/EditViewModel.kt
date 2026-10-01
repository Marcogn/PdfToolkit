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
import com.marcogn.pdftoolkit.data.images.ImageImporter
import com.marcogn.pdftoolkit.data.save.ExtraSource
import com.marcogn.pdftoolkit.data.save.SaveProgress
import com.marcogn.pdftoolkit.data.save.SaveRequest
import com.marcogn.pdftoolkit.data.save.SaveScheduler
import com.marcogn.pdftoolkit.data.settings.SavePreferences
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.EditSession
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.edit.ImageFit
import com.marcogn.pdftoolkit.domain.edit.InsertionPoint
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.edit.PageSizing
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import com.marcogn.pdftoolkit.domain.edit.SizePt
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.pdf.edit.PageImageLoader
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

sealed interface EditUiState {
    data object Loading : EditUiState

    data class Error(val failure: OpenFailure) : EditUiState

    /** [session] is what the grids show; [sources] has the thumbnails and page sizes of every PDF the pages come from. */
    data class Ready(
        val displayName: String,
        val sources: Map<DocRef, PageSource>,
        val session: EditSession,
        val hasUnsavedChanges: Boolean,
    ) : EditUiState
}

/** A PDF the session takes pages from: its name, the size of each of its pages and a thumbnail cache. */
class PageSource(val name: String, val pageSizes: List<PageSize>, val thumbnails: PageThumbnails, val pageCount: Int = pageSizes.size)

/** A PDF picked to add pages from, opened but not yet part of the session. */
class PendingPdf(val docRef: DocRef, val uri: String, val source: PageSource)

/** An image the user picked, already copied into the app and measured. */
data class PickedImage(val uri: String, val dimensions: ImageDimensions)

/** One-off things the screen tells the user about. */
sealed interface EditEvent {
    data object PdfProtected : EditEvent
    data object PdfUnreadable : EditEvent
    data class ImagesSkipped(val count: Int) : EditEvent
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
    private val imageImporter: ImageImporter,
    private val imageLoader: PageImageLoader,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Destination.Edit>()
    val sourceUri: String = route.uri

    /** A merge (spec §6.6): the result is always a new file, named `<first>_unito.pdf`. */
    val isMerge: Boolean = route.mergeWith.isNotEmpty()

    /** Merge without editing: the screen asks where to save as soon as the session is ready. */
    val autoSave: Boolean = route.autoSave

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

    private val _pendingPdf = MutableStateFlow<PendingPdf?>(null)
    /** A PDF opened to pick pages from, until the pages are added or the pick is dropped. */
    val pendingPdf: StateFlow<PendingPdf?> = _pendingPdf.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<PickedImage>>(emptyList())
    /** Images picked and waiting for the fit mode and the insertion point. */
    val pendingImages: StateFlow<List<PickedImage>> = _pendingImages.asStateFlow()

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = _events.receiveAsFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a picked PDF or the picked images are being read. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var renderer: PdfDocumentRenderer? = null
    /** Documents the session draws pages from, main first. Doc `n` of [extraSources] is `DocRef(n + 1)`. */
    private val sources = linkedMapOf<DocRef, PageSource>()
    private val extraSources = mutableListOf<ExtraSource>()
    private val renderers = mutableListOf<PdfDocumentRenderer>()
    private var pendingRenderer: PdfDocumentRenderer? = null
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
            sources[DocRef.MAIN] = pageSourceOf(displayName, opened.renderer)
            // Other PDFs are reopened from their URIs: the merge ones from the route, the ones added
            // later from the saved state (their ids are their position, so a restored page list matches).
            val uris = savedStateHandle.get<ArrayList<String>>(KEY_EXTRAS) ?: ArrayList(route.mergeWith)
            for ((index, uri) in uris.withIndex()) {
                val extra = opener.open(uri.toUri())
                registerExtra(DocRef(index + 1), uri, pageSourceOf(extra.displayName ?: uri, extra.renderer), extra.renderer)
            }
            _canOverwrite.value = !isMerge && hasWriteAccess(sourceUri.toUri())
            val counts = sources.mapValues { it.value.pageCount }
            val restored = savedStateHandle.get<String>(KEY_PAGES)?.let { EditSession.decode(it, counts) }
            publish(restored ?: EditSession.ofDocuments(sources.values.map { it.pageCount }))
        } catch (e: PdfOpenException) {
            _uiState.value = EditUiState.Error(e.failure)
        }
    }

    private fun pageSourceOf(name: String, renderer: PdfDocumentRenderer) =
        PageSource(name, renderer.pageSizes, PageThumbnails(renderer))

    private fun registerExtra(ref: DocRef, uri: String, source: PageSource, renderer: PdfDocumentRenderer) {
        renderers += renderer
        sources[ref] = source
        extraSources += ExtraSource(ref.id, uri, source.pageCount)
        savedStateHandle[KEY_EXTRAS] = ArrayList(extraSources.map { it.uri })
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
        publish(updated)
        return true
    }

    private fun publish(session: EditSession) {
        savedStateHandle[KEY_PAGES] = session.encode()
        _uiState.value = EditUiState.Ready(
            displayName = displayName,
            sources = sources.toMap(),
            session = session,
            hasUnsavedChanges = session.isModified && session.encode() != savedEncoded,
        )
    }

    // --- Adding pages (spec §6.2) ---

    /** The size the new pages of [point] take by default: the page before them, or the first page (spec §6.2). */
    fun referenceSize(point: InsertionPoint): SizePt {
        val pages = (_uiState.value as? EditUiState.Ready)?.session?.pages.orEmpty()
        return PageSizing.referenceSize(pages, point.toIndex(pages.size), ::visibleSize)
    }

    /** Whether the open document has pages of different sizes, so the dialog says which one is used. */
    fun hasMixedSizes(): Boolean {
        val pages = (_uiState.value as? EditUiState.Ready)?.session?.pages.orEmpty()
        return PageSizing.hasMixedSizes(pages.mapNotNull(::visibleSize))
    }

    private fun visibleSize(item: PageItem): SizePt? = when (item) {
        is PageItem.FromPdf -> sources[item.docRef]?.pageSizes?.getOrNull(item.pageIndex)?.let { SizePt(it.width, it.height) }?.rotated(item.rotation)
        is PageItem.Blank -> SizePt(item.widthPt, item.heightPt).rotated(item.rotation)
        is PageItem.FromImage -> SizePt(item.widthPt, item.heightPt).rotated(item.rotation)
    }

    private fun newId(): String = "n" + UUID.randomUUID().toString().take(ID_LENGTH)

    private fun insertionIndex(point: InsertionPoint): Int = point.toIndex((_uiState.value as? EditUiState.Ready)?.session?.pageCount ?: 0)

    /** Opens [uri] to pick pages from. A protected or unreadable file is reported through [events]. */
    fun openPdfToAdd(uri: Uri) {
        if (_busy.value || _pendingPdf.value != null) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val opened = opener.open(uri)
                val name = opened.displayName ?: uri.toString()
                _pendingPdf.value = PendingPdf(DocRef(extraSources.size + 1), uri.toString(), pageSourceOf(name, opened.renderer))
                pendingRenderer = opened.renderer
            } catch (e: PdfOpenException) {
                _events.trySend(if (e.failure == OpenFailure.NOT_FOUND || e.failure == OpenFailure.UNREADABLE) EditEvent.PdfUnreadable else EditEvent.PdfProtected)
            } finally {
                _busy.value = false
            }
        }
    }

    /** The pick is over without adding anything: the document is closed. */
    fun dropPendingPdf() {
        _pendingPdf.value?.source?.thumbnails?.clear()
        pendingRenderer?.close()
        pendingRenderer = null
        _pendingPdf.value = null
    }

    /** Adds the pages [pageIndices] (in the order given) of the pending PDF at [point]. Returns the ids of the new pages. */
    fun insertPendingPdfPages(pageIndices: List<Int>, point: InsertionPoint): List<String> {
        val pending = _pendingPdf.value ?: return emptyList()
        val renderer = pendingRenderer ?: return emptyList()
        if (pageIndices.isEmpty()) return emptyList()
        val index = insertionIndex(point)
        registerExtra(pending.docRef, pending.uri, pending.source, renderer)
        // Registered: the renderer now belongs to the session, not to the pending pick.
        pendingRenderer = null
        _pendingPdf.value = null
        val items = pageIndices.map { PageItem.FromPdf(newId(), pending.docRef, it) }
        apply { it.insert(index, items) }
        return items.map { it.id }
    }

    /** Adds [count] blank pages (spec §6.2: 1 to 50) at [point], sized like the neighbouring page. */
    fun insertBlankPages(count: Int, point: InsertionPoint): List<String> {
        val amount = count.coerceIn(PageSizing.MIN_BLANK_PAGES, PageSizing.MAX_BLANK_PAGES)
        val size = referenceSize(point)
        val items = List(amount) { PageItem.Blank(newId(), size.width, size.height) }
        val index = insertionIndex(point)
        return if (apply { it.insert(index, items) }) items.map { it.id } else emptyList()
    }

    /** Copies and measures the picked images; those that can't be read are reported through [events]. */
    fun pickImages(uris: List<Uri>) {
        if (_busy.value || uris.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val picked = mutableListOf<PickedImage>()
                var skipped = 0
                for (uri in uris) {
                    val copy = imageImporter.import(uri)
                    val dimensions = copy?.let { withContext(Dispatchers.IO) { imageLoader.probe(it) } }
                    if (copy != null && dimensions != null) picked += PickedImage(copy, dimensions) else skipped++
                }
                if (skipped > 0) _events.trySend(EditEvent.ImagesSkipped(skipped))
                _pendingImages.value = picked
            } finally {
                _busy.value = false
            }
        }
    }

    fun dropPendingImages() {
        _pendingImages.value = emptyList()
    }

    /** Turns the picked images into pages at [point]; the page size follows [mode] (spec §6.2). Returns the ids of the new pages. */
    fun insertPendingImages(mode: ImageFit, point: InsertionPoint): List<String> {
        val images = _pendingImages.value
        if (images.isEmpty()) return emptyList()
        val reference = referenceSize(point)
        val items = images.map { image ->
            val size = PageSizing.pageSizeFor(image.dimensions, mode, reference)
            PageItem.FromImage(newId(), image.uri, mode, size.width, size.height)
        }
        val index = insertionIndex(point)
        _pendingImages.value = emptyList()
        return if (apply { it.insert(index, items) }) items.map { it.id } else emptyList()
    }

    fun imageThumbnail(uri: String, maxSidePx: Int) = imageLoader.thumbnail(uri, maxSidePx)

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
            extraSources = extraSources.toList(),
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
                        (_uiState.value as? EditUiState.Ready)?.let { publish(it.session) }
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
        renderers.forEach { it.close() }
        pendingRenderer?.close()
        sources.values.forEach { it.thumbnails.clear() }
    }

    private companion object {
        const val KEY_PAGES = "pages"
        const val KEY_EXTRAS = "extras"
        const val ID_LENGTH = 8
        const val QUARTER = 90
    }
}
