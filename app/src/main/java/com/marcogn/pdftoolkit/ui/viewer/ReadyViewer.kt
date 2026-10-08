package com.marcogn.pdftoolkit.ui.viewer

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.ui.annotate.AnnotateTool
import com.marcogn.pdftoolkit.ui.annotate.AnnotateToolBar
import com.marcogn.pdftoolkit.ui.annotate.applyLabel
import com.marcogn.pdftoolkit.ui.annotate.rememberAnnotatePaneState
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.edit.SaveDialog
import com.marcogn.pdftoolkit.ui.edit.SaveErrorDialog
import com.marcogn.pdftoolkit.ui.edit.SaveUiState
import com.marcogn.pdftoolkit.ui.edit.SavedSnackbar
import com.marcogn.pdftoolkit.ui.edit.UnsavedChangesDialog
import com.marcogn.pdftoolkit.ui.edit.shareCopy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.ui.navigation.editContainerBounds
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.ui.search.RevealRequest
import com.marcogn.pdftoolkit.pdf.text.PageTextReader
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import com.marcogn.pdftoolkit.ui.annotate.AnnotationLayer
import com.marcogn.pdftoolkit.ui.annotate.ResolveTextSelection
import com.marcogn.pdftoolkit.ui.annotate.TextSelectionState
import com.marcogn.pdftoolkit.ui.annotate.rememberTextSelectionState
import com.marcogn.pdftoolkit.ui.search.SearchHighlights
import com.marcogn.pdftoolkit.ui.search.SearchNotice
import com.marcogn.pdftoolkit.ui.search.SearchTopBar
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private const val MODE_CROSSFADE_MS = 200
private const val PANEL_MS = 220
private const val FAB_SCROLL_SLOP = 8f
private const val FAB_REAPPEAR_MS = 1_500L

/** How far from an annotation the eraser still takes it, so a thin underline isn't a precision job. */
private val ERASE_TOLERANCE = 14.dp

