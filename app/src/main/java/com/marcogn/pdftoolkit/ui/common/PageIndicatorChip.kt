package com.marcogn.pdftoolkit.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.ui.viewer.GoToPageDialog
import kotlinx.coroutines.launch

/**
 * "Page X of N" over a pager of pages; a tap opens "Go to page" (plan U1: the Fill and Annotate panes
 * have no scrubber, so far pages were a swipe each).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageIndicatorChip(pagerState: PagerState, pageCount: Int, modifier: Modifier = Modifier) {
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.fill_page_indicator, pagerState.currentPage + 1, pageCount),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.viewer_menu_go_to_page)) { showGoTo = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    if (showGoTo) {
        GoToPageDialog(
            pageCount = pageCount,
            onGo = { index ->
                showGoTo = false
                scope.launch { pagerState.scrollToPage(index) }
            },
            onDismiss = { showGoTo = false },
        )
    }
}

/** Tells [onPageChanged] the id of the page [pagerState] shows, whenever it changes. */
@Composable
fun ReportCurrentPage(pagerState: PagerState, pages: List<com.marcogn.pdftoolkit.domain.edit.PageItem>, onPageChanged: (pageId: String) -> Unit) {
    val currentPages by rememberUpdatedState(pages)
    val currentCallback by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index -> currentPages.getOrNull(index)?.let { currentCallback(it.id) } }
    }
}
