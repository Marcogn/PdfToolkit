package com.marcogn.pdftoolkit.ui.viewer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow

private const val MODE_CROSSFADE_MS = 200
private const val PANEL_MS = 220
private const val FAB_SCROLL_SLOP = 8f
private const val FAB_REAPPEAR_MS = 1_500L

/**
 * The open document (spec §4.2): top bar with page indicator and menu, the pages in the chosen
 * [ReadingMode], the scrubber and the thumbnail bar. Search (phase 5) and the Edit FAB (phase 2)
 * are not here yet.
 *
 * [currentPage] lives here and is fed by whichever mode is on screen, so switching mode, the top
 * bar and the thumbnail bar always agree. Jumps (scrubber, thumbnails, "go to page") are sent
 * through [jumps] and executed by the mode on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyViewer(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    onPageChanged: (Int) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val pageCount = state.pageSizes.size
    var currentPage by rememberSaveable { mutableIntStateOf(state.startPage) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val jumps = remember { Channel<Int>(Channel.CONFLATED) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            stringResource(R.string.viewer_page_indicator, currentPage + 1, pageCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
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
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !fabHidden && !showThumbnails,
                enter = scaleIn(tween(PANEL_MS)) + fadeIn(tween(PANEL_MS)),
                exit = scaleOut(tween(PANEL_MS)) + fadeOut(tween(PANEL_MS)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = onEdit,
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.edit_fab)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Crossfade(
                targetState = readingMode,
                animationSpec = tween(MODE_CROSSFADE_MS),
                label = "readingMode",
                modifier = Modifier.fillMaxSize(),
            ) { mode ->
                when (mode) {
                    ReadingMode.CONTINUOUS -> ContinuousPages(state, budget, currentPage, jumps, reportPage, onScroll)
                    ReadingMode.SINGLE_PAGE -> SinglePages(state, budget, currentPage, jumps, reportPage)
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
    onPageChanged: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, state.pageSizes.size - 1)) { state.pageSizes.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
    }
    LaunchedEffect(pagerState, jumps) {
        jumps.receiveAsFlow().collect { pagerState.scrollToPage(it) }
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val viewportState = rememberSaveable(page, saver = PdfViewportState.Saver) { PdfViewportState() }
        PdfViewport(
            pageSizes = listOf(state.pageSizes[page]),
            bitmaps = state.bitmaps,
            budget = budget,
            state = viewportState,
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            pageIndexOffset = page,
            requestSource = page,
            yieldHorizontalToParent = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
