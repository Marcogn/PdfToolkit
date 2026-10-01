package com.marcogn.pdftoolkit.ui.merge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.ui.viewer.takePersistableAccess
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PDF_MIME = "application/pdf"
private const val MIN_FILES = 2

/**
 * The merge list (spec §6.6): PDFs with a first-page preview, name and page count, reordered by
 * dragging the handle (or from the accessibility actions), removed by swiping, extended with "+".
 *
 * @param onMerge [uris] in order; [edit] true for "Merge and edit" (opens the edit hub on the merged
 * pages), false for "Merge" (asks where to save straight away).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    onBack: () -> Unit,
    onMerge: (uris: List<String>, edit: Boolean) -> Unit,
    viewModel: MergeViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { context.takePersistableAccess(it) }
        viewModel.add(uris)
    }
    val addFiles = { addPicker.launch(arrayOf(PDF_MIME)) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    when (event) {
                        is MergeEvent.Protected -> resources.getString(R.string.merge_protected, event.name)
                        is MergeEvent.Unreadable -> resources.getString(R.string.merge_unreadable, event.name)
                    },
                )
            }
        }
    }

    val canMerge = entries.size >= MIN_FILES && !loading
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.tool_merge)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = addFiles) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.merge_add))
                        }
                    },
                )
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.any { it.hasForm }) {
                        Text(
                            stringResource(R.string.merge_form_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entries.size < MIN_FILES) {
                        Text(
                            stringResource(R.string.merge_need_two),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { onMerge(entries.map { it.uri }, true) },
                            enabled = canMerge,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.merge_and_edit), textAlign = TextAlign.Center) }
                        Button(
                            onClick = { onMerge(entries.map { it.uri }, false) },
                            enabled = canMerge,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.merge_action)) }
                    }
                }
            }
        },
    ) { padding ->
        if (entries.isEmpty() && !loading) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.merge_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = addFiles) { Text(stringResource(R.string.merge_add)) }
            }
        } else {
            MergeList(entries, onMove = viewModel::move, onRemove = viewModel::remove, modifier = Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeList(
    entries: List<MergeEntry>,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val moveUp = stringResource(R.string.merge_move_up)
    val moveDown = stringResource(R.string.merge_move_down)
    val remove = stringResource(R.string.merge_remove)

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            ReorderableItem(reorderState, key = entry.id) { isDragging ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.Settled) {
                            false
                        } else {
                            onRemove(entry.id)
                            true
                        }
                    },
                )
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                    },
                ) {
                    Card(
                        Modifier.semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index, index - 1); true })
                                if (index < entries.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(index, index + 1); true })
                                add(CustomAccessibilityAction(remove) { onRemove(entry.id); true })
                            }
                        },
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 1.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Thumbnail(entry)
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 2, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    pluralStringResource(R.plurals.edit_hub_subtitle, entry.pageCount, entry.pageCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.merge_drag_handle),
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                ).padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(entry: MergeEntry) {
    Box(
        Modifier.size(width = 48.dp, height = 64.dp).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        entry.thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
