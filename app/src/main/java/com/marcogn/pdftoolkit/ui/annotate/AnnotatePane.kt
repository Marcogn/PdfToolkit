package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationEdits
import com.marcogn.pdftoolkit.domain.annotate.AnnotationPalette
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.pdf.annotations.MarkupFactory
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import com.marcogn.pdftoolkit.ui.edit.AnnotateDocuments
import com.marcogn.pdftoolkit.ui.edit.AnnotateLoad
import com.marcogn.pdftoolkit.ui.fill.FillPageContent
import com.marcogn.pdftoolkit.ui.fill.ToolButtonFrame

/** What a gesture on a page does in the "Annotate" pane (spec §7.4). */
enum class AnnotateTool(val kind: MarkupKind?) {
    HIGHLIGHT(MarkupKind.HIGHLIGHT),
    UNDERLINE(MarkupKind.UNDERLINE),
    STRIKEOUT(MarkupKind.STRIKEOUT),
    ERASER(null),
}

/**
 * UI state of "Annotate" that the edit session doesn't hold: the armed tool and the colour chosen
 * for highlights and for lines (underline and strikeout share one), as positions in
 * [AnnotationPalette]. Survives rotation.
 */
@Stable
class AnnotatePaneState(tool: AnnotateTool = AnnotateTool.HIGHLIGHT, highlightColor: Int = 0, lineColor: Int = 0) {
    var tool by mutableStateOf(tool)
    var highlightColor by mutableIntStateOf(highlightColor)
    var lineColor by mutableIntStateOf(lineColor)

    /** The colour new annotations of [kind] get. */
    fun colorFor(kind: MarkupKind): AnnotationColor {
        val palette = AnnotationPalette.of(kind)
        val index = if (kind == MarkupKind.HIGHLIGHT) highlightColor else lineColor
        return palette[index.coerceIn(palette.indices)]
    }

    fun setColor(kind: MarkupKind, index: Int) {
        if (kind == MarkupKind.HIGHLIGHT) highlightColor = index else lineColor = index
    }

    companion object {
        val Saver = listSaver<AnnotatePaneState, Any>(
            save = { listOf(it.tool.name, it.highlightColor, it.lineColor) },
            restore = { AnnotatePaneState(AnnotateTool.valueOf(it[0] as String), it[1] as Int, it[2] as Int) },
        )
    }
}

@Composable
fun rememberAnnotatePaneState(): AnnotatePaneState = rememberSaveable(saver = AnnotatePaneState.Saver) { AnnotatePaneState() }

/** What the pane asks of the edit screen. */
interface AnnotateActions : FillPageContent {
    fun addAnnotation(annotation: NewAnnotation)
    fun removeAnnotation(id: String)
    fun removeExistingAnnotation(ref: AnnotationRef)
    fun newAnnotationId(): String

    /** The text of a page as its source shows it; null if it can't be read. */
    suspend fun pageText(item: PageItem.FromPdf): TextSelection?
    fun message(text: String)
}

/**
 * "Annotate" (spec §7.4): the session's pages one at a time, each zoomable, with the annotations
 * already in the files and the ones added in this session drawn on top. With a markup tool a press
 * and hold selects text; with the eraser a tap removes the annotation under the finger.
 */
@Composable
fun AnnotatePane(
    pages: List<PageItem>,
    edits: AnnotationEdits,
    load: AnnotateLoad?,
    state: AnnotatePaneState,
    selection: TextSelectionState,
    actions: AnnotateActions,
    modifier: Modifier = Modifier,
) {
    when (load) {
        null, AnnotateLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        AnnotateLoad.Failed -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.annotate_load_failed), textAlign = TextAlign.Center)
        }
        is AnnotateLoad.Ready -> AnnotatePages(pages, edits, load.documents, state, selection, actions, modifier)
    }
}

