package com.marcogn.pdftoolkit.ui.viewer

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
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
    onHighlight: (page: Int, selectionStart: Int, selectionEnd: Int) -> Unit,
    onBack: () -> Unit,
) {
    val pageCount = state.pageSizes.size
    var currentPage by rememberSaveable { mutableIntStateOf(state.startPage) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
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
    BackHandler(enabled = searchOpen, onBack = closeSearch)

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
    BackHandler(enabled = selection.isActive) { selection.clear() }
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
    val highlights = remember(searchOpen, searchState.matches, searchState.current) {
        if (searchOpen) SearchHighlights.of(searchState.matches, searchState.current) else SearchHighlights.None
    }
    val documentAnnotations by state.annotations.collectAsStateWithLifecycle()
    val annotations = remember(documentAnnotations) { AnnotationLayer.of(documentAnnotations) }
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

    // A single tap on the page hides the top bar, the Edit button and the system bars, and shows them again (plan U20).
    var immersive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchOpen) {
        if (searchOpen) immersive = false
    }
    ImmersiveMode(immersive)
    val onTap: () -> Unit = {
        if (selection.isActive) selection.clear() else if (!searchOpen) immersive = !immersive
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
                // Annotating goes through the edit screen, one save path (plan U5): the selection travels along.
                if (range != null && page != null) {
                    TextButton(onClick = { onHighlight(page, range.start, range.end) }) {
                        Icon(Icons.Outlined.BorderColor, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.viewer_selection_highlight), modifier = Modifier.padding(start = 8.dp))
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
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_search))
                        }
                        IconToggleButton(checked = showThumbnails, onCheckedChange = { showThumbnails = it }) {
                            Icon(Icons.Outlined.ViewCarousel, contentDescription = stringResource(R.string.cd_thumbnails))
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
                visible = !fabHidden && !showThumbnails && !searchOpen && !selection.isActive && !immersive,
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
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) },
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
                    ReadingMode.CONTINUOUS -> ContinuousPages(state, budget, currentPage, jumps, reveals, highlights, annotations, selection, onLongPress, onTap, selectionBar, reportPage, onScroll)
                    ReadingMode.SINGLE_PAGE -> SinglePages(state, budget, currentPage, jumps, reveals, highlights, annotations, selection, onLongPress, onTap, selectionBar, reportPage)
                }
            }

            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                AnimatedVisibility(
                    visible = searchOpen || !immersive,
                    enter = slideInVertically(tween(PANEL_MS)) { -it } + fadeIn(tween(PANEL_MS)),
                    exit = slideOutVertically(tween(PANEL_MS)) { -it } + fadeOut(tween(PANEL_MS)),
                ) { topBar() }
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
                modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = if (showThumbnails) ThumbnailBarHeight else 0.dp),
            )

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
    selection: TextSelectionState,
    onLongPress: (Int, Offset) -> Unit,
    onTap: () -> Unit,
    selectionBar: @Composable () -> Unit,
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
    selection: TextSelectionState,
    onLongPress: (Int, Offset) -> Unit,
    onTap: () -> Unit,
    selectionBar: @Composable () -> Unit,
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