/**
 * The open document (spec §4.2): top bar with page indicator and menu, the pages in the chosen
 * [ReadingMode], the scrubber and the thumbnail bar, the Edit FAB and the text search (spec §5.1).
 *
 * [currentPage] lives here and is fed by whichever mode is on screen, so switching mode, the top
 * bar and the thumbnail bar always agree. Jumps (scrubber, thumbnails, "go to page") are sent
 * through [jumps] and executed by the mode on screen; search results are brought to the centre of
 * the screen through [reveals].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyViewer(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    onPageChanged: (Int) -> Unit,
    onEdit: (page: Int) -> Unit,
    save: ViewerSaveUi,
    onBack: () -> Unit,
    onReopen: (uri: String) -> Unit,
    onOpenCopy: (uri: String) -> Unit,
) {
    val pageCount = state.pageSizes.size
    var currentPage by rememberSaveable { mutableIntStateOf(state.startPage) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    // A single tap on the page hides the top bar, the Edit button and the system bars, and shows them again (plan U20).
    var immersive by rememberSaveable { mutableStateOf(false) }
    val jumps = remember { Channel<Int>(Channel.CONFLATED) }
    val reveals = remember { Channel<RevealRequest>(Channel.CONFLATED) }
    val search = state.search
    val searchState by search.state.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var handledFocusToken by rememberSaveable { mutableIntStateOf(searchState.focusToken) }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            search.start()
            search.setQuery(searchText)
        } else {
            search.close()
        }
    }
    // Scroll to the current result when the search asks for it (first result, next, previous), not
    // when more pages get indexed or the screen turns.
    LaunchedEffect(searchState.focusToken) {
        if (searchOpen && searchState.focusToken != handledFocusToken) {
            handledFocusToken = searchState.focusToken
            searchState.currentMatch?.let { reveals.trySend(RevealRequest(searchState.focusToken, it)) }
        }
    }
    val closeSearch = {
        searchOpen = false
        searchText = ""
    }

    // Text selection and Copy (spec §7.4): a press and hold selects the word, the handles stretch it.
    val selection = rememberTextSelectionState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.viewer_text_copied)
    val copyLabel = stringResource(R.string.viewer_selection_title)
    ResolveTextSelection(selection) { key -> key.toIntOrNull()?.let { state.textReader.selectionModel(it) } }
    LaunchedEffect(searchOpen) {
        if (searchOpen) selection.clear()
    }
    val onLongPress: (Int, Offset) -> Unit = onLongPress@{ page, point ->
        if (searchOpen) return@onLongPress
        scope.launch {
            val model = state.textReader.selectionModel(page) ?: return@launch
            val word = model.wordAt(point) ?: return@launch
            selection.select(page.toString(), model, word)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val copySelection = {
        val text = selection.clipboardText
        if (text.isNotEmpty()) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
            // Android 13 and later confirm a copy themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
            }
        }
        selection.clear()
    }
    // Editing on the pages (plan V-a, ADR 0005): the viewer's own session, drawn over the file as it is.
    val editing = state.editing
    val edits by editing.edits.collectAsStateWithLifecycle()
    val availability by state.editAvailability.collectAsStateWithLifecycle()
    val documentAnnotations by state.annotations.collectAsStateWithLifecycle()
    val pageTools = remember(editing, documentAnnotations, availability) {
        documentAnnotations?.takeIf { availability == EditAvailability.READY }?.let { ViewerPageTools(editing, it) }
    }
    val annotateTools = rememberAnnotatePaneState()
    var annotating by rememberSaveable { mutableStateOf(false) }
    // A tool is in effect only while the tool bar is out and the document can be edited.
    val armed = annotating && pageTools != null
    val armedTool = annotateTools.tool.takeIf { armed }
    val applyKind = armedTool?.kind ?: MarkupKind.HIGHLIGHT
    LaunchedEffect(searchOpen) {
        // Search and the tools don't mix (plan V-a): opening search puts the tool down.
        if (searchOpen) annotating = false
    }
    // Strokes just finished, drawn here until the session (a state flow, a frame later) has them.
    val pending = remember { mutableStateListOf<NewAnnotation>() }
    LaunchedEffect(edits) { pending.removeAll { p -> edits.session.annotations.added.any { it.id == p.id } } }
    val brush = armedTool?.freehand
    val brushColor = brush?.let(annotateTools::colorFor)
    val brushWidth = brush?.let(annotateTools::widthFor)
    val drawing = remember(pageTools, brush, brushColor, brushWidth) {
        val tools = pageTools
        if (tools == null || brush == null || brushColor == null || brushWidth == null) {
            null
        } else {
            ViewportDrawing(brush, brushColor, brushWidth) { page, stroke ->
                tools.inkAnnotation(page, stroke, brush, brushColor)?.let { annotation ->
                    pending += annotation
                    if (!editing.addAnnotation(annotation)) pending -= annotation
                }
            }
        }
    }
    val density = LocalDensity.current
    val eraseTolerancePx = with(density) { ERASE_TOLERANCE.toPx() }
    val unavailableMessage = stringResource(
        when (availability) {
            EditAvailability.PROTECTED -> R.string.edit_error_protected
            EditAvailability.UNREADABLE -> R.string.annotate_load_failed
            else -> R.string.viewer_edit_loading
        },
    )
    val toggleTools = {
        if (annotating) {
            annotating = false
        } else if (pageTools == null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        } else {
            annotating = true
            showThumbnails = false
            immersive = false
        }
    }

    // Saving from the viewer (plan V-a): the save dialog of the edit screen, the same engine (ADR 0005).
    val saving = save.state is SaveUiState.Saving
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    // Saving from the exit dialog leaves once the file is written (plan U3).
    var leaveAfterSave by rememberSaveable { mutableStateOf(false) }
    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) save.onSave(uri, false) else leaveAfterSave = false
    }
    val copySuffix = stringResource(R.string.save_copy_suffix)
    val startSave = {
        showSaveDialog = false
        if (save.overwriteChoice && save.canOverwrite) {
            // The save dialog is the explicit confirmation (plan U6).
            save.onSave(state.uri.toUri(), true)
        } else {
            copyLauncher.launch(save.suggestedCopyName(copySuffix))
        }
    }
    val savedMessage = stringResource(R.string.save_done)
    val shareTitle = stringResource(R.string.viewer_share_title)
    LaunchedEffect(save.state) {
        when (val result = save.state) {
            // The file on screen is the old one: leave, or open the new one in its place (ADR 0003).
            is SaveUiState.Overwritten -> if (leaveAfterSave) {
                leaveAfterSave = false
                Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                onReopen(result.uri)
            }
            is SaveUiState.Saved -> if (leaveAfterSave) {
                leaveAfterSave = false
                save.onDismissResult()
                Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                onBack()
            }
            is SaveUiState.Failed -> leaveAfterSave = false
            else -> Unit
        }
    }
    val requestExit = {
        if (edits.hasUnsavedChanges && !saving) showUnsaved = true else onBack()
    }
    val backStep = viewerBackStep(searchOpen, selection.isActive, annotating, edits.hasUnsavedChanges, saving)
    BackHandler(enabled = backStep != ViewerBackStep.LEAVE) {
        when (backStep) {
            ViewerBackStep.CLOSE_SEARCH -> closeSearch()
            ViewerBackStep.CLEAR_SELECTION -> selection.clear()
            ViewerBackStep.PUT_TOOL_DOWN -> annotating = false
            ViewerBackStep.ASK_TO_SAVE -> showUnsaved = true
            ViewerBackStep.LEAVE -> onBack()
        }
    }
    val toolBarVisible = annotating && !immersive && !searchOpen
    var toolBarHeight by remember { mutableStateOf(0.dp) }
    // Selecting is for reading and for the markup tools; a brush or the eraser takes the touch.
    val pageSelection = selection.takeIf { armedTool == null || armedTool.kind != null }

    val highlights = remember(searchOpen, searchState.matches, searchState.current) {
        if (searchOpen) SearchHighlights.of(searchState.matches, searchState.current) else SearchHighlights.None
    }
    val annotations = remember(documentAnnotations, edits.session.annotations, pending.toList()) {
        val session = edits.session.annotations
        val waiting = pending.filter { p -> session.added.none { it.id == p.id } }
        AnnotationLayer.of(documentAnnotations, session.copy(added = session.added + waiting), ViewerEditSession::pageId)
    }
    // The Edit button hides while the reader scrolls down and comes back on scrolling up or after a
    // pause (spec §4.2).
    var fabHidden by remember { mutableStateOf(false) }
    var scrollTick by remember { mutableIntStateOf(0) }
    val onScroll: (Float) -> Unit = { delta ->
        if (delta > FAB_SCROLL_SLOP) {
            fabHidden = true
            scrollTick++
        } else if (delta < -FAB_SCROLL_SLOP) {
            fabHidden = false
        }
    }
    LaunchedEffect(scrollTick) {
        delay(FAB_REAPPEAR_MS)
        fabHidden = false
    }
    val reportPage: (Int) -> Unit = { page ->
        if (page != currentPage) {
            currentPage = page
            onPageChanged(page)
        }
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) immersive = false
    }
    ImmersiveMode(immersive)
    val onTap: (PageTap?) -> Unit = { tap ->
        when {
            selection.isActive -> selection.clear()
            // The eraser takes what is under the finger; a tap beside everything does nothing.
            armedTool == AnnotateTool.ERASER -> tap?.let { pageTools?.erase(it.documentPage, it.point, eraseTolerancePx / it.screenPxPerPoint) }
            // A tap with a brush is a dot, not a request for full screen.
            armedTool?.freehand != null -> Unit
            !searchOpen -> immersive = !immersive
        }
    }
    // Copy and Highlight float by the selected text, within reach, instead of replacing the top bar (plan U21).
    val selectionBar: @Composable () -> Unit = {
        val range = selection.range
        val page = selection.key?.toIntOrNull()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = copySelection) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.viewer_selection_copy), modifier = Modifier.padding(start = 8.dp))
                }
                // Marked up here, on the page being read (plan V-a): the armed markup tool, or a highlight.
                val tools = pageTools
                if (range != null && page != null && tools != null) {
                    val kind = applyKind
                    TextButton(onClick = {
                        tools.markupAnnotation(page, selection.runs, kind, annotateTools.colorFor(kind))?.let(editing::addAnnotation)
                        selection.clear()
                    }) {
                        Icon(Icons.Outlined.BorderColor, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(kind.applyLabel()), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    // The bars float over the pages, which always fill the screen: showing or hiding them (plan U20) never
    // resizes the page area, so the document doesn't move or re-render under the reader's finger.
    val topBar: @Composable () -> Unit = {
            if (searchOpen) {
                SearchTopBar(
                    query = searchText,
                    onQueryChange = {
                        searchText = it
                        search.setQuery(it)
                    },
                    state = searchState,
                    onPrevious = search::previous,
                    onNext = search::next,
                    onClose = closeSearch,
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            // A tap opens "Go to page" (plan U13), as in most readers; the menu entry stays.
                            Text(
                                stringResource(R.string.viewer_page_indicator, currentPage + 1, pageCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClickLabel = stringResource(R.string.viewer_menu_go_to_page)) { showGoTo = true }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = requestExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        // "Save" as a word whenever there is something to save, as in the edit screen (plan U16).
                        if (edits.hasUnsavedChanges) {
                            TextButton(onClick = { showSaveDialog = true }, enabled = !saving) { Text(stringResource(R.string.edit_save)) }
                        }
                        IconToggleButton(checked = annotating, onCheckedChange = { toggleTools() }) {
                            Icon(Icons.Outlined.Draw, contentDescription = stringResource(R.string.tool_annotate))
                        }
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_search))
                        }
                        if (!annotating) {
                            IconToggleButton(checked = showThumbnails, onCheckedChange = { showThumbnails = it }) {
                                Icon(Icons.Outlined.ViewCarousel, contentDescription = stringResource(R.string.cd_thumbnails))
                            }
                        }
                        ViewerMenu(
                            readingMode = readingMode,
                            onReadingModeChange = onReadingModeChange,
                            onGoToPage = { showGoTo = true },
                            onInfo = { showInfo = true },
                            uri = state.uri.toUri(),
                        )
                    },
                )
            }
    }

    Scaffold(
        // No insets: the page area is the whole screen; each floating element pads itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                // Not while the pages are being edited here: the edit screen would start from the saved file (plan V-b adds save-first).
                visible = !fabHidden && !showThumbnails && !searchOpen && !selection.isActive && !immersive && !annotating && !edits.hasUnsavedChanges,
                enter = scaleIn(tween(PANEL_MS)) + fadeIn(tween(PANEL_MS)),
                exit = scaleOut(tween(PANEL_MS)) + fadeOut(tween(PANEL_MS)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { onEdit(currentPage) },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.edit_fab)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.navigationBarsPadding().editContainerBounds(),
                )
            }
        },
        snackbarHost = {
            Column(Modifier.navigationBarsPadding().padding(bottom = if (toolBarVisible) toolBarHeight else 0.dp)) {
                (save.state as? SaveUiState.Saved)?.let { saved ->
                    SavedSnackbar(
                        onOpen = {
                            save.onDismissResult()
                            onOpenCopy(saved.uri)
                        },
                        onShare = { shareCopy(context, saved.uri, shareTitle) },
                        onDismiss = save.onDismissResult,
                    )
                }
                SnackbarHost(snackbarHostState)
            }
        },
    ) { padding ->
        val pageDescription = stringResource(R.string.cd_viewer_page, currentPage + 1, pageCount)
        val nextPageLabel = stringResource(R.string.cd_viewer_next_page)
        val previousPageLabel = stringResource(R.string.cd_viewer_previous_page)
        // The pages are drawn on a Canvas, which TalkBack can't read: the container says where the
        // reader is and offers page turns (the scrubber and the thumbnails still jump anywhere).
        Box(
            Modifier.padding(padding).fillMaxSize().semantics {
                contentDescription = pageDescription
                customActions = listOf(
                    CustomAccessibilityAction(nextPageLabel) {
                        if (currentPage < pageCount - 1) jumps.trySend(currentPage + 1)
                        currentPage < pageCount - 1
                    },
                    CustomAccessibilityAction(previousPageLabel) {
                        if (currentPage > 0) jumps.trySend(currentPage - 1)
                        currentPage > 0
                    },
                )
            },
        ) {
            Crossfade(
                targetState = readingMode,
                animationSpec = tween(MODE_CROSSFADE_MS),
                label = "readingMode",
                modifier = Modifier.fillMaxSize(),
            ) { mode ->
                when (mode) {
                    ReadingMode.CONTINUOUS -> ContinuousPages(state, budget, currentPage, jumps, reveals, highlights, annotations, pageSelection, onLongPress, onTap, selectionBar, drawing, reportPage, onScroll)
                    ReadingMode.SINGLE_PAGE -> SinglePages(state, budget, currentPage, jumps, reveals, highlights, annotations, pageSelection, onLongPress, onTap, selectionBar, drawing, reportPage)
                }
            }

            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                AnimatedVisibility(
                    visible = searchOpen || !immersive,
                    enter = slideInVertically(tween(PANEL_MS)) { -it } + fadeIn(tween(PANEL_MS)),
                    exit = slideOutVertically(tween(PANEL_MS)) { -it } + fadeOut(tween(PANEL_MS)),
                ) { topBar() }
                // Non-blocking progress (spec §6.7): reading goes on while the file is written.
                (save.state as? SaveUiState.Saving)?.let { saving ->
                    if (saving.fraction > 0f) {
                        LinearProgressIndicator(progress = { saving.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (searchOpen) {
                    if (searchState.isIndexing) {
                        val indexingDescription = stringResource(R.string.cd_search_indexing)
                        LinearProgressIndicator(
                            progress = { searchState.indexedPages.toFloat() / pageCount.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = indexingDescription },
                        )
                    }
                    SearchNotice(searchState, Modifier.fillMaxWidth())
                }
            }

            PageScrubber(
                pageCount = pageCount,
                currentPage = currentPage,
                onPageSelected = { jumps.trySend(it) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(
                    bottom = when {
                        showThumbnails -> ThumbnailBarHeight
                        toolBarVisible -> toolBarHeight
                        else -> 0.dp
                    },
                ),
            )

            // The page tools (plan V-a, a plain bar until V-b's): over the pages, gone in full screen.
            AnimatedVisibility(
                visible = toolBarVisible,
                enter = slideInVertically(tween(PANEL_MS)) { it },
                exit = slideOutVertically(tween(PANEL_MS)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(Modifier.onSizeChanged { toolBarHeight = with(density) { it.height.toDp() } }) {
                    AnnotateToolBar(
                        state = annotateTools,
                        selection = selection,
                        undoRedo = UndoRedo(edits.session.canUndo, edits.session.canRedo, { editing.undo() }, { editing.redo() }),
                        side = false,
                        // The bar by the selection applies it.
                        showApply = false,
                    ) {}
                }
            }

            AnimatedVisibility(
                visible = showThumbnails,
                enter = slideInVertically(tween(PANEL_MS)) { it },
                exit = slideOutVertically(tween(PANEL_MS)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ThumbnailBar(
                    pageSizes = state.pageSizes,
                    currentPage = currentPage,
                    thumbnails = state.thumbnails,
                    onPageSelected = { jumps.trySend(it) },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = pageCount,
            onGo = {
                showGoTo = false
                jumps.trySend(it)
            },
            onDismiss = { showGoTo = false },
        )
    }
    if (showInfo) {
        DocumentInfoDialog(
            name = state.displayName.orEmpty(),
            pageCount = pageCount,
            sizeBytes = state.sizeBytes,
            onDismiss = { showInfo = false },
        )
    }
    if (showSaveDialog) {
        SaveDialog(
            overwrite = save.overwriteChoice && save.canOverwrite,
            canOverwrite = save.canOverwrite,
            onOverwriteChange = save.onOverwriteChange,
            onConfirm = startSave,
            onDismiss = {
                showSaveDialog = false
                leaveAfterSave = false
            },
            flattenInk = if (edits.session.annotations.hasInk) save.flattenInk else null,
            onFlattenInkChange = save.onFlattenInkChange,
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
                editing.discard()
                onBack()
            },
            onDismiss = { showUnsaved = false },
        )
    }
    (save.state as? SaveUiState.Failed)?.let { failed ->
        SaveErrorDialog(failed.failure, onDismiss = save.onDismissResult)
    }
}

/** Hides the status and navigation bars while [immersive]; they come back on a swipe from the edge, and on leaving. */
@Composable
private fun ImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller?.hide(WindowInsetsCompat.Type.systemBars()) else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Overflow menu (spec §4.2): reading mode, go to page, share, document information. */
@Composable
private fun ViewerMenu(
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    onGoToPage: () -> Unit,
    onInfo: () -> Unit,
    uri: Uri,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.viewer_share_title)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModeItem(R.string.viewer_menu_mode_continuous, readingMode == ReadingMode.CONTINUOUS) {
                expanded = false
                onReadingModeChange(ReadingMode.CONTINUOUS)
            }
            ModeItem(R.string.viewer_menu_mode_single, readingMode == ReadingMode.SINGLE_PAGE) {
                expanded = false
                onReadingModeChange(ReadingMode.SINGLE_PAGE)
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_menu_go_to_page)) },
                onClick = {
                    expanded = false
                    onGoToPage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_menu_share)) },
                onClick = {
                    expanded = false
                    sharePdf(context, uri, shareTitle)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_menu_info)) },
                onClick = {
                    expanded = false
                    onInfo()
                },
            )
        }
    }
}

