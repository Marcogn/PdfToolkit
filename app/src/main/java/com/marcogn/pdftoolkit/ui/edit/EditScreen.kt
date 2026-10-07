package com.marcogn.pdftoolkit.ui.edit

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.ui.navigation.editContainerBounds
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfTool
import com.marcogn.pdftoolkit.ui.home.icon
import com.marcogn.pdftoolkit.ui.home.isSignatureAction
import com.marcogn.pdftoolkit.ui.home.labelRes
import com.marcogn.pdftoolkit.ui.fill.FILL_IMAGE_SIDE_PX
import com.marcogn.pdftoolkit.ui.fill.FillActions
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.ui.annotate.AnnotateActions
import com.marcogn.pdftoolkit.ui.annotate.AnnotatePane
import com.marcogn.pdftoolkit.ui.annotate.AnnotateToolBar
import com.marcogn.pdftoolkit.ui.annotate.applySelection
import com.marcogn.pdftoolkit.ui.annotate.rememberAnnotatePaneState
import com.marcogn.pdftoolkit.ui.annotate.rememberTextSelectionState
import com.marcogn.pdftoolkit.ui.fill.FillPane
import com.marcogn.pdftoolkit.ui.fill.FillTool
import com.marcogn.pdftoolkit.ui.fill.FillToolBar
import com.marcogn.pdftoolkit.ui.fill.MAX_PAGE_PIXELS
import com.marcogn.pdftoolkit.ui.fill.rememberFillPaneState
import com.marcogn.pdftoolkit.ui.signatures.SignatureCreationHost
import com.marcogn.pdftoolkit.ui.signatures.SignaturePickerSheet
import com.marcogn.pdftoolkit.ui.signatures.SignaturesViewModel
import com.marcogn.pdftoolkit.ui.signatures.rememberSignatureCreationState
import com.marcogn.pdftoolkit.data.signatures.Signature
import com.marcogn.pdftoolkit.ui.viewer.takePersistableAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditPane { HUB, REMOVE, REORDER, FILL, ANNOTATE }

private fun PdfTool?.initialPane(): EditPane = when (this) {
    PdfTool.REMOVE_PAGES -> EditPane.REMOVE
    PdfTool.REORDER_PAGES -> EditPane.REORDER
    PdfTool.FILL_AND_SIGN -> EditPane.FILL
    PdfTool.HIGHLIGHT -> EditPane.ANNOTATE
    else -> EditPane.HUB
}

private const val PDF_MIME = "application/pdf"
private const val IMAGE_MIME = "image/*"
private const val THUMBNAIL_PX = 320

