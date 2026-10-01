package com.marcogn.pdftoolkit.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** What the page grid is for: pick pages to remove, or drag them into a new order (spec §6.3, §6.4). */
enum class PagesMode { REMOVE, REORDER }

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
    pageSizes: List<PageSize>,
    thumbnails: PageThumbnails,
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
                    page = page as PageItem.FromPdf,
                    number = index + 1,
                    pageSize = pageSizes[page.pageIndex],
                    thumbnails = thumbnails,
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
    page: PageItem.FromPdf,
    number: Int,
    pageSize: PageSize,
    thumbnails: PageThumbnails,
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
    val clickModifier = if (mode == PagesMode.REMOVE) {
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
            RotatedThumbnail(page, pageSize, thumbnails)
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

/**
 * The thumbnail of the source page, turned by the rotation the user added. At 90° and 270° the
 * cell is as tall as the page is wide, so the unrotated image is laid out swapped and then turned.
 */
@Composable
private fun RotatedThumbnail(page: PageItem.FromPdf, pageSize: PageSize, thumbnails: PageThumbnails) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, page.pageIndex, thumbnails) {
        value = thumbnails.get(page.pageIndex)?.asImageBitmap()
    }
    val sideways = page.rotation % HALF_TURN != 0
    val aspect = pageSize.width / pageSize.height // unrotated, width / height
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxWidth
        val cellHeight = if (sideways) cellWidth * aspect else cellWidth / aspect
        Box(Modifier.size(cellWidth, cellHeight), contentAlignment = Alignment.Center) {
            val imageModifier = if (sideways) Modifier.requiredSize(cellHeight, cellWidth) else Modifier.fillMaxWidth()
            Box(
                imageModifier.graphicsLayer { rotationZ = page.rotation.toFloat() },
            ) {
                bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.matchParentSize()) }
            }
        }
    }
}

private const val DRAG_SCALE = 1.05f
private const val HALF_TURN = 180
