package com.marcogn.pdftoolkit.ui.edit

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
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
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfTool
import com.marcogn.pdftoolkit.ui.home.ToolButton
import com.marcogn.pdftoolkit.ui.home.labelRes
import kotlinx.coroutines.launch

private enum class EditPane { HUB, REMOVE, REORDER }

private fun PdfTool?.initialPane(): EditPane = when (this) {
    PdfTool.REMOVE_PAGES -> EditPane.REMOVE
    PdfTool.REORDER_PAGES -> EditPane.REORDER
    else -> EditPane.HUB
}

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** Tools of the hub that already work; the others show "coming up" until phases 3 and 4. */
private val implementedTools = setOf(PdfTool.REMOVE_PAGES, PdfTool.REORDER_PAGES)

/**
 * Edit hub and page tools on one edit session (spec §4.3, §6). Hub, "remove" and "reorder" are
 * panes of this one screen so they share the session, the renderer and the save state; the
 * system back goes pane → hub → leave (asking about unsaved changes).
 *
 * @param startTool the tool tapped on Home, which opens straight on its pane (spec §4.1); null from the viewer.
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

    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri, overwrite = false)
    }
    val startSave = {
        showSaveDialog = false
        if (overwriteChoice && canOverwrite) {
            showOverwriteConfirm = true
        } else {
            copyLauncher.launch(viewModel.suggestedCopyName(resources.getString(R.string.save_copy_suffix)))
        }
    }

    LaunchedEffect(saveState) {
        (saveState as? SaveUiState.Overwritten)?.let { onResultReady(it.uri) }
    }

    val ready = uiState as? EditUiState.Ready
    val saving = saveState is SaveUiState.Saving
    // Selection can only hold pages that still exist (undo can bring pages back, remove takes them).
    LaunchedEffect(ready?.session?.pages) {
        val ids = ready?.session?.pages?.map { it.id }?.toSet() ?: return@LaunchedEffect
        if (!ids.containsAll(selection)) selection = selection intersect ids
        if (rangeAnchor !in ids) rangeAnchor = null
    }

    val requestExit = {
        if (ready?.hasUnsavedChanges == true && !saving) showUnsaved = true else onBack()
    }
    val handleBack = {
        when {
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
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            Column {
                when {
                    ready == null -> TopAppBar(
                        title = {},
                        navigationIcon = { BackButton(onBack) },
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
                            if (ready.hasUnsavedChanges) SaveAction(enabled = !saving) { showSaveDialog = true }
                        },
                    )
                    else -> TopAppBar(
                        title = {
                            Text(
                                stringResource(if (pane == EditPane.REMOVE) R.string.tool_remove_pages else R.string.tool_reorder_pages),
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
            is EditUiState.Ready -> when (pane) {
                EditPane.HUB -> EditHub(
                    onToolClick = { tool ->
                        when (tool) {
                            PdfTool.REMOVE_PAGES -> pane = EditPane.REMOVE
                            PdfTool.REORDER_PAGES -> pane = EditPane.REORDER
                            else -> showMessage(resources.getString(R.string.edit_tool_unavailable, resources.getString(tool.labelRes())))
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
                EditPane.REMOVE, EditPane.REORDER -> PagesPane(
                    state = state,
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
        SaveDialog(
            overwrite = overwriteChoice && canOverwrite,
            canOverwrite = canOverwrite,
            onOverwriteChange = viewModel::setOverwriteChoice,
            onConfirm = startSave,
            onDismiss = { showSaveDialog = false },
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

/** The tools of Home bound to the open document (spec §4.3). */
@Composable
private fun EditHub(onToolClick: (PdfTool) -> Unit, modifier: Modifier = Modifier) {
    val tools = remember { PdfTool.available.filter { it.requiresDocument } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(tools, key = { it.name }) { tool -> ToolButton(tool, onClick = { onToolClick(tool) }) }
    }
}

@Composable
private fun PagesPane(
    state: EditUiState.Ready,
    mode: PagesMode,
    selection: Set<String>,
    onTap: (com.marcogn.pdftoolkit.domain.edit.PageItem) -> Unit,
    onLongPress: (com.marcogn.pdftoolkit.domain.edit.PageItem) -> Unit,
    onCommitMove: (Int, Int) -> Unit,
    actions: PageActions,
    padding: PaddingValues,
) {
    Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
        Text(
            stringResource(if (mode == PagesMode.REMOVE) R.string.edit_remove_hint else R.string.edit_reorder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PagesGrid(
            pages = state.session.pages,
            pageSizes = state.pageSizes,
            thumbnails = state.thumbnails,
            mode = mode,
            selection = selection,
            onTap = onTap,
            onLongPress = onLongPress,
            onCommitMove = onCommitMove,
            actions = actions,
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
            Row {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.save_open)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.save_share)) }
            }
        },
        dismissAction = {
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = null) }
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