/** Ids of the cells of the page picker; they live only in this screen, not in the edit session. */
private const val PICK_ID_PREFIX = "pick"

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * Edit hub and page tools on one edit session (spec §4.3, §6). Hub, "remove" and "reorder" are
 * panes of this one screen so they share the session, the renderer and the save state; the
 * system back goes pane → hub → leave (asking about unsaved changes).
 *
 * @param startTool the tool tapped on Home, which opens straight on its pane or dialog (spec §4.1); null from the viewer.
 * @param onBack leaves the edit.
 * @param onResultReady an overwrite finished: the original has new content, so the caller must
 * drop any screen still showing the old one and open [uri].
 * @param onOpenCopy opens the saved copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    startTool: PdfTool?,
    onBack: () -> Unit,
    onResultReady: (uri: String) -> Unit,
    onOpenCopy: (uri: String) -> Unit,
    viewModel: EditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val overwriteChoice by viewModel.overwriteChoice.collectAsStateWithLifecycle()
    val canOverwrite by viewModel.canOverwrite.collectAsStateWithLifecycle()
    val pendingPdf by viewModel.pendingPdf.collectAsStateWithLifecycle()
    val pendingImages by viewModel.pendingImages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val fillLoad by viewModel.fillLoad.collectAsStateWithLifecycle()
    val flattenChoice by viewModel.flattenChoice.collectAsStateWithLifecycle()
    val flattenInkChoice by viewModel.flattenInkChoice.collectAsStateWithLifecycle()
    val fillState = rememberFillPaneState()
    val annotateLoad by viewModel.annotateLoad.collectAsStateWithLifecycle()
    val annotateState = rememberAnnotatePaneState()
    val annotateSelection = rememberTextSelectionState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current

    val openedOnTool = startTool.initialPane() != EditPane.HUB
    var pane by rememberSaveable { mutableStateOf(startTool.initialPane()) }
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var rangeAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showOverwriteConfirm by rememberSaveable { mutableStateOf(false) }
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    var showAddSource by rememberSaveable { mutableStateOf(false) }
    var showBlankDialog by rememberSaveable { mutableStateOf(false) }
    var showImageSource by rememberSaveable { mutableStateOf(false) }
    var showPickedPagesDialog by rememberSaveable { mutableStateOf(false) }
    var startToolHandled by rememberSaveable { mutableStateOf(false) }
    // Pages just added: shown highlighted, in the page grid, scrolled into view.
    var highlighted by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var scrollToId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoSaveHandled by rememberSaveable { mutableStateOf(false) }

    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri, overwrite = false)
    }
    val copySuffix = resources.getString(if (viewModel.isMerge) R.string.save_merge_suffix else R.string.save_copy_suffix)
    val startSave = {
        showSaveDialog = false
        if (overwriteChoice && canOverwrite) {
            showOverwriteConfirm = true
        } else {
            copyLauncher.launch(viewModel.suggestedCopyName(copySuffix))
        }
    }

    // The pickers of phase 3. Read access is kept when the provider allows it, so a save
    // resumed by the system can still read the added PDF.
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.takePersistableAccess(uri)
            viewModel.openPdfToAdd(uri)
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.pickImages(uris)
    }
    val imageFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.pickImages(uris)
    }
    // Phase 4b: the signature comes from the archive ("My signatures"), or is created on the spot.
    val signaturesViewModel: SignaturesViewModel = hiltViewModel()
    val signatureCreation = rememberSignatureCreationState()
    var showSignatureSheet by rememberSaveable { mutableStateOf(false) }
    val placeSignature: (Signature) -> Unit = { signature ->
        scope.launch {
            val image = viewModel.importOverlayImage(signaturesViewModel.file(signature).toUri())
            if (image == null) {
                snackbarHostState.showSnackbar(resources.getString(R.string.fill_image_unreadable))
            } else {
                fillState.image = image
                fillState.tool = FillTool.SIGNATURE
                fillState.selected = null
            }
        }
    }
    SignatureCreationHost(signatureCreation, signaturesViewModel, onCreated = placeSignature)
    if (showSignatureSheet) {
        SignaturePickerSheet(
            viewModel = signaturesViewModel,
            onPick = { showSignatureSheet = false; placeSignature(it) },
            onNew = { showSignatureSheet = false; signatureCreation.start() },
            onDismiss = { showSignatureSheet = false },
        )
    }

    LaunchedEffect(saveState) {
        (saveState as? SaveUiState.Overwritten)?.let { onResultReady(it.uri) }
        // "N pages added · Undo" no longer applies once the document is saved, and it would stack
        // on top of the "saved" snackbar.
        if (saveState is SaveUiState.Saved) snackbarHostState.currentSnackbarData?.dismiss()
    }

    val ready = uiState as? EditUiState.Ready
    val saving = saveState is SaveUiState.Saving
    val picking = pendingPdf != null
    // The "new" marks help find what was just added; picking pages to remove is a different
    // question, and once saved there is nothing new any more.
    LaunchedEffect(pane, saveState is SaveUiState.Saved) {
        if (pane == EditPane.REMOVE || saveState is SaveUiState.Saved) {
            highlighted = emptySet()
            scrollToId = null
        }
    }
    // Tools of Home that are a dialog rather than a pane open it as soon as the document is ready;
    // a merge without editing asks where to save straight away.
    LaunchedEffect(ready != null) {
        if (ready == null) return@LaunchedEffect
        if (!startToolHandled) {
            startToolHandled = true
            when (startTool) {
                PdfTool.ADD_PAGES -> showAddSource = true
                PdfTool.INSERT_IMAGES -> showImageSource = true
                else -> Unit
            }
        }
        if (viewModel.autoSave && !autoSaveHandled) {
            autoSaveHandled = true
            copyLauncher.launch(viewModel.suggestedCopyName(copySuffix))
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    when (event) {
                        EditEvent.PdfProtected -> resources.getString(R.string.add_pdf_protected)
                        EditEvent.PdfUnreadable -> resources.getString(R.string.add_pdf_unreadable)
                        is EditEvent.ImagesSkipped -> resources.getQuantityString(R.plurals.images_skipped, event.count, event.count)
                    },
                )
            }
        }
    }
    // Selection can only hold pages that still exist (undo can bring pages back, remove takes them).
    LaunchedEffect(ready?.session?.pages) {
        if (picking) return@LaunchedEffect
        val ids = ready?.session?.pages?.map { it.id }?.toSet() ?: return@LaunchedEffect
        if (!ids.containsAll(selection)) selection = selection intersect ids
        if (!ids.containsAll(highlighted)) highlighted = highlighted intersect ids
        if (rangeAnchor !in ids) rangeAnchor = null
    }

    val requestExit = {
        if (ready?.hasUnsavedChanges == true && !saving) showUnsaved = true else onBack()
    }
    val handleBack = {
        when {
            pane == EditPane.FILL && fillState.consumesBack -> fillState.back()
            pane == EditPane.ANNOTATE && annotateSelection.isActive -> annotateSelection.clear()
            picking -> {
                viewModel.dropPendingPdf()
                selection = emptySet()
                rangeAnchor = null
            }
            selection.isNotEmpty() -> {
                selection = emptySet()
                rangeAnchor = null
            }
            pane != EditPane.HUB && !openedOnTool -> pane = EditPane.HUB
            else -> requestExit()
        }
    }
    BackHandler(enabled = ready != null) { handleBack() }

    val showRemoved: (Int) -> Unit = { count ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val message = resources.getQuantityString(R.plurals.edit_pages_removed, count, count)
            val result = snackbarHostState.showSnackbar(message, actionLabel = resources.getString(R.string.edit_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }
    // After an addition the page grid shows the new pages marked and scrolls to them, so it is
    // clear which they are and where they went.
    val showAdded: (List<String>) -> Unit = showAdded@{ ids ->
        if (ids.isEmpty()) return@showAdded
        val count = ids.size
        highlighted = ids.toSet()
        scrollToId = ids.first()
        selection = emptySet()
        rangeAnchor = null
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val message = resources.getQuantityString(R.plurals.edit_pages_added, count, count)
            val result = snackbarHostState.showSnackbar(message, actionLabel = resources.getString(R.string.edit_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // No "Merge" in the hub: on an open document it is "Add pages → from another PDF" (author's decision).
    val hubTools = remember { PdfTool.available.filter { it.requiresDocument && it != PdfTool.MERGE } }
    val onHubTool: (PdfTool) -> Unit = { tool ->
        when (tool) {
            PdfTool.REMOVE_PAGES -> pane = EditPane.REMOVE
            PdfTool.REORDER_PAGES -> pane = EditPane.REORDER
            PdfTool.ADD_PAGES -> showAddSource = true
            PdfTool.INSERT_IMAGES -> showImageSource = true
            PdfTool.FILL_AND_SIGN -> pane = EditPane.FILL
            PdfTool.HIGHLIGHT -> pane = EditPane.ANNOTATE
            else -> showMessage(resources.getString(R.string.edit_tool_unavailable, resources.getString(tool.labelRes())))
        }
    }

    // "Fill and sign" reads the documents' page boxes and form when it opens (and again for PDFs added since).
    LaunchedEffect(pane, ready?.sources?.size) {
        if (pane == EditPane.FILL && ready != null) viewModel.loadFill()
    }
    // "Annotate" reads the annotations of the documents when it opens (and again for PDFs added since).
    LaunchedEffect(pane, ready?.sources?.size) {
        if (pane == EditPane.ANNOTATE && ready != null) viewModel.loadAnnotate()
    }
    LaunchedEffect(pane) {
        if (pane != EditPane.ANNOTATE) annotateSelection.clear()
    }
    val currentShowMessage by rememberUpdatedState(showMessage)
    val annotateActions = remember(viewModel) {
        object : AnnotateActions {
            override suspend fun renderPage(item: PageItem.FromPdf, pxPerPoint: Float) = viewModel.renderPage(item, pxPerPoint, MAX_PAGE_PIXELS)
            override suspend fun image(uri: String) = withContext(Dispatchers.IO) { viewModel.imageThumbnail(uri, FILL_IMAGE_SIDE_PX) }
            override fun addAnnotation(annotation: NewAnnotation) { viewModel.addAnnotation(annotation) }
            override fun removeAnnotation(id: String) { viewModel.removeAnnotation(id) }
            override fun removeExistingAnnotation(ref: AnnotationRef) { viewModel.removeExistingAnnotation(ref) }
            override fun newAnnotationId() = viewModel.newAnnotationId()
            override suspend fun pageText(item: PageItem.FromPdf) = viewModel.pageText(item)
            override fun message(text: String) = currentShowMessage(text)
        }
    }
    val fillActions = remember(viewModel) {
        object : FillActions {
            override suspend fun renderPage(item: PageItem.FromPdf, pxPerPoint: Float) = viewModel.renderPage(item, pxPerPoint, MAX_PAGE_PIXELS)
            override suspend fun image(uri: String) = withContext(Dispatchers.IO) { viewModel.imageThumbnail(uri, FILL_IMAGE_SIDE_PX) }
            override fun addOverlay(overlay: Overlay) { viewModel.addOverlay(overlay) }
            override fun updateOverlay(overlay: Overlay) { viewModel.updateOverlay(overlay) }
            override fun removeOverlay(id: String) { viewModel.removeOverlay(id) }
            override fun setField(field: FormField, value: FieldValue, typing: Boolean) { viewModel.setField(field, value, typing) }
            override fun newOverlayId() = viewModel.newOverlayId()
            override fun sanitize(text: String) = viewModel.sanitizeText(text)
            override fun message(text: String) = currentShowMessage(text)
        }
    }

    Scaffold(
        // The viewer's Edit button grows into this screen (spec §9, container transform).
        modifier = Modifier.editContainerBounds(),
        // In the bottomBar slot, so snackbars are placed above the tools instead of covering them.
        bottomBar = {
            if (ready != null && !picking && pane == EditPane.HUB) HubToolBar(hubTools, onHubTool)
            if (ready != null && !picking && pane == EditPane.FILL) {
                FillToolBar(fillState, ready.session.fill.overlays, fillActions) { showSignatureSheet = true }
            }
            if (ready != null && !picking && pane == EditPane.ANNOTATE) {
                AnnotateToolBar(annotateState, annotateSelection) { kind ->
                    applySelection(kind, annotateState, annotateSelection, ready.session.pages, (annotateLoad as? AnnotateLoad.Ready)?.documents, annotateActions)
                }
            }
        },
        topBar = {
            Column {
                when {
                    ready == null -> TopAppBar(
                        title = {},
                        navigationIcon = { BackButton(onBack) },
                    )
                    picking -> TopAppBar(
                        title = {
                            Text(
                                if (selection.isEmpty()) {
                                    stringResource(R.string.add_pick_pages_title)
                                } else {
                                    pluralStringResource(R.plurals.edit_selected_count, selection.size, selection.size)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = handleBack) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.save_cancel))
                            }
                        },
                        actions = {
                            val pageCount = pendingPdf?.source?.pageCount ?: 0
                            IconButton(onClick = { selection = (0 until pageCount).map { "$PICK_ID_PREFIX$it" }.toSet() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.edit_select_all))
                            }
                            IconButton(onClick = { selection = emptySet(); rangeAnchor = null }, enabled = selection.isNotEmpty()) {
                                Icon(Icons.Filled.Deselect, contentDescription = stringResource(R.string.add_select_none))
                            }
                            IconButton(onClick = { showPickedPagesDialog = true }, enabled = selection.isNotEmpty()) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.add_confirm))
                            }
                        },
                    )
                    pane == EditPane.REMOVE && selection.isNotEmpty() -> TopAppBar(
                        title = { Text(pluralStringResource(R.plurals.edit_selected_count, selection.size, selection.size)) },
                        navigationIcon = {
                            IconButton(onClick = { selection = emptySet(); rangeAnchor = null }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.edit_clear_selection))
                            }
                        },
                        actions = {
                            IconButton(onClick = { selection = ready.session.pages.map { it.id }.toSet() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.edit_select_all))
                            }
                            IconButton(onClick = { viewModel.rotate(selection) }) {
                                Icon(Icons.Filled.RotateRight, contentDescription = stringResource(R.string.edit_rotate))
                            }
                            IconButton(
                                onClick = {
                                    val ids = selection
                                    if (viewModel.remove(ids)) {
                                        selection = emptySet()
                                        rangeAnchor = null
                                        showRemoved(ids.size)
                                    } else {
                                        showMessage(resources.getString(R.string.edit_cannot_remove_all))
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_remove))
                            }
                        },
                    )
                    pane == EditPane.HUB -> TopAppBar(
                        title = {
                            Column {
                                Text(ready.displayName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                Text(
                                    pluralStringResource(R.plurals.edit_hub_subtitle, ready.session.pageCount, ready.session.pageCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = { BackButton(handleBack) },
                        actions = {
                            if (ready.session.canUndo || ready.session.canRedo) {
                                IconButton(onClick = viewModel::undo, enabled = ready.session.canUndo) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.edit_undo))
                                }
                                IconButton(onClick = viewModel::redo, enabled = ready.session.canRedo) {
                                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.edit_redo))
                                }
                            }
                            if (ready.hasUnsavedChanges) SaveAction(enabled = !saving) { showSaveDialog = true }
                        },
                    )
                    else -> TopAppBar(
                        title = {
                            Text(
                                stringResource(
                                    when (pane) {
                                        EditPane.REMOVE -> R.string.tool_remove_pages
                                        EditPane.FILL -> R.string.tool_fill_and_sign
                                        EditPane.ANNOTATE -> R.string.tool_highlight
                                        else -> R.string.tool_reorder_pages
                                    },
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = { BackButton(handleBack) },
                        actions = {
                            if (pane == EditPane.REMOVE) {
                                IconButton(onClick = { selection = ready.session.pages.map { it.id }.toSet() }) {
                                    Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.edit_select_all))
                                }
                            }
                            IconButton(onClick = viewModel::undo, enabled = ready.session.canUndo) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.edit_undo))
                            }
                            IconButton(onClick = viewModel::redo, enabled = ready.session.canRedo) {
                                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.edit_redo))
                            }
                            SaveAction(enabled = ready.hasUnsavedChanges && !saving) { showSaveDialog = true }
                        },
                    )
                }
                // Reading a picked PDF or copying images can take a moment: show it without blocking the screen.
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                // Non-blocking progress (spec §6.7): the screen stays usable.
                if (saveState is SaveUiState.Saving) {
                    val fraction = (saveState as SaveUiState.Saving).fraction
                    if (fraction > 0f) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        snackbarHost = {
            Column {
                (saveState as? SaveUiState.Saved)?.let { saved ->
                    SavedSnackbar(
                        onOpen = {
                            viewModel.dismissSaveResult()
                            onOpenCopy(saved.uri)
                        },
                        onShare = { shareCopy(context, saved.uri, resources.getString(R.string.viewer_share_title)) },
                        onDismiss = viewModel::dismissSaveResult,
                    )
                }
                SnackbarHost(snackbarHostState)
            }
        },
    ) { padding ->
        when (val state = uiState) {
            EditUiState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is EditUiState.Error -> EditError(state.failure, onBack, Modifier.padding(padding))
            is EditUiState.Ready -> if (pendingPdf != null) {
                PickPdfPane(
                    pending = pendingPdf!!,
                    selection = selection,
                    onTap = { page ->
                        val anchor = rangeAnchor
                        if (anchor != null) {
                            val a = anchor.removePrefix(PICK_ID_PREFIX).toIntOrNull()
                            val b = page.id.removePrefix(PICK_ID_PREFIX).toIntOrNull()
                            if (a != null && b != null) selection = selection union (minOf(a, b)..maxOf(a, b)).map { "$PICK_ID_PREFIX$it" }
                            rangeAnchor = null
                        } else {
                            selection = if (page.id in selection) selection - page.id else selection + page.id
                        }
                    },
                    onLongPress = { page ->
                        selection = selection + page.id
                        rangeAnchor = page.id
                    },
                    padding = padding,
                )
            } else when (pane) {
                EditPane.HUB -> EditHub(
                    state = state,
                    imageThumbnail = { uri -> viewModel.imageThumbnail(uri, THUMBNAIL_PX) },
                    highlighted = highlighted,
                    scrollToId = scrollToId,
                    padding = padding,
                )
                EditPane.FILL -> FillPane(
                    pages = state.session.pages,
                    overlays = state.session.fill.overlays,
                    values = state.session.fill.fields,
                    load = fillLoad,
                    state = fillState,
                    actions = fillActions,
                    modifier = Modifier.padding(padding),
                )
                EditPane.ANNOTATE -> AnnotatePane(
                    pages = state.session.pages,
                    edits = state.session.annotations,
                    load = annotateLoad,
                    state = annotateState,
                    selection = annotateSelection,
                    actions = annotateActions,
                    modifier = Modifier.padding(padding),
                )
                EditPane.REMOVE, EditPane.REORDER -> PagesPane(
                    state = state,
                    imageThumbnail = { uri -> viewModel.imageThumbnail(uri, THUMBNAIL_PX) },
                    highlighted = highlighted,
                    scrollToId = scrollToId,
                    mode = if (pane == EditPane.REMOVE) PagesMode.REMOVE else PagesMode.REORDER,
                    selection = selection,
                    onTap = { page ->
                        val anchor = rangeAnchor
                        if (anchor != null) {
                            val pages = state.session.pages
                            val a = pages.indexOfFirst { it.id == anchor }
                            val b = pages.indexOfFirst { it.id == page.id }
                            if (a >= 0 && b >= 0) selection = selection union pages.subList(minOf(a, b), maxOf(a, b) + 1).map { it.id }
                            rangeAnchor = null
                        } else {
                            selection = if (page.id in selection) selection - page.id else selection + page.id
                        }
                    },
                    onLongPress = { page ->
                        selection = selection + page.id
                        rangeAnchor = page.id
                    },
                    onCommitMove = viewModel::move,
                    actions = PageActions(
                        onMoveToStart = viewModel::moveToStart,
                        onMoveToEnd = viewModel::moveToEnd,
                        onMoveEarlier = { id -> state.session.pages.indexOfFirst { it.id == id }.let { viewModel.move(it, it - 1) } },
                        onMoveLater = { id -> state.session.pages.indexOfFirst { it.id == id }.let { viewModel.move(it, it + 1) } },
                        onRotate = { id -> viewModel.rotate(setOf(id)) },
                    ),
                    padding = padding,
                )
            }
        }
    }

    if (showSaveDialog) {
        // "Make final" is offered once the form has been read, i.e. after "Fill and sign" was opened.
        val hasForm = (fillLoad as? FillLoad.Ready)?.documents?.form?.hasFields == true
        SaveDialog(
            overwrite = overwriteChoice && canOverwrite,
            canOverwrite = canOverwrite,
            onOverwriteChange = viewModel::setOverwriteChoice,
            onConfirm = startSave,
            onDismiss = { showSaveDialog = false },
            flatten = if (hasForm) flattenChoice ?: ready?.session?.fill?.hasSignature ?: false else null,
            onFlattenChange = viewModel::setFlattenChoice,
            flattenInk = if (ready?.session?.annotations?.hasInk == true) flattenInkChoice else null,
            onFlattenInkChange = viewModel::setFlattenInkChoice,
        )
    }
    if (showOverwriteConfirm) {
        OverwriteConfirmDialog(
            onConfirm = {
                showOverwriteConfirm = false
                viewModel.save(viewModel.sourceUri.toUri(), overwrite = true)
            },
            onDismiss = { showOverwriteConfirm = false },
        )
    }
    if (showUnsaved) {
        UnsavedChangesDialog(
            onSave = {
                showUnsaved = false
                showSaveDialog = true
            },
            onDiscard = {
                showUnsaved = false
                onBack()
            },
            onDismiss = { showUnsaved = false },
        )
    }
    if (showAddSource) {
        AddPagesSourceDialog(
            onFromPdf = {
                showAddSource = false
                pdfPicker.launch(arrayOf(PDF_MIME))
            },
            onBlank = {
                showAddSource = false
                showBlankDialog = true
            },
            onDismiss = { showAddSource = false },
        )
    }
    if (showBlankDialog && ready != null) {
        BlankPagesDialog(
            pageCount = ready.session.pageCount,
            referenceSize = viewModel::referenceSize,
            mixedSizes = viewModel.hasMixedSizes(),
            onConfirm = { count, point ->
                showBlankDialog = false
                showAdded(viewModel.insertBlankPages(count, point))
            },
            onDismiss = { showBlankDialog = false },
        )
    }
    if (showImageSource) {
        ImageSourceDialog(
            onPhotos = {
                showImageSource = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFiles = {
                showImageSource = false
                imageFilePicker.launch(arrayOf(IMAGE_MIME))
            },
            onDismiss = { showImageSource = false },
        )
    }
    if (pendingImages.isNotEmpty() && ready != null) {
        ImagesDialog(
            images = pendingImages,
            thumbnail = { uri -> viewModel.imageThumbnail(uri, THUMBNAIL_PX) },
            pageCount = ready.session.pageCount,
            referenceSize = viewModel::referenceSize,
            mixedSizes = viewModel.hasMixedSizes(),
            onConfirm = { mode, point ->
                showAdded(viewModel.insertPendingImages(mode, point))
            },
            onDismiss = viewModel::dropPendingImages,
        )
    }
    if (showPickedPagesDialog && pendingPdf != null && ready != null) {
        PickedPagesDialog(
            pickedCount = selection.size,
            pageCount = ready.session.pageCount,
            onConfirm = { point ->
                showPickedPagesDialog = false
                val indices = selection.mapNotNull { it.removePrefix(PICK_ID_PREFIX).toIntOrNull() }.sorted()
                selection = emptySet()
                rangeAnchor = null
                showAdded(viewModel.insertPendingPdfPages(indices, point))
            },
            onDismiss = { showPickedPagesDialog = false },
        )
    }
    (saveState as? SaveUiState.Failed)?.let { failed ->
        SaveErrorDialog(failed.failure, onDismiss = viewModel::dismissSaveResult)
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
    }
}

