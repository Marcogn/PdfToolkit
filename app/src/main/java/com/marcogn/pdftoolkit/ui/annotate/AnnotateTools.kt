package com.marcogn.pdftoolkit.ui.annotate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationPalette
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.FreehandOptions
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.ToolStyleSettings

/**
 * What a gesture on a page does with the annotation tools of the viewer (spec §7.4): select text for a markup
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
 * UI state of the annotation tools that the edit session doesn't hold: the armed tool, the colour chosen for
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
    partialEraser: Boolean = false,
    eraserWidth: Int = FreehandOptions.defaultWidthIndex(FreehandKind.PEN),
) {
    var tool by mutableStateOf(tool)
    var highlightColor by mutableIntStateOf(highlightColor)
    var lineColor by mutableIntStateOf(lineColor)
    var penColor by mutableIntStateOf(penColor)
    var penWidth by mutableIntStateOf(penWidth)
    var markerColor by mutableIntStateOf(markerColor)
    var markerWidth by mutableIntStateOf(markerWidth)

    /** The eraser takes only what the finger passes over (plan T-a), not whole annotations. */
    var partialEraser by mutableStateOf(partialEraser)

    /** The partial eraser's size, a position in the pen's sizes ([eraserWidths]). */
    var eraserWidth by mutableIntStateOf(eraserWidth)

    /** The text markup tool last used, which the "Highlight" button of the viewer arms again. */
    var markupTool by mutableStateOf(AnnotateTool.HIGHLIGHT)
        private set

    /** The brush last used, which the "Draw" button of the viewer arms again. */
    var brushTool by mutableStateOf(AnnotateTool.PEN)
        private set

    init {
        choose(tool)
    }

    /** Arms [tool] and remembers it as the one of its group. */
    fun choose(tool: AnnotateTool) {
        this.tool = tool
        when {
            tool.kind != null -> markupTool = tool
            tool.freehand != null -> brushTool = tool
        }
    }

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

    /** What is kept across restarts (plan T-b). */
    fun style() = ToolStyleSettings(
        highlightColor, lineColor, penColor, penWidth, markerColor, markerWidth, partialEraser, eraserWidth,
        markupTool.drawerTool(false), brushTool.drawerTool(false),
    )

    /** Takes the kept [style] (colours, sizes, eraser mode, last tool of each group) without arming anything. */
    fun restore(style: ToolStyleSettings) {
        highlightColor = style.highlightColor
        lineColor = style.lineColor
        penColor = style.penColor
        penWidth = style.penWidth
        markerColor = style.markerColor
        markerWidth = style.markerWidth
        partialEraser = style.partialEraser
        eraserWidth = style.eraserWidth
        style.lastMarkup.annotateTool()?.takeIf { it.kind != null }?.let { markupTool = it }
        style.lastBrush.annotateTool()?.takeIf { it.freehand != null }?.let { brushTool = it }
        // The tool already armed (opened from Home on Draw...) is the last of its group, whatever was kept.
        if (tool.kind != null || tool.freehand != null) choose(tool)
    }

    /** How wide the partial eraser is, in points of the page: one of the pen's sizes, 2 pt at first (plan T-a). */
    val eraserSize: Float get() = eraserWidths.let { it[eraserWidth.coerceIn(it.indices)] }

    companion object {
        /** The partial eraser's sizes: the pen's (author, plan T-a). */
        val eraserWidths: List<Float> = FreehandOptions.widths(FreehandKind.PEN)

        val Saver = listSaver<AnnotatePaneState, Any>(
            save = {
                listOf(
                    it.tool.name, it.highlightColor, it.lineColor, it.penColor, it.penWidth, it.markerColor, it.markerWidth,
                    it.markupTool.name, it.brushTool.name, it.partialEraser, it.eraserWidth,
                )
            },
            restore = {
                AnnotatePaneState(
                    AnnotateTool.valueOf(it[0] as String), it[1] as Int, it[2] as Int, it[3] as Int, it[4] as Int, it[5] as Int, it[6] as Int,
                    // Absent from a state saved before plan T-a.
                    it.getOrNull(9) as? Boolean ?: false,
                    it.getOrNull(10) as? Int ?: FreehandOptions.defaultWidthIndex(FreehandKind.PEN),
                )
                    .also { state ->
                        state.choose(AnnotateTool.valueOf(it[7] as String))
                        state.choose(AnnotateTool.valueOf(it[8] as String))
                        state.tool = AnnotateTool.valueOf(it[0] as String)
                    }
            },
        )
    }
}

