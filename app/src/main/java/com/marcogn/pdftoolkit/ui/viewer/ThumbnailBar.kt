package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails

private val ThumbnailHeight = 104.dp

/** Height of the bar: thumbnail, page number and padding. The scrubber moves up by this much while it is open. */
internal val ThumbnailBarHeight = 140.dp

/**
 * Strip of page thumbnails that slides up from the bottom (spec §4.2). Thumbnails are rendered
 * only for the items on screen, with placeholders of the right proportions meanwhile.
 */
@Composable
fun ThumbnailBar(
    pageSizes: List<PageSize>,
    currentPage: Int,
    thumbnails: PageThumbnails,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0))
    // Keep the current page in view while scrolling in the document.
    LaunchedEffect(currentPage) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == currentPage }) listState.animateScrollToItem((currentPage - 1).coerceAtLeast(0))
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pageSizes, key = { index, _ -> index }) { index, size ->
                Thumbnail(index, size, selected = index == currentPage, thumbnails, onClick = { onPageSelected(index) })
            }
        }
    }
}

@Composable
private fun Thumbnail(index: Int, size: PageSize, selected: Boolean, thumbnails: PageThumbnails, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, index, thumbnails) {
        value = thumbnails.get(index)?.asImageBitmap()
    }
    val description = stringResource(R.string.cd_thumbnail_page, index + 1)
    val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            border = border,
            color = Color.White,
            modifier = Modifier.height(ThumbnailHeight).aspectRatio(size.width / size.height),
        ) {
            bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds) }
        }
        Text(
            (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).background(Color.Transparent),
        )
    }
}