@Composable
private fun SaveAction(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.edit_save))
    }
}

/**
 * The edit hub: the document as it is now (the session's pages, read-only) with the tools of Home
 * bound to it in a bar at the bottom (spec §4.3, changed at the author's request: a grid of tools
 * alone looked like Home and didn't show which document was being edited).
 */
@Composable
private fun EditHub(
    state: EditUiState.Ready,
    imageThumbnail: (uri: String) -> android.graphics.Bitmap?,
    highlighted: Set<String>,
    scrollToId: String?,
    padding: PaddingValues,
) {
    Column(Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()).fillMaxSize()) {
        if (highlighted.isNotEmpty()) {
            Text(
                stringResource(R.string.add_review_hint_hub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        PagesGrid(
            pages = state.session.pages,
            sources = state.sources,
            imageThumbnail = imageThumbnail,
            mode = PagesMode.VIEW,
            selection = emptySet(),
            onTap = {},
            onLongPress = {},
            onCommitMove = { _, _ -> },
            actions = PageActions({}, {}, {}, {}, {}),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.weight(1f),
            highlighted = highlighted,
            scrollToId = scrollToId,
        )
    }
}

/** The document tools as a row of compact buttons, scrollable on narrow screens. */
@Composable
private fun HubToolBar(tools: List<PdfTool>, onToolClick: (PdfTool) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tools.forEach { tool -> HubToolButton(tool, onClick = { onToolClick(tool) }) }
        }
    }
}

