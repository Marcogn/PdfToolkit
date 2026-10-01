package com.marcogn.pdftoolkit.ui.recents

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.marcogn.pdftoolkit.R

/** Drawer entry "Recent files": the same list as Home's section, as a column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    recents: List<RecentItem>?,
    onMenuClick: () -> Unit,
    onRecentClick: (RecentItem) -> Unit,
    onRecentRemove: (RecentItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_recents)) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                recents == null -> Unit
                recents.isEmpty() -> Text(
                    stringResource(R.string.home_recents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(recents, key = { it.document.uri }) { item -> RecentRow(item, onRecentClick, onRecentRemove) }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem, onClick: (RecentItem) -> Unit, onRemove: (RecentItem) -> Unit) {
    val document = item.document
    ListItem(
        headlineContent = { Text(document.displayName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Text(
                if (item.accessible) {
                    DateUtils.getRelativeTimeSpanString(document.lastOpenedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                } else {
                    stringResource(R.string.recent_unavailable)
                },
                color = if (item.accessible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        leadingContent = {
            RecentThumbnail(
                document.thumbnailPath,
                Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(4.dp)),
            )
        },
        trailingContent = {
            IconButton(onClick = { onRemove(item) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_recent_remove, document.displayName))
            }
        },
        modifier = Modifier.clickable { onClick(item) },
    )
}