@Composable
fun rememberAnnotatePaneState(initialTool: AnnotateTool = AnnotateTool.HIGHLIGHT): AnnotatePaneState =
    rememberSaveable(saver = AnnotatePaneState.Saver) { AnnotatePaneState().also { it.choose(initialTool) } }

/**
 * What the viewer tells the reader when this tool is armed; shown over the page for a few seconds (plan U7).
 * [partialEraser]: the eraser takes only what the finger passes over (plan T-a).
 */
@Composable
fun AnnotateTool.hint(partialEraser: Boolean = false): String = stringResource(
    when {
        kind != null -> R.string.annotate_hint_select
        freehand != null -> R.string.annotate_hint_draw
        partialEraser -> R.string.annotate_hint_erase_partial
        else -> R.string.annotate_hint_erase
    },
)

/**
 * The bubble of a tool (plan T-b): its name, its colours, the size of a brush and of the partial eraser. Opens on a
 * hold in the drawer or a tap on the armed quick tool; replaces the old Style button. Choices go straight to
 * [state]. A tool with nothing to choose (stroke eraser, fill) shows just its name.
 */
@Composable
fun ToolBubble(tool: DrawerTool, state: AnnotatePaneState, expanded: Boolean, onDismiss: () -> Unit) {
    val annotate = tool.annotateTool()
    val kind = annotate?.kind
    val brush = annotate?.freehand
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(tool.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            when {
                kind != null -> ColorRow(AnnotationPalette.of(kind), state.colorFor(kind)) { state.setColor(kind, it) }
                brush != null -> {
                    ColorRow(FreehandOptions.colors(brush), state.colorFor(brush)) { state.setColor(brush, it) }
                    SizeRow(FreehandOptions.widths(brush), state.widthFor(brush)) { state.setWidth(brush, it) }
                }
                tool == DrawerTool.ERASER_PARTIAL -> SizeRow(AnnotatePaneState.eraserWidths, state.eraserSize) { state.eraserWidth = it }
            }
        }
    }
}

/** The annotation tool a drawer tool arms, or null for "Fill and sign", which has its own strip. */
fun DrawerTool.annotateTool(): AnnotateTool? = when (this) {
    DrawerTool.HIGHLIGHT -> AnnotateTool.HIGHLIGHT
    DrawerTool.UNDERLINE -> AnnotateTool.UNDERLINE
    DrawerTool.STRIKEOUT -> AnnotateTool.STRIKEOUT
    DrawerTool.PEN -> AnnotateTool.PEN
    DrawerTool.MARKER -> AnnotateTool.MARKER
    DrawerTool.ERASER_STROKE, DrawerTool.ERASER_PARTIAL -> AnnotateTool.ERASER
    DrawerTool.FILL -> null
}

/** The drawer tool [this] is, with the eraser's mode deciding between the two erasers. */
fun AnnotateTool.drawerTool(partialEraser: Boolean): DrawerTool = when (this) {
    AnnotateTool.HIGHLIGHT -> DrawerTool.HIGHLIGHT
    AnnotateTool.UNDERLINE -> DrawerTool.UNDERLINE
    AnnotateTool.STRIKEOUT -> DrawerTool.STRIKEOUT
    AnnotateTool.PEN -> DrawerTool.PEN
    AnnotateTool.MARKER -> DrawerTool.MARKER
    AnnotateTool.ERASER -> if (partialEraser) DrawerTool.ERASER_PARTIAL else DrawerTool.ERASER_STROKE
}

/** Arms [tool] (not "Fill and sign"): the eraser's mode is part of the tool. */
fun AnnotatePaneState.arm(tool: DrawerTool) {
    val annotate = tool.annotateTool() ?: return
    if (annotate == AnnotateTool.ERASER) partialEraser = tool == DrawerTool.ERASER_PARTIAL
    choose(annotate)
}

/** A round 48 dp choice in a row of the bubble, filled when [selected]. */
@Composable
private fun ChoiceButton(selected: Boolean, name: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = name
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun ColorRow(palette: List<AnnotationColor>, chosen: AnnotationColor, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        palette.forEachIndexed { index, color ->
            val selected = color == chosen
            val name = stringResource(color.nameRes())
            // 48 dp to touch, a smaller swatch drawn inside (plan U19).
            Box(
                Modifier
                    .size(TOUCH_TARGET)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(index) }
                    .semantics {
                        this.selected = selected
                        contentDescription = name
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(SWATCH_SIZE)
                        .clip(CircleShape)
                        .background(Color(color.red, color.green, color.blue))
                        .border(
                            BorderStroke(if (selected) SELECTED_RIM else THIN_RIM, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            CircleShape,
                        ),
                )
            }
        }
    }
}

