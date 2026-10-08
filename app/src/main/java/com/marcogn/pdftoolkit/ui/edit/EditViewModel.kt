package com.marcogn.pdftoolkit.ui.edit

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.data.images.ImageImporter
import com.marcogn.pdftoolkit.data.save.ExtraSource
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
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormDocument
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.PageBox
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.pdf.edit.FontCoverage
import com.marcogn.pdftoolkit.pdf.edit.PageImageLoader
import com.marcogn.pdftoolkit.pdf.forms.FormReader
import com.marcogn.pdftoolkit.pdf.render.PageKey
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentRenderer
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import kotlin.math.sqrt

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

/**
 * What "Fill and sign" knows about the documents of the session (spec §6.5): the user-space box of
 * every page, to place overlays, and the form of the main document.
 */
class FillDocuments(val boxes: Map<DocRef, List<PageBox>>, val form: FormDocument?) {

    /** Main-document fields by page index of the main document. */
    private val fieldsByPage: Map<Int, List<FormField>> =
        form?.fields.orEmpty().flatMap { field -> field.widgets.map { it.pageIndex to field } }.groupBy({ it.first }, { it.second })
            .mapValues { (_, fields) -> fields.distinct() }

    /** How [item] is shown in the session, its added rotation included; null if its document isn't read yet. */
    fun space(item: PageItem): PdfPageSpace? = sessionPageSpace(item) { ref, index -> boxes[ref]?.getOrNull(index) }

    /** The page as its source shows it, without the rotation the user added. */
    fun sourceSpace(item: PageItem): PdfPageSpace? = space(item)?.withAddedRotation(-item.rotation)

    /** Form fields with a widget on [item] (only pages of the main document have them). */
    fun fieldsOn(item: PageItem): List<FormField> =
        if (item is PageItem.FromPdf && item.docRef == DocRef.MAIN) fieldsByPage[item.pageIndex].orEmpty() else emptyList()
}

/**
 * How [item] is shown in the session, its added rotation included: from the user-space box of its
 * page in the source document ([boxOf]: document, page index), or from its own size for blank and
 * image pages. Null if the box isn't known yet.
 */
private fun sessionPageSpace(item: PageItem, boxOf: (DocRef, Int) -> PageBox?): PdfPageSpace? = when (item) {
    is PageItem.FromPdf -> boxOf(item.docRef, item.pageIndex)?.toPageSpace()?.withAddedRotation(item.rotation)
    is PageItem.Blank -> PdfPageSpace.ofSize(item.widthPt, item.heightPt).withAddedRotation(item.rotation)
    is PageItem.FromImage -> PdfPageSpace.ofSize(item.widthPt, item.heightPt).withAddedRotation(item.rotation)
}

