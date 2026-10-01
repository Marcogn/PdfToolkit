package com.marcogn.pdftoolkit.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import android.graphics.Bitmap
import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.PageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * What the page grid is for: pick pages to remove, drag them into a new order (spec §6.3, §6.4), or
 * pick pages of another PDF to add (spec §6.2). [REMOVE] and [PICK] behave the same: a tap selects.
 */
enum class PagesMode { REMOVE, REORDER, PICK }

/** What the cell menu of a page in reorder mode can do. */
class PageActions(
    val onMoveToStart: (String) -> Unit,
    val onMoveToEnd: (String) -> Unit,
    val onMoveEarlier: (String) -> Unit,
    val onMoveLater: (String) -> Unit,
    val onRotate: (String) -> Unit,
)

private val CellMinWidth = 104.dp

/**
 * Thumbnails of the session's pages in a grid. In [PagesMode.REMOVE] a tap selects, in
 * [PagesMode.REORDER] a long press picks the page up (Reorderable library, Apache 2.0) and the
 * menu of each page offers the same moves without dragging (spec §9, accessibility).
 *
 * While dragging, the grid shows its own copy of the list and commits one move to the session on
 * release, so a whole drag is a single undo step.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesGrid(
    pages: List<PageItem>,
    sources: Map<DocRef, PageSource>,
    imageThumbnail: (uri: String) -> Bitmap?,
    mode: PagesMode,
    selection: Set<String>,
    onTap: (PageItem) -> Unit,
    onLongPress: (PageItem) -> Unit,
    onCommitMove: (from: Int, to: Int) -> Unit,
    actions: PageActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var working by remember { mutableStateOf<List<PageItem>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // The session answers the committed move on the next frame: until then keep showing the copy.
    LaunchedEffect(pages) { working = null }
    val shown = working ?: pages

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val list = (working ?: pages).toMutableList()
        list.add(to.index, list.removeAt(from.index))
        working = list
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(CellMinWidth),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        itemsIndexed(shown, key = { _, page -> page.id }) { index, page ->
            ReorderableItem(reorderState, key = page.id, enabled = mode == PagesMode.REORDER) { isDragging ->
                val dragModifier = if (mode == PagesMode.REORDER) {
                    Modifier.longPressDraggableHandle(
                        onDragStarted = {
                            draggingId = page.id
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        },
                        onDragStopped = {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            val id = draggingId
                            val current = working
                            draggingId = null
                            if (id != null && current != null) {
                                val from = pages.indexOfFirst { it.id == id }
                                val to = current.indexOfFirst { it.id == id }
                                if (from >= 0 && to >= 0 && from != to) onCommitMove(from, to) else working = null
                            }
                        },
                    )
                } else {
                    Modifier
                }
                PageCell(
                    page = page,
                    number = index + 1,
                    sources = sources,
                    imageThumbnail = imageThumbnail,
                    selected = page.id in selection,
                    mode = mode,
                    isDragging = isDragging,
                    isFirst = index == 0,
                    isLast = index == shown.lastIndex,
                    actions = actions,
                    onTap = { onTap(page) },
                    onLongPress = { onLongPress(page) },
                    modifier = dragModifier,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageCell(
    page: PageItem,
    number: Int,
    sources: Map<DocRef, PageSource>,
    imageThumbnail: (uri: String) -> Bitmap?,
    selected: Boolean,
    mode: PagesMode,
    isDragging: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    actions: PageActions,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_edit_page, number, page.rotation)
    val moveToStart = stringResource(R.string.edit_move_to_start)
    val moveToEnd = stringResource(R.string.edit_move_to_end)
    val moveEarlier = stringResource(R.string.edit_move_earlier)
    val moveLater = stringResource(R.string.edit_move_later)
    val rotate = stringResource(R.string.edit_rotate)
    val semanticsModifier = Modifier.semantics {
        contentDescription = description
        this.selected = selected
        if (mode == PagesMode.REORDER) {
            customActions = buildList {
                if (!isFirst) {
                    add(CustomAccessibilityAction(moveToStart) { actions.onMoveToStart(page.id); true })
                    add(CustomAccessibilityAction(moveEarlier) { actions.onMoveEarlier(page.id); true })
                }
                if (!isLast) {
                    add(CustomAccessibilityAction(moveLater) { actions.onMoveLater(page.id); true })
                    add(CustomAccessibilityAction(moveToEnd) { actions.onMoveToEnd(page.id); true })
                }
                add(CustomAccessibilityAction(rotate) { actions.onRotate(page.id); true })
            }
        }
    }
    val clickModifier = if (mode != PagesMode.REORDER) {
        Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .graphicsLayer {
                val scale = if (isDragging) DRAG_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (isDragging) 8.dp else 0.dp, MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .then(clickModifier),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PageThumbnail(page, sources, imageThumbnail)
        }
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(3.dp),
            )
        }
        if (mode == PagesMode.REORDER) {
            PageMenu(number, isFirst, isLast, page.id, actions, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun PageMenu(number: Int, isFirst: Boolean, isLast: Boolean, id: String, actions: PageActions, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_edit_page_menu, number),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(2.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!isFirst) {
                MenuItem(R.string.edit_move_to_start) { actions.onMoveToStart(id) }
                MenuItem(R.string.edit_move_earlier) { actions.onMoveEarlier(id) }
            }
            if (!isLast) {
                MenuItem(R.string.edit_move_later) { actions.onMoveLater(id) }
                MenuItem(R.string.edit_move_to_end) { actions.onMoveToEnd(id) }
            }
            MenuItem(R.string.edit_rotate) { actions.onRotate(id) }
        }
    }
}

@Composable
private fun MenuItem(labelRes: Int, onClick: () -> Unit) {
    // The menu closes itself when the list changes (the cell may move), so no explicit dismiss.
    DropdownMenuItem(text = { Text(stringResource(labelRes)) }, onClick = onClick)
}

/** The thumbnail of any kind of page, drawn in its added rotation. */
@Composable
private fun PageThumbnail(page: PageItem, sources: Map<DocRef, PageSource>, imageThumbnail: (uri: String) -> Bitmap?) {
    when (page) {
        is PageItem.FromPdf -> {
            val source = sources[page.docRef]
            val size = source?.pageSizes?.getOrNull(page.pageIndex)
            val bitmap by produceState<ImageBitmap?>(initialValue = null, page.pageIndex, source) {
                value = source?.thumbnails?.get(page.pageIndex)?.asImageBitmap()
            }
            RotatedThumbnail(page.rotation, size?.let { it.width / it.height } ?: A4_ASPECT) {
                bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.matchParentSize()) }
            }
        }
        is PageItem.Blank -> RotatedThumbnail(page.rotation, page.widthPt / page.heightPt) {
            Box(Modifier.matchParentSize().background(Color.White))
        }
        is PageItem.FromImage -> {
            val bitmap by produceState<ImageBitmap?>(initialValue = null, page.imageUri) {
                value = withContext(Dispatchers.IO) { imageThumbnail(page.imageUri) }?.asImageBitmap()
            }
            RotatedThumbnail(page.rotation, page.widthPt / page.heightPt) {
                Box(Modifier.matchParentSize().background(Color.White)) {
                    // Same placement as the saved page: fitted and centred inside it.
                    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
                }
            }
        }
    }
}

/**
 * [content] is the unrotated page, turned by [rotation]. At 90° and 270° the cell is as tall as the
 * page is wide, so the unrotated page is laid out swapped and then turned. [aspect] is the unrotated
 * width / height.
 */
@Composable
private fun RotatedThumbnail(rotation: Int, aspect: Float, content: @Composable BoxScope.() -> Unit) {
    val sideways = rotation % HALF_TURN != 0
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxWidth
        val cellHeight = if (sideways) cellWidth * aspect else cellWidth / aspect
        Box(Modifier.size(cellWidth, cellHeight), contentAlignment = Alignment.Center) {
            val pageModifier = if (sideways) Modifier.requiredSize(cellHeight, cellWidth) else Modifier.fillMaxSize()
            Box(pageModifier.graphicsLayer { rotationZ = rotation.toFloat() }, content = content)
        }
    }
}

private const val DRAG_SCALE = 1.05f
private const val HALF_TURN = 180
private const val A4_ASPECT = 595f / 842f
