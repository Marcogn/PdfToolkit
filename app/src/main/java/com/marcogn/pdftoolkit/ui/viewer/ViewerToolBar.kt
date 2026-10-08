package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.ui.annotate.AnnotateTool
import com.marcogn.pdftoolkit.ui.annotate.AnnotatePaneState
import com.marcogn.pdftoolkit.ui.annotate.StyleButton
import com.marcogn.pdftoolkit.ui.annotate.icon
import com.marcogn.pdftoolkit.ui.annotate.labelRes
import com.marcogn.pdftoolkit.ui.common.ToolStrip
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.fill.ToolButtonFrame

/** The three families of page tools the bar arms: text markup, freehand drawing and the eraser (plan V-b). */
enum class ViewerToolGroup {
    MARKUP,
    DRAW,
    ERASER,
}

/** The family this tool belongs to. */
fun AnnotateTool.group(): ViewerToolGroup = when {
    kind != null -> ViewerToolGroup.MARKUP
    freehand != null -> ViewerToolGroup.DRAW
    else -> ViewerToolGroup.ERASER
}

/** The tool a tap on the button of [group] arms: the one of that family used last. */
fun AnnotatePaneState.toolOf(group: ViewerToolGroup): AnnotateTool = when (group) {
    ViewerToolGroup.MARKUP -> markupTool
    ViewerToolGroup.DRAW -> brushTool
    ViewerToolGroup.ERASER -> AnnotateTool.ERASER
}

/**
 * The tools of the viewer (plans V-b, V-c), as a bar at the bottom of the screen or, with [side], a rail at
 * its end (plan U18): Highlight, Draw and Eraser arm a page tool, Fill arms "Fill and sign", Pages opens the
 * edit screen (which asks to save first when the viewer has changes). A tap on the button of the armed
 * family puts the tool down. With a markup or drawing tool armed, the Style button (colour, size and the
 * other tool of its family) sits at the end where the thumb is, and so do Undo and Redo once there is
 * something to undo. While [filling], the strip holds Fill (armed) and the [fillTools] instead.
 *
 * @param armed the tool of [state] is in effect (the bar may show without one).
 * @param editable the document can take page tools; otherwise those buttons are dimmed and a tap on them
 * only explains why ([onGroup] and [onFill] decide what to say).
 */
@Composable
fun ViewerToolBar(
    state: AnnotatePaneState,
    armed: Boolean,
    editable: Boolean,
    undoRedo: UndoRedo?,
    side: Boolean,
    onGroup: (ViewerToolGroup) -> Unit,
    filling: Boolean,
    onFill: () -> Unit,
    onPages: () -> Unit,
    modifier: Modifier = Modifier,
    fillTools: @Composable () -> Unit = {},
) {
    ToolStrip(
        side = side,
        undoRedo = undoRedo,
        modifier = modifier.then(if (side) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()),
        trailing = { if (armed && !filling) StyleButton(state) },
    ) {
        if (filling) {
            ToolButtonFrame(R.string.viewer_tool_fill, true, onClick = onFill) {
                Icon(Icons.Outlined.EditNote, contentDescription = null)
            }
            fillTools()
        } else {
            for (group in ViewerToolGroup.entries) {
                val tool = state.toolOf(group)
                Box(Modifier.alpha(if (editable) 1f else DIMMED)) {
                    // Named after the tool of the family that a tap arms (Underline, Marker...), so the button says what it does.
                    ToolButtonFrame(tool.labelRes(), armed && state.tool.group() == group, onClick = { onGroup(group) }) {
                        Icon(tool.icon(), contentDescription = null)
                    }
                }
            }
            Box(Modifier.alpha(if (editable) 1f else DIMMED)) {
                ToolButtonFrame(R.string.viewer_tool_fill, false, onClick = onFill) {
                    Icon(Icons.Outlined.EditNote, contentDescription = null)
                }
            }
            ToolButtonFrame(R.string.viewer_tool_pages, false, onClick = onPages) {
                Icon(Icons.Outlined.GridView, contentDescription = null)
            }
        }
    }
}

private const val DIMMED = 0.38f