@Composable
private fun HubToolButton(tool: PdfTool, onClick: () -> Unit) {
    val accent = tool.isSignatureAction
    Column(
        Modifier
            .width(76.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    tool.icon(),
                    contentDescription = null,
                    tint = if (accent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            stringResource(tool.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PagesPane(
    state: EditUiState.Ready,
    imageThumbnail: (uri: String) -> android.graphics.Bitmap?,
    highlighted: Set<String>,
    scrollToId: String?,
    mode: PagesMode,
    selection: Set<String>,
    onTap: (PageItem) -> Unit,
    onLongPress: (PageItem) -> Unit,
    onCommitMove: (Int, Int) -> Unit,
    actions: PageActions,
    padding: PaddingValues,
) {
    Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
        Text(
            stringResource(
                when {
                    highlighted.isNotEmpty() && mode == PagesMode.REORDER -> R.string.add_review_hint
                    mode == PagesMode.REMOVE -> R.string.edit_remove_hint
                    else -> R.string.edit_reorder_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PagesGrid(
            pages = state.session.pages,
            sources = state.sources,
            imageThumbnail = imageThumbnail,
            mode = mode,
            selection = selection,
            onTap = onTap,
            onLongPress = onLongPress,
            onCommitMove = onCommitMove,
            actions = actions,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
            highlighted = highlighted,
            scrollToId = scrollToId,
        )
    }
}

/** The pages of the PDF picked to add from, as a grid to select from (spec §6.2). */
@Composable
private fun PickPdfPane(
    pending: PendingPdf,
    selection: Set<String>,
    onTap: (PageItem) -> Unit,
    onLongPress: (PageItem) -> Unit,
    padding: PaddingValues,
) {
    val pages = remember(pending) { List(pending.source.pageCount) { PageItem.FromPdf("$PICK_ID_PREFIX$it", pending.docRef, it) } }
    val sources = remember(pending) { mapOf(pending.docRef to pending.source) }
    Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
        Text(
            stringResource(R.string.add_pick_pages_hint, pending.source.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PagesGrid(
            pages = pages,
            sources = sources,
            imageThumbnail = { null },
            mode = PagesMode.PICK,
            selection = selection,
            onTap = onTap,
            onLongPress = onLongPress,
            onCommitMove = { _, _ -> },
            actions = PageActions({}, {}, {}, {}, {}),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EditError(failure: OpenFailure, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(stringResource(R.string.edit_error_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(
                when (failure) {
                    OpenFailure.NOT_FOUND -> R.string.viewer_error_not_found
                    OpenFailure.PASSWORD_PROTECTED, OpenFailure.PASSWORD_UNSUPPORTED -> R.string.edit_error_protected
                    OpenFailure.UNREADABLE -> R.string.viewer_error_unreadable
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack) { Text(stringResource(R.string.edit_error_back)) }
    }
}

/** Snackbar after a copy was saved, with the two actions of spec §6.7. */
@Composable
private fun SavedSnackbar(onOpen: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = {
            // A plain TextButton takes the primary colour, unreadable on the snackbar's inverse surface.
            val actionColors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor)
            Row {
                TextButton(onClick = onOpen, colors = actionColors) { Text(stringResource(R.string.save_open)) }
                TextButton(onClick = onShare, colors = actionColors) { Text(stringResource(R.string.save_share)) }
            }
        },
        dismissAction = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = SnackbarDefaults.dismissActionContentColor)
            }
        },
    ) {
        Text(stringResource(R.string.save_done))
    }
}

private fun shareCopy(context: android.content.Context, uri: String, title: String) {
    val parsed = uri.toUri()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, parsed)
        clipData = ClipData.newRawUri(null, parsed)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: Exception) {
        // No app to share with.
    }
}