@Composable
private fun ModeItem(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        onClick = onClick,
    )
}

/** Hands the document itself to the system share sheet (read access granted to the receiver). */
private fun sharePdf(context: android.content.Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: Exception) {
        // No app to share with, or a file:// URI the system refuses to hand out: nothing to do.
    }
}

@Composable
private fun ContinuousPages(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    startPage: Int,
    jumps: Channel<Int>,
    reveals: Channel<RevealRequest>,
    highlights: SearchHighlights,
    annotations: AnnotationLayer,
    selection: TextSelectionState?,
    onLongPress: (Int, Offset) -> Unit,
    onTap: (PageTap?) -> Unit,
    selectionBar: @Composable () -> Unit,
    drawing: ViewportDrawing?,
    onPageChanged: (Int) -> Unit,
    onScroll: (Float) -> Unit,
) {
    val viewportState = rememberSaveable(saver = PdfViewportState.Saver) {
        PdfViewportState(ViewportAnchor(startPage, pageFractionY = 0f, contentFractionX = 0.5f, zoom = 1f))
    }
    LaunchedEffect(viewportState) {
        snapshotFlow { viewportState.currentPage }.collect { if (it >= 0) onPageChanged(it) }
    }
    LaunchedEffect(viewportState, jumps) {
        jumps.receiveAsFlow().collect { viewportState.jumpToPage(it) }
    }
    LaunchedEffect(viewportState, reveals) {
        reveals.receiveAsFlow().collect { viewportState.centerOnPageRect(it.match.pageIndex, it.match.bounds) }
    }
    LaunchedEffect(viewportState) {
        var previous = viewportState.viewport.offset.y
        snapshotFlow { viewportState.viewport.offset.y }.collect { y ->
            onScroll(y - previous)
            previous = y
        }
    }
    PdfViewport(
        pageSizes = state.pageSizes,
        bitmaps = state.bitmaps,
        budget = budget,
        state = viewportState,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        highlights = highlights,
        annotations = annotations,
        selection = selection,
        onLongPress = onLongPress,
        onTap = onTap,
        selectionBar = selectionBar,
        drawing = drawing,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * One page per screen, swipe to turn (spec §4.2). Each page is a [PdfViewport] of its own with its
 * own zoom; at fit width, or at the edge of a zoomed page, a horizontal drag goes to the pager.
 * The pager keeps the neighbours composed, which also prepares their bitmaps.
 */
@Composable
private fun SinglePages(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    startPage: Int,
    jumps: Channel<Int>,
    reveals: Channel<RevealRequest>,
    highlights: SearchHighlights,
    annotations: AnnotationLayer,
    selection: TextSelectionState?,
    onLongPress: (Int, Offset) -> Unit,
    onTap: (PageTap?) -> Unit,
    selectionBar: @Composable () -> Unit,
    drawing: ViewportDrawing?,
    onPageChanged: (Int) -> Unit,
) {
    // A search result waits here until its page is composed and has a layout, then it is centred.
    var pendingReveal by remember { mutableStateOf<RevealRequest?>(null) }
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, state.pageSizes.size - 1)) { state.pageSizes.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
    }
    LaunchedEffect(pagerState, jumps) {
        jumps.receiveAsFlow().collect { pagerState.scrollToPage(it) }
    }
    LaunchedEffect(pagerState, reveals) {
        reveals.receiveAsFlow().collect {
            pendingReveal = it
            pagerState.scrollToPage(it.match.pageIndex)
        }
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        key = { it },
        // A brush takes every one-finger drag, so the pages don't turn under it.
        userScrollEnabled = drawing == null,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val viewportState = rememberSaveable(page, saver = PdfViewportState.Saver) { PdfViewportState() }
        LaunchedEffect(viewportState, pendingReveal) {
            pendingReveal?.takeIf { it.match.pageIndex == page }?.let {
                viewportState.centerOnPageRect(0, it.match.bounds)
                pendingReveal = null
            }
        }
        PdfViewport(
            pageSizes = listOf(state.pageSizes[page]),
            bitmaps = state.bitmaps,
            budget = budget,
            state = viewportState,
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            pageIndexOffset = page,
            requestSource = page,
            yieldHorizontalToParent = true,
            highlights = highlights,
            annotations = annotations,
            selection = selection,
            onLongPress = onLongPress,
            onTap = onTap,
            selectionBar = selectionBar,
            // One ink layer at a time (the library's advice): only on the page that is shown.
            drawing = drawing?.takeIf { pagerState.settledPage == page },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The text model of page [index] for selecting, or null if the page can't be read. */
private suspend fun PageTextReader.selectionModel(index: Int): TextSelection? =
    try {
        TextSelection(page(index))
    } catch (e: java.io.IOException) {
        null
    }