/** Sizes in points of the page: a dot as wide as the stroke would be (capped to fit), one to pick. */
@Composable
internal fun SizeRow(widths: List<Float>, chosen: Float, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        widths.forEachIndexed { index, width ->
            ChoiceButton(selected = width == chosen, name = stringResource(R.string.annotate_width, width.toInt()), onClick = { onPick(index) }) {
                val dot = (width * WIDTH_DOT_PER_POINT).coerceIn(MIN_DOT, MAX_DOT)
                Box(Modifier.size(dot.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

private val SWATCH_SIZE = 32.dp

/** Every touch target is at least this big, whatever is drawn inside (plan U19). */
private val TOUCH_TARGET = 48.dp
private const val WIDTH_DOT_PER_POINT = 1.2f
private const val MIN_DOT = 3f
private const val MAX_DOT = 30f
private val SELECTED_RIM = 3.dp
private val THIN_RIM = 1.dp

internal fun DrawerTool.labelRes(): Int = when (this) {
    DrawerTool.HIGHLIGHT -> R.string.annotate_tool_highlight
    DrawerTool.UNDERLINE -> R.string.annotate_tool_underline
    DrawerTool.STRIKEOUT -> R.string.annotate_tool_strikeout
    DrawerTool.PEN -> R.string.annotate_tool_pen
    DrawerTool.MARKER -> R.string.annotate_tool_marker
    DrawerTool.ERASER_STROKE -> R.string.annotate_tool_eraser
    DrawerTool.ERASER_PARTIAL -> R.string.annotate_eraser_partial_short
    DrawerTool.FILL -> R.string.viewer_tool_fill
}

internal fun DrawerTool.icon(): ImageVector = when (this) {
    DrawerTool.HIGHLIGHT -> Icons.Outlined.BorderColor
    DrawerTool.UNDERLINE -> Icons.Filled.FormatUnderlined
    DrawerTool.STRIKEOUT -> Icons.Filled.FormatStrikethrough
    DrawerTool.PEN -> Icons.Outlined.Draw
    DrawerTool.MARKER -> Icons.Outlined.Brush
    DrawerTool.ERASER_STROKE -> Icons.Outlined.AutoFixNormal
    DrawerTool.ERASER_PARTIAL -> Icons.Outlined.ContentCut
    DrawerTool.FILL -> Icons.Outlined.EditNote
}

internal fun AnnotateTool.labelRes(): Int = when (this) {
    AnnotateTool.HIGHLIGHT -> R.string.annotate_tool_highlight
    AnnotateTool.UNDERLINE -> R.string.annotate_tool_underline
    AnnotateTool.STRIKEOUT -> R.string.annotate_tool_strikeout
    AnnotateTool.PEN -> R.string.annotate_tool_pen
    AnnotateTool.MARKER -> R.string.annotate_tool_marker
    AnnotateTool.ERASER -> R.string.annotate_tool_eraser
}

internal fun AnnotateTool.icon(): ImageVector = when (this) {
    AnnotateTool.HIGHLIGHT -> Icons.Outlined.BorderColor
    AnnotateTool.UNDERLINE -> Icons.Filled.FormatUnderlined
    AnnotateTool.STRIKEOUT -> Icons.Filled.FormatStrikethrough
    AnnotateTool.PEN -> Icons.Outlined.Draw
    AnnotateTool.MARKER -> Icons.Outlined.Brush
    AnnotateTool.ERASER -> Icons.Outlined.AutoFixNormal
}

/** The label of the button that applies [this] kind to the selected text. */
internal fun MarkupKind.applyLabel(): Int = when (this) {
    MarkupKind.HIGHLIGHT -> R.string.annotate_apply_highlight
    MarkupKind.UNDERLINE -> R.string.annotate_apply_underline
    MarkupKind.STRIKEOUT -> R.string.annotate_apply_strikeout
    MarkupKind.SQUIGGLY -> R.string.annotate_apply_underline
}

/** What the snackbar says after the selected text was marked up as this kind. */
internal fun MarkupKind.appliedLabel(): Int = when (this) {
    MarkupKind.HIGHLIGHT -> R.string.annotate_applied_highlight
    MarkupKind.UNDERLINE -> R.string.annotate_applied_underline
    MarkupKind.STRIKEOUT -> R.string.annotate_applied_strikeout
    MarkupKind.SQUIGGLY -> R.string.annotate_applied_underline
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
