package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Draw
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
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.FreehandOptions
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.pdf.annotations.MarkupFactory
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import com.marcogn.pdftoolkit.ui.common.PageIndicatorChip
import com.marcogn.pdftoolkit.ui.common.ReportCurrentPage
import com.marcogn.pdftoolkit.ui.edit.AnnotateDocuments
import com.marcogn.pdftoolkit.ui.edit.AnnotateLoad
import com.marcogn.pdftoolkit.ui.fill.FillPageContent
import com.marcogn.pdftoolkit.ui.fill.ToolButtonFrame

/**
 * What a gesture on a page does in the "Annotate" pane (spec §7.4): select text for a markup
 * [kind], draw with a [freehand] brush, or erase (neither).
 */
enum class AnnotateTool(val kind: MarkupKind?, val freehand: FreehandKind? = null) {
    HIGHLIGHT(MarkupKind.HIGHLIGHT),
    UNDERLINE(MarkupKind.UNDERLINE),
    STRIKEOUT(MarkupKind.STRIKEOUT),
    PEN(null, FreehandKind.PEN),
    MARKER(null, FreehandKind.HIGHLIGHTER),
    ERASER(null),
}

/**
 * UI state of "Annotate" that the edit session doesn't hold: the armed tool, the colour chosen for
 * highlights and for lines (underline and strikeout share one), and the colour and width of each
 * freehand brush, all as positions in [AnnotationPalette] and [FreehandOptions]. Survives rotation.
 */
@Stable
class AnnotatePaneState(
    tool: AnnotateTool = AnnotateTool.HIGHLIGHT,
    highlightColor: Int = 0,
    lineColor: Int = 0,
    penColor: Int = FreehandOptions.defaultColorIndex(FreehandKind.PEN),
    penWidth: Int = FreehandOptions.defaultWidthIndex(FreehandKind.PEN),
    markerColor: Int = FreehandOptions.defaultColorIndex(FreehandKind.HIGHLIGHTER),
    markerWidth: Int = FreehandOptions.defaultWidthIndex(FreehandKind.HIGHLIGHTER),
) {
    var tool by mutableStateOf(tool)
    var highlightColor by mutableIntStateOf(highlightColor)
    var lineColor by mutableIntStateOf(lineColor)
    var penColor by mutableIntStateOf(penColor)
    var penWidth by mutableIntStateOf(penWidth)
    var markerColor by mutableIntStateOf(markerColor)
    var markerWidth by mutableIntStateOf(markerWidth)

    /** The colour new annotations of [kind] get. */
    fun colorFor(kind: MarkupKind): AnnotationColor {
        val palette = AnnotationPalette.of(kind)
        val index = if (kind == MarkupKind.HIGHLIGHT) highlightColor else lineColor
        return palette[index.coerceIn(palette.indices)]
    }

    fun setColor(kind: MarkupKind, index: Int) {
        if (kind == MarkupKind.HIGHLIGHT) highlightColor = index else lineColor = index
    }

    private fun colorIndex(kind: FreehandKind) = if (kind == FreehandKind.PEN) penColor else markerColor
    private fun widthIndex(kind: FreehandKind) = if (kind == FreehandKind.PEN) penWidth else markerWidth

    /** The colour strokes of [kind] get. */
    fun colorFor(kind: FreehandKind): AnnotationColor =
        FreehandOptions.colors(kind).let { it[colorIndex(kind).coerceIn(it.indices)] }

    /** The brush size, in points of the page, of strokes of [kind]. */
    fun widthFor(kind: FreehandKind): Float =
        FreehandOptions.widths(kind).let { it[widthIndex(kind).coerceIn(it.indices)] }

    fun setColor(kind: FreehandKind, index: Int) {
        if (kind == FreehandKind.PEN) penColor = index else markerColor = index
    }

    fun setWidth(kind: FreehandKind, index: Int) {
        if (kind == FreehandKind.PEN) penWidth = index else markerWidth = index
    }

    companion object {
        val Saver = listSaver<AnnotatePaneState, Any>(
            save = { listOf(it.tool.name, it.highlightColor, it.lineColor, it.penColor, it.penWidth, it.markerColor, it.markerWidth) },
            restore = {
                AnnotatePaneState(AnnotateTool.valueOf(it[0] as String), it[1] as Int, it[2] as Int, it[3] as Int, it[4] as Int, it[5] as Int, it[6] as Int)
            },
        )
    }
}

@Composable
fun rememberAnnotatePaneState(initialTool: AnnotateTool = AnnotateTool.HIGHLIGHT): AnnotatePaneState =
    rememberSaveable(saver = AnnotatePaneState.Saver) { AnnotatePaneState(initialTool) }

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
 * and hold selects text; with a freehand tool a finger or a stylus draws (two fingers zoom and
 * pan); with the eraser a tap removes the annotation under the finger.
 */