/** Loading the documents for "Fill and sign". */
sealed interface FillLoad {
    data object Loading : FillLoad
    data class Ready(val documents: FillDocuments) : FillLoad
    data object Failed : FillLoad
}

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
    private val formReader: FormReader,
    private val fontCoverage: FontCoverage,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Destination.Edit>()
    val sourceUri: String = route.uri

    /** A merge (spec §6.6): the result is always a new file, named `<first>_unito.pdf`. */
    val isMerge: Boolean = route.mergeWith.isNotEmpty()

    /** Merge without editing: the screen asks where to save as soon as the session is ready. */
    val autoSave: Boolean = route.autoSave

    private val _uiState = MutableStateFlow<EditUiState>(EditUiState.Loading)
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private val saveRunner = SaveRunner(viewModelScope, scheduler)
    val saveState: StateFlow<SaveUiState> = saveRunner.state

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

    private val _fillLoad = MutableStateFlow<FillLoad?>(null)
    /** Null until "Fill and sign" is opened. */
    val fillLoad: StateFlow<FillLoad?> = _fillLoad.asStateFlow()

    private val _flattenChoice = MutableStateFlow<Boolean?>(null)
    /** "Make final" as chosen in the save dialog; null = the default of spec §6.5 (on with a signature). */
    val flattenChoice: StateFlow<Boolean?> = _flattenChoice.asStateFlow()

    private var renderer: PdfDocumentRenderer? = null
    private val renderersByRef = mutableMapOf<DocRef, PdfDocumentRenderer>()
    /** Documents the session draws pages from, main first. Doc `n` of [extraSources] is `DocRef(n + 1)`. */
    private val sources = linkedMapOf<DocRef, PageSource>()
    private val extraSources = mutableListOf<ExtraSource>()
    private val renderers = mutableListOf<PdfDocumentRenderer>()
    private var pendingRenderer: PdfDocumentRenderer? = null
    private var displayName = ""
    private var sourcePageCount = 0
    private var savedEncoded: String? = null
    private var fillJob: Job? = null

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
            renderersByRef[DocRef.MAIN] = opened.renderer
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
            _canOverwrite.value = !isMerge && context.hasWriteAccess(sourceUri.toUri())
            val counts = sources.mapValues { it.value.pageCount }
            val restored = savedStateHandle.get<String>(KEY_PAGES)?.let {
                EditSession.decode(it, counts, savedStateHandle.get<String>(KEY_FILL).orEmpty(), savedStateHandle.get<String>(KEY_ANNOTATIONS).orEmpty())
            }
            publish(restored ?: EditSession.ofDocuments(sources.values.map { it.pageCount }))
        } catch (e: PdfOpenException) {
            _uiState.value = EditUiState.Error(e.failure)
        }
    }

    private fun pageSourceOf(name: String, renderer: PdfDocumentRenderer) =
        PageSource(name, renderer.pageSizes, PageThumbnails(renderer))

    private fun registerExtra(ref: DocRef, uri: String, source: PageSource, renderer: PdfDocumentRenderer) {
        renderers += renderer
        renderersByRef[ref] = renderer
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
        savedStateHandle[KEY_FILL] = session.encodeFill()
        savedStateHandle[KEY_ANNOTATIONS] = session.encodeAnnotations()
        _uiState.value = EditUiState.Ready(
            displayName = displayName,
            sources = sources.toMap(),
            session = session,
            hasUnsavedChanges = session.isModified && savedKey(session) != savedEncoded,
        )
    }

    /** What was saved last, to tell whether there is anything new to save. */
    private fun savedKey(session: EditSession) = session.encode() + "\n" + session.encodeFill() + "\n" + session.encodeAnnotations()

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

    // --- Fill and sign (spec §6.5) ---

    /**
     * Reads the page boxes of every document of the session and the form of the main one, if not
     * read yet. Called whenever "Fill and sign" opens: PDFs added since then are read too.
     */
    fun loadFill() {
        val current = (_fillLoad.value as? FillLoad.Ready)?.documents
        val missing = sources.keys.filter { current?.boxes?.containsKey(it) != true }
        if (missing.isEmpty() || fillJob?.isActive == true) return
        if (current == null) _fillLoad.value = FillLoad.Loading
        fillJob = viewModelScope.launch {
            val boxes = current?.boxes.orEmpty().toMutableMap()
            var form = current?.form
            for (ref in missing) {
                val uri = if (ref == DocRef.MAIN) sourceUri else extraSources.firstOrNull { it.docId == ref.id }?.uri ?: continue
                val read = formReader.read({ context.contentResolver.openInputStream(uri.toUri()) }, withFields = ref == DocRef.MAIN)
                if (read == null) {
                    if (ref == DocRef.MAIN) {
                        _fillLoad.value = FillLoad.Failed
                        return@launch
                    }
                    continue
                }
                boxes[ref] = read.pageBoxes
                if (ref == DocRef.MAIN) form = read
            }
            // The font's character table, read here rather than on the first keystroke.
            withContext(Dispatchers.IO) { fontCoverage.sanitize("") }
            _fillLoad.value = FillLoad.Ready(FillDocuments(boxes, form))
        }
    }

    fun newOverlayId(): String = "o" + UUID.randomUUID().toString().take(ID_LENGTH)

    fun addOverlay(overlay: Overlay) = apply { it.addOverlay(overlay) }

    fun updateOverlay(overlay: Overlay) = apply { it.updateOverlay(overlay) }

    fun removeOverlay(id: String) = apply { it.removeOverlay(id) }

    /** A value equal to the one in the file clears the change. [typing]: one undo step per field while typing. */
    fun setField(field: FormField, value: FieldValue, typing: Boolean = false) =
        apply { it.setField(field.name, value.takeIf { v -> v != field.value }, typing) }

    /** The text as the PDF will hold it: characters the font can't draw are dropped. */
    fun sanitizeText(text: String): String = fontCoverage.sanitize(text)

    fun setFlattenChoice(flatten: Boolean) {
        _flattenChoice.value = flatten
    }

    /** "Make final" for this save: the user's choice, or on when the document carries a signature (spec §6.5). */
    fun flattenForm(): Boolean {
        val session = (_uiState.value as? EditUiState.Ready)?.session ?: return false
        return _flattenChoice.value ?: session.fill.hasSignature
    }

    /** Copies and measures an image picked to place on a page (a signature, until phase 4b's archive). */
    suspend fun importOverlayImage(uri: Uri): PickedImage? {
        val copy = imageImporter.import(uri) ?: return null
        val dimensions = withContext(Dispatchers.IO) { imageLoader.probe(copy) } ?: return null
        return PickedImage(copy, dimensions)
    }

    /**
     * Renders [item] as its source shows it, at [pxPerPoint], for "Fill and sign"; at most
     * [maxPixels] pixels, so a deep zoom gets a softer page rather than an allocation failure.
     */
    suspend fun renderPage(item: PageItem.FromPdf, pxPerPoint: Float, maxPixels: Int): android.graphics.Bitmap? {
        val renderer = renderersByRef[item.docRef] ?: return null
        val size = sources[item.docRef]?.pageSizes?.getOrNull(item.pageIndex) ?: return null
        val area = size.width * size.height
        val scale = minOf(pxPerPoint, sqrt(maxPixels / area))
        val width = (size.width * scale).toInt().coerceAtLeast(1)
        val height = (size.height * scale).toInt().coerceAtLeast(1)
        return renderer.render(PageKey(item.pageIndex, width, height, scale))
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
        if (saveRunner.isRunning) return
        if (!overwrite) context.takeWritePermission(destination)
        val request = SaveRequest(
            sourceUri = sourceUri,
            destinationUri = destination.toString(),
            sourcePageCount = sourcePageCount,
            pages = ready.session.encode(),
            extraSources = extraSources.toList(),
            fill = ready.session.encodeFill(),
            flattenForm = flattenForm(),
            annotations = ready.session.encodeAnnotations(),
        )
        val savedPages = savedKey(ready.session)
        saveRunner.start(request, overwrite) {
            savedEncoded = savedPages
            (_uiState.value as? EditUiState.Ready)?.let { publish(it.session) }
        }
    }

    fun dismissSaveResult() = saveRunner.dismiss()

    override fun onCleared() {
        renderer?.close()
        renderers.forEach { it.close() }
        pendingRenderer?.close()
        sources.values.forEach { it.thumbnails.clear() }
    }

    private companion object {
        const val KEY_PAGES = "pages"
        const val KEY_EXTRAS = "extras"
        const val KEY_FILL = "fill"
        const val KEY_ANNOTATIONS = "annotations"
        const val ID_LENGTH = 8
        const val QUARTER = 90
    }
}
