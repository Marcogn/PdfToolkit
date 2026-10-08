package com.marcogn.pdftoolkit.ui.edit

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.InsertionPoint
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.ui.fill.ToolButtonFrame
import com.marcogn.pdftoolkit.ui.common.ToolStrip
import com.marcogn.pdftoolkit.ui.common.TransientHint
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.common.isLandscape
import com.marcogn.pdftoolkit.ui.viewer.takePersistableAccess
import kotlinx.coroutines.launch

/** 1-based position in [pages] of page [mainPageIndex] of the main document, or null if unknown or removed. */
internal fun viewerPageNumber(pages: List<PageItem>?, mainPageIndex: Int): Int? {
    if (mainPageIndex < 0 || pages == null) return null
    return pages.indexOfFirst { it is PageItem.FromPdf && it.docRef == DocRef.MAIN && it.pageIndex == mainPageIndex }
        .takeIf { it >= 0 }?.plus(1)
}

private const val PDF_MIME = "application/pdf"
private const val IMAGE_MIME = "image/*"
private const val THUMBNAIL_PX = 320

/** Ids of the cells of the page picker; they live only in this screen, not in the edit session. */
private const val PICK_ID_PREFIX = "pick"

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * The edit screen (spec §6): "Organize pages" on one edit session (reorder, rotate, remove and add pages,
 * merge). Everything done on the pages themselves (highlight, draw, fill and sign) happens in the viewer
 * (ADR 0005). The system back leaves it, asking about unsaved changes. In landscape the tools sit in a
 * rail at the side instead of a bar at the bottom (plan U18).
 *
 * @param startPage the page the reader was on in the main document, or -1 (from Home): the insertion
 * dialogs default to "after" it (plan U1).
 * @param onBack leaves the edit.
 * @param onResultReady an overwrite finished: the original has new content, so the caller must
 * drop any screen still showing the old one and open [uri].
 * @param onOpenCopy opens the saved copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    startPage: Int,
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current

    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var rangeAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    var showAddSource by rememberSaveable { mutableStateOf(false) }
    var showBlankDialog by rememberSaveable { mutableStateOf(false) }
    var showPickedPagesDialog by rememberSaveable { mutableStateOf(false) }
    // Where "Add" was started from (after the selected page, after the one the reader was on, or the end).
    var addPoint by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(InsertionPoint.END_OF_DOCUMENT) }
    val side = isLandscape()
    // Pages just added: shown highlighted, in the page grid, scrolled into view.
    var highlighted by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var scrollToId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoSaveHandled by rememberSaveable { mutableStateOf(false) }
    // Saving from the exit dialog leaves once the copy is written (plan U3).
    var leaveAfterSave by rememberSaveable { mutableStateOf(false) }

    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri, overwrite = false) else leaveAfterSave = false
    }
    val copySuffix = resources.getString(if (viewModel.isMerge) R.string.save_merge_suffix else R.string.save_copy_suffix)
    val startSave = {
        showSaveDialog = false
        if (overwriteChoice && canOverwrite) {
            // The save dialog already asked for explicit confirmation (plan U6).
            viewModel.save(viewModel.sourceUri.toUri(), overwrite = true)
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
    val savedMessage = stringResource(R.string.save_done)
    LaunchedEffect(saveState) {
        (saveState as? SaveUiState.Overwritten)?.let { onResultReady(it.uri) }
        if (saveState is SaveUiState.Saved && leaveAfterSave) {
            // The edit is over: say it was saved (the snackbar with Open / Share would go with this screen).
            leaveAfterSave = false
            viewModel.dismissSaveResult()
            Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
            onBack()
        } else if (saveState is SaveUiState.Failed) {
            leaveAfterSave = false
        }
        // "N pages added · Undo" no longer applies once the document is saved, and it would stack
        // on top of the "saved" snackbar.
        if (saveState is SaveUiState.Saved) snackbarHostState.currentSnackbarData?.dismiss()
    }

    val ready = uiState as? EditUiState.Ready
    val saving = saveState is SaveUiState.Saving
    val picking = pendingPdf != null
    // The "new" marks help find what was just added and drag it where it belongs; once saved there is
    // nothing new any more.
    LaunchedEffect(saveState is SaveUiState.Saved) {
        if (saveState is SaveUiState.Saved) {
            highlighted = emptySet()
            scrollToId = null
        }
    }
    // A merge without editing asks where to save straight away.
    LaunchedEffect(ready != null) {
        if (ready == null) return@LaunchedEffect
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
            picking -> {
                viewModel.dropPendingPdf()
                selection = emptySet()
                rangeAnchor = null
            }
            selection.isNotEmpty() -> {
                selection = emptySet()
                rangeAnchor = null
            }
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

    // No "Merge" here: on an open document it is "Add pages → from another PDF" (author's decision).
    // Opened from the viewer, new pages default to "after the page being read" (plan U1).
    // In "Organize pages" with pages selected, after the last of them (plan U4).
    val insertionDefault = run {
        val lastSelected = ready?.session?.pages?.indexOfLast { it.id in selection } ?: -1
        if (lastSelected >= 0) {
            InsertionPoint(InsertionPoint.Kind.AFTER_PAGE, lastSelected + 1)
        } else {
            viewerPageNumber(ready?.session?.pages, startPage)
                ?.let { InsertionPoint(InsertionPoint.Kind.AFTER_PAGE, it) } ?: InsertionPoint.END_OF_DOCUMENT
        }
    }
    val undoRedo = UndoRedo(ready?.session?.canUndo == true, ready?.session?.canRedo == true, viewModel::undo, viewModel::redo)
    // The tools: a bar under the content, or a rail at its side in landscape (plan U18); undo and
    // redo are in it, where the thumb is (plan U21).
    val controls: @Composable (Boolean) -> Unit = { sideMode ->
        if (ready != null && !picking) {
            val stripModifier = if (sideMode) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()
            OrganizeToolBar(undoRedo, sideMode, stripModifier) {
                addPoint = insertionDefault
                showAddSource = true
            }
        }
    }
    val railed = side && ready != null && !picking

    Scaffold(
        // In the bottomBar slot, so snackbars are placed above the tools instead of covering them.
        bottomBar = { if (!railed) controls(false) },
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
                    selection.isNotEmpty() -> TopAppBar(
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
                    else -> TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.tool_organize_pages),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = { BackButton(handleBack) },
                        actions = {
                            IconButton(onClick = { selection = ready.session.pages.map { it.id }.toSet() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.edit_select_all))
                            }
                            if (ready.hasUnsavedChanges) SaveAction(enabled = !saving) { showSaveDialog = true }
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
    ) { scaffoldPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // With a rail, the content leaves the end side (and the system bar there) to it.
        val padding = if (railed) {
            PaddingValues(
                start = scaffoldPadding.calculateStartPadding(layoutDirection),
                top = scaffoldPadding.calculateTopPadding(),
                bottom = scaffoldPadding.calculateBottomPadding(),
            )
        } else {
            scaffoldPadding
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
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
            } else {
                PagesPane(
                    state = state,
                    imageThumbnail = { uri -> viewModel.imageThumbnail(uri, THUMBNAIL_PX) },
                    highlighted = highlighted,
                    scrollToId = scrollToId,
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
            if (railed) {
                Box(
                    Modifier.padding(
                        top = scaffoldPadding.calculateTopPadding(),
                        end = scaffoldPadding.calculateEndPadding(layoutDirection),
                        bottom = scaffoldPadding.calculateBottomPadding(),
                    ),
                ) { controls(true) }
            }
        }
    }

    if (showSaveDialog) {
        SaveDialog(
            overwrite = overwriteChoice && canOverwrite,
            canOverwrite = canOverwrite,
            onOverwriteChange = viewModel::setOverwriteChoice,
            onConfirm = startSave,
            onDismiss = {
                showSaveDialog = false
                leaveAfterSave = false
            },
        )
    }
    if (showUnsaved) {
        UnsavedChangesDialog(
            onSave = {
                showUnsaved = false
                leaveAfterSave = true
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
        AddSourceDialog(
            onFromPdf = {
                showAddSource = false
                // The page picker reuses the selection for the pages of the other PDF.
                selection = emptySet()
                rangeAnchor = null
                pdfPicker.launch(arrayOf(PDF_MIME))
            },
            onBlank = {
                showAddSource = false
                showBlankDialog = true
            },
            onPhotos = {
                showAddSource = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFiles = {
                showAddSource = false
                imageFilePicker.launch(arrayOf(IMAGE_MIME))
            },
            onDismiss = { showAddSource = false },
        )
    }
    if (showBlankDialog && ready != null) {
        BlankPagesDialog(
            pageCount = ready.session.pageCount,
            referenceSize = viewModel::referenceSize,
            mixedSizes = viewModel.hasMixedSizes(),
            initialPoint = addPoint,
            onConfirm = { count, point ->
                showBlankDialog = false
                showAdded(viewModel.insertBlankPages(count, point))
            },
            onDismiss = { showBlankDialog = false },
        )
    }
    if (pendingImages.isNotEmpty() && ready != null) {
        ImagesDialog(
            images = pendingImages,
            thumbnail = { uri -> viewModel.imageThumbnail(uri, THUMBNAIL_PX) },
            pageCount = ready.session.pageCount,
            referenceSize = viewModel::referenceSize,
            mixedSizes = viewModel.hasMixedSizes(),
            initialPoint = addPoint,
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
            initialPoint = addPoint,
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

/** "Save" as a word, shown whenever there is something to save, the same in every pane (plan U16). */
@Composable
private fun SaveAction(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(stringResource(R.string.edit_save))
    }
}

/** The tools of "Organize pages": adding pages from anywhere is one button (plan U4). */
@Composable
private fun OrganizeToolBar(undoRedo: UndoRedo, side: Boolean, modifier: Modifier = Modifier, onAdd: () -> Unit) {
    ToolStrip(side, undoRedo, modifier) {
        ToolButtonFrame(R.string.organize_add, false, onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = null) }
    }
}

@Composable
private fun PagesPane(
    state: EditUiState.Ready,
    imageThumbnail: (uri: String) -> android.graphics.Bitmap?,
    highlighted: Set<String>,
    scrollToId: String?,
    selection: Set<String>,
    onTap: (PageItem) -> Unit,
    onLongPress: (PageItem) -> Unit,
    onCommitMove: (Int, Int) -> Unit,
    actions: PageActions,
    padding: PaddingValues,
) {
    Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
        PagesGrid(
            pages = state.session.pages,
            sources = state.sources,
            imageThumbnail = imageThumbnail,
            mode = PagesMode.ORGANIZE,
            selection = selection,
            onTap = onTap,
            onLongPress = onLongPress,
            onCommitMove = onCommitMove,
            actions = actions,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
            highlighted = highlighted,
            scrollToId = scrollToId,
        )
        // For a few seconds, over the grid instead of a permanent row (plan U7).
        TransientHint(
            stringResource(if (highlighted.isNotEmpty()) R.string.add_review_hint else R.string.organize_hint),
            key = highlighted.isNotEmpty(),
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
