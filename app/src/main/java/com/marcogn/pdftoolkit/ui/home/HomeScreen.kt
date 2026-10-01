package com.marcogn.pdftoolkit.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.PdfTool
import com.marcogn.pdftoolkit.ui.recents.RecentItem
import kotlinx.coroutines.launch

/** Minimum width of a tool button: three columns on a 360 dp wide phone. */
private val ToolMinSize = 100.dp

/**
 * Home without an open document (spec §4.1): "Open PDF" card, recents, tool grid.
 * [recents] is null until the first load. Tools still lead to a placeholder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMenuClick: () -> Unit,
    onOpenPdfClick: () -> Unit,
    onToolClick: (PdfTool) -> Unit,
    recents: List<RecentItem>? = emptyList(),
    onRecentClick: (RecentItem) -> Unit = {},
    onRecentRemove: (RecentItem) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    val onToolTap: (PdfTool) -> Unit = { tool ->
        if (tool.comingSoon) {
            // Product phase 2 tool: just a short snackbar, no navigation (spec §4.1).
            val message = resources.getString(R.string.home_coming_soon_message, resources.getString(tool.labelRes()))
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        } else {
            onToolClick(tool)
        }
    }

    val onRecentTap: (RecentItem) -> Unit = { item ->
        if (item.accessible) {
            onRecentClick(item)
        } else {
            val message = resources.getString(R.string.home_recent_unavailable_message)
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(ToolMinSize),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fullWidth { OpenPdfCard(onClick = onOpenPdfClick) }
            fullWidth { SectionTitle(stringResource(R.string.home_recents_title)) }
            if (recents != null) {
                if (recents.isEmpty()) {
                    fullWidth { RecentsEmpty() }
                } else {
                    fullWidth { RecentsRow(recents, onRecentTap, onRecentRemove) }
                }
            }
            fullWidth { SectionTitle(stringResource(R.string.home_tools_title)) }
            items(PdfTool.available, key = { it.name }) { tool -> ToolButton(tool, onClick = { onToolTap(tool) }) }
            fullWidth { SectionTitle(stringResource(R.string.home_upcoming_title)) }
            items(PdfTool.upcoming, key = { it.name }) { tool -> ToolButton(tool, onClick = { onToolTap(tool) }) }
        }
    }
}

@Composable
private fun RecentsRow(recents: List<RecentItem>, onClick: (RecentItem) -> Unit, onRemove: (RecentItem) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        lazyItems(recents, key = { it.document.uri }) { item ->
            RecentCard(item, onClick = { onClick(item) }, onRemove = { onRemove(item) }, modifier = Modifier.animateItem())
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun OpenPdfCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_open_pdf_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_open_pdf_subtitle), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RecentsEmpty() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.home_recents_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