@Composable
fun AnnotatePane(
    pages: List<PageItem>,
    edits: AnnotationEdits,
    load: AnnotateLoad?,
    state: AnnotatePaneState,
    selection: TextSelectionState,
    actions: AnnotateActions,
    initialPageId: String? = null,
    onPageChanged: (pageId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (load) {
        null, AnnotateLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        AnnotateLoad.Failed -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.annotate_load_failed), textAlign = TextAlign.Center)
        }
        is AnnotateLoad.Ready -> AnnotatePages(pages, edits, load.documents, state, selection, actions, initialPageId, onPageChanged, modifier)
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
    initialPageId: String?,
    onPageChanged: (pageId: String) -> Unit,
    modifier: Modifier,
) {
    val pagerState = rememberPagerState(initialPage = pages.indexOfFirst { it.id == initialPageId }.coerceAtLeast(0)) { pages.size }
    ReportCurrentPage(pagerState, pages, onPageChanged)
    ResolveTextSelection(selection) { key -> (pages.firstOrNull { it.id == key } as? PageItem.FromPdf)?.let { actions.pageText(it) } }
    Box(modifier.fillMaxSize()) {
        // Drawing takes every one-finger drag, so the pages don't turn under a freehand tool.
        HorizontalPager(pagerState, key = { pages[it].id }, userScrollEnabled = state.tool.freehand == null, modifier = Modifier.fillMaxSize()) { index ->
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
                brushColor = state.tool.freehand?.let(state::colorFor) ?: AnnotationColor.BLACK,
                brushWidth = state.tool.freehand?.let(state::widthFor) ?: 0f,
                // One ink layer at a time (the library's advice): only on the page that is shown.
                isCurrent = pagerState.settledPage == index,
                selection = selection,
                actions = actions,
                pageColor = Color.White,
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                selectionColor = MaterialTheme.colorScheme.primary,
            )
        }
        PageIndicatorChip(pagerState, pages.size, Modifier.align(Alignment.BottomCenter).padding(8.dp))
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
                    stringResource(
                        when {
                            kind != null -> R.string.annotate_hint_select
                            state.tool.freehand != null -> R.string.annotate_hint_draw
                            else -> R.string.annotate_hint_erase
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (kind != null) {
            ColorRow(AnnotationPalette.of(kind), state.colorFor(kind)) { state.setColor(kind, it) }
        }
        val brush = state.tool.freehand
        if (brush != null) {
            ColorRow(FreehandOptions.colors(brush), state.colorFor(brush)) { state.setColor(brush, it) }
            WidthRow(brush, state)
        }
        BottomAppBar {
            // Spread out when the tools fit, scrolling when they don't (six tools on a narrow phone).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
}

@Composable
private fun ColorRow(palette: List<AnnotationColor>, chosen: AnnotationColor, onPick: (Int) -> Unit) {
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
                        .clickable { onPick(index) }
                        .semantics {
                            this.selected = selected
                            contentDescription = name
                        },
                )
            }
        }
    }
}

/** The brush sizes of [kind]: a dot as wide as the stroke would be (capped to fit), one to pick. */
@Composable
private fun WidthRow(kind: FreehandKind, state: AnnotatePaneState) {
    val widths = FreehandOptions.widths(kind)
    val chosen = state.widthFor(kind)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            widths.forEachIndexed { index, width ->
                val selected = width == chosen
                val name = stringResource(R.string.annotate_width, width.toInt())
                Box(
                    Modifier
                        .size(WIDTH_CELL)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { state.setWidth(kind, index) }
                        .semantics {
                            this.selected = selected
                            contentDescription = name
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val dot = (width * WIDTH_DOT_PER_POINT).coerceIn(MIN_DOT, MAX_DOT)
                    Box(Modifier.size(dot.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                }
            }
        }
    }
}

private val SWATCH_SIZE = 32.dp
private val WIDTH_CELL = 40.dp
private const val WIDTH_DOT_PER_POINT = 1.2f
private const val MIN_DOT = 3f
private const val MAX_DOT = 30f
private val SELECTED_RIM = 3.dp
private val THIN_RIM = 1.dp

private fun AnnotateTool.labelRes(): Int = when (this) {
    AnnotateTool.HIGHLIGHT -> R.string.annotate_tool_highlight
    AnnotateTool.UNDERLINE -> R.string.annotate_tool_underline
    AnnotateTool.STRIKEOUT -> R.string.annotate_tool_strikeout
    AnnotateTool.PEN -> R.string.annotate_tool_pen
    AnnotateTool.MARKER -> R.string.annotate_tool_marker
    AnnotateTool.ERASER -> R.string.annotate_tool_eraser
}

private fun AnnotateTool.icon(): ImageVector = when (this) {
    AnnotateTool.HIGHLIGHT -> Icons.Outlined.BorderColor
    AnnotateTool.UNDERLINE -> Icons.Filled.FormatUnderlined
    AnnotateTool.STRIKEOUT -> Icons.Filled.FormatStrikethrough
    AnnotateTool.PEN -> Icons.Outlined.Draw
    AnnotateTool.MARKER -> Icons.Outlined.Brush
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