@Composable
private fun AnnotatePages(
    pages: List<PageItem>,
    edits: AnnotationEdits,
    documents: AnnotateDocuments,
    state: AnnotatePaneState,
    selection: TextSelectionState,
    actions: AnnotateActions,
    modifier: Modifier,
) {
    val pagerState = rememberPagerState { pages.size }
    ResolveTextSelection(selection) { key -> (pages.firstOrNull { it.id == key } as? PageItem.FromPdf)?.let { actions.pageText(it) } }
    Box(modifier.fillMaxSize()) {
        HorizontalPager(pagerState, key = { pages[it].id }, modifier = Modifier.fillMaxSize()) { index ->
            val page = pages[index]
            val space = documents.space(page)
            val sourceSpace = documents.sourceSpace(page)
            if (space == null || sourceSpace == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@HorizontalPager
            }
            AnnotatePage(
                item = page,
                space = space,
                sourceSpace = sourceSpace,
                existing = documents.existingOn(page),
                edits = edits,
                tool = state.tool,
                selection = selection,
                actions = actions,
                pageColor = Color.White,
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                selectionColor = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
        ) {
            Text(
                stringResource(R.string.fill_page_indicator, pagerState.currentPage + 1, pages.size),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Puts the selected text's annotation into the session: [kind] in the colour [state] has for it,
 * on the page the selection is on. The selection is cleared.
 */
fun applySelection(
    kind: MarkupKind,
    state: AnnotatePaneState,
    selection: TextSelectionState,
    pages: List<PageItem>,
    documents: AnnotateDocuments?,
    actions: AnnotateActions,
) {
    val page = pages.firstOrNull { it.id == selection.key }
    val sourceSpace: PdfPageSpace? = page?.let { documents?.sourceSpace(it) }
    if (page != null && sourceSpace != null) {
        MarkupFactory.build(actions.newAnnotationId(), page.id, kind, state.colorFor(kind), selection.runs, sourceSpace)
            ?.let(actions::addAnnotation)
    }
    selection.clear()
}

/**
 * The tool bar of the pane, in the screen's bottom bar: what to do with the selection, the
 * colours of the armed tool, and the tools. [onApply] puts the selection into the document.
 */
@Composable
fun AnnotateToolBar(state: AnnotatePaneState, selection: TextSelectionState, onApply: (MarkupKind) -> Unit) {
    val kind = state.tool.kind
    Column {
        if (kind != null && selection.isActive) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = selection::clear) { Text(stringResource(R.string.annotate_cancel)) }
                    Button(onClick = { onApply(kind) }) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(kind.applyLabel()), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(if (kind == null) R.string.annotate_hint_erase else R.string.annotate_hint_select),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (kind != null) ColorRow(kind, state)
        BottomAppBar {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                for (tool in AnnotateTool.entries) {
                    ToolButtonFrame(tool.labelRes(), state.tool == tool, onClick = {
                        if (state.tool != tool) selection.clear()
                        state.tool = tool
                    }) { Icon(tool.icon(), contentDescription = null) }
                }
            }
        }
    }
}

@Composable
private fun ColorRow(kind: MarkupKind, state: AnnotatePaneState) {
    val palette = AnnotationPalette.of(kind)
    val chosen = state.colorFor(kind)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            palette.forEachIndexed { index, color ->
                val selected = color == chosen
                val name = stringResource(color.nameRes())
                Box(
                    Modifier
                        .size(SWATCH_SIZE)
                        .clip(CircleShape)
                        .background(Color(color.red, color.green, color.blue))
                        .border(
                            BorderStroke(if (selected) SELECTED_RIM else THIN_RIM, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            CircleShape,
                        )
                        .clickable { state.setColor(kind, index) }
                        .semantics {
                            this.selected = selected
                            contentDescription = name
                        },
                )
            }
        }
    }
}

private val SWATCH_SIZE = 32.dp
private val SELECTED_RIM = 3.dp
private val THIN_RIM = 1.dp

private fun AnnotateTool.labelRes(): Int = when (this) {
    AnnotateTool.HIGHLIGHT -> R.string.annotate_tool_highlight
    AnnotateTool.UNDERLINE -> R.string.annotate_tool_underline
    AnnotateTool.STRIKEOUT -> R.string.annotate_tool_strikeout
    AnnotateTool.ERASER -> R.string.annotate_tool_eraser
}

private fun AnnotateTool.icon(): ImageVector = when (this) {
    AnnotateTool.HIGHLIGHT -> Icons.Outlined.BorderColor
    AnnotateTool.UNDERLINE -> Icons.Filled.FormatUnderlined
    AnnotateTool.STRIKEOUT -> Icons.Filled.FormatStrikethrough
    AnnotateTool.ERASER -> Icons.Outlined.AutoFixNormal
}

private fun MarkupKind.applyLabel(): Int = when (this) {
    MarkupKind.HIGHLIGHT -> R.string.annotate_apply_highlight
    MarkupKind.UNDERLINE -> R.string.annotate_apply_underline
    MarkupKind.STRIKEOUT -> R.string.annotate_apply_strikeout
    MarkupKind.SQUIGGLY -> R.string.annotate_apply_underline
}

private fun AnnotationColor.nameRes(): Int = when (this) {
    AnnotationColor.YELLOW -> R.string.color_yellow
    AnnotationColor.GREEN -> R.string.color_green
    AnnotationColor.BLUE -> R.string.color_blue
    AnnotationColor.PINK -> R.string.color_pink
    AnnotationColor.ORANGE -> R.string.color_orange
    AnnotationColor.RED -> R.string.color_red
    AnnotationColor.DARK_BLUE -> R.string.color_dark_blue
    AnnotationColor.BLACK -> R.string.color_black
    AnnotationColor.DARK_GREEN -> R.string.color_dark_green
    else -> R.string.color_other
}
