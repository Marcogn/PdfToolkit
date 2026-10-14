package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.QuickSlots
import com.marcogn.pdftoolkit.ui.annotate.AnnotatePaneState
import com.marcogn.pdftoolkit.ui.annotate.ToolBubble
import com.marcogn.pdftoolkit.ui.annotate.icon
import com.marcogn.pdftoolkit.ui.annotate.labelRes
import com.marcogn.pdftoolkit.ui.common.ToolStrip
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.fill.ToolButtonFrame

/** Everything the bar and the drawer share about the quick slots and a drag in progress. */
class ToolBarCallbacks(
    val geometry: ToolBarGeometry,
    val drag: ToolDragState,
    /** A tool let go: which, from which slot (null = the drawer), onto which slot, whether over the bar. */
    val onDrop: (tool: DrawerTool, from: Int?, to: Int?, overBar: Boolean) -> Unit,
)

/**
 * The tools of the viewer (plans V-b, V-c, T-b), as a bar at the bottom of the screen or, with [side], a rail at
 * its end (plan U18). The places never move: **Tools** (opens the drawer, shows the icon of the armed tool when
 * that one isn't pinned), the two **quick slots**, **Undo**, **Redo** and **Pages**. Arming a tool, picking a
 * colour or having something to undo changes nothing in the layout. While [filling], the strip holds Tools, Fill
 * (armed) and the [fillTools] instead, with Undo and Redo.
 *
 * @param armed the tool in effect, wherever it is (also "Fill and sign"), or null.
 * @param editable the document can take page tools; otherwise those buttons are dimmed and a tap on them
 * only explains why ([onArm] decides what to say).
 * @param onOrigin where the drawer's fan unfolds from, in root coordinates, when the Tools button moves.
 */
@Composable
fun ViewerToolBar(
    state: AnnotatePaneState,
    armed: DrawerTool?,
    slots: QuickSlots,
    editable: Boolean,
    drawerOpen: Boolean,
    undoRedo: UndoRedo,
    side: Boolean,
    callbacks: ToolBarCallbacks,
    onDrawer: () -> Unit,
    onArm: (DrawerTool) -> Unit,
    filling: Boolean,
    onFillDown: () -> Unit,
    onPages: () -> Unit,
    onOrigin: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    fillTools: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val gap = with(density) { FAN_GAP.toPx() }
    val armedElsewhere = armed != null && armed !in slots.tools
    Box(
        modifier
            .then(if (side) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .onGloballyPositioned { callbacks.geometry.bar = it.boundsInRoot() },
    ) {
        DisposableEffect(callbacks) { onDispose { callbacks.geometry.bar = null } }
        // Undo, Redo and Pages are pinned at the end like the old Undo/Redo; the rest scrolls if the bar is too narrow.
        ToolStrip(
            side = side,
            undoRedo = null,
            trailing = {
                UndoRedoButtons(undoRedo, vertical = side)
                if (!filling) {
                    ToolButtonFrame(R.string.viewer_tool_pages, false, onClick = onPages) {
                        Icon(Icons.Outlined.GridView, contentDescription = null)
                    }
                }
            },
        ) {
            // Tools: the drawer. Never a long press (plan T-b).
            Box(
                Modifier.onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    // The fan starts a little above the bar (or beside the rail), level with this button.
                    onOrigin(
                        if (side) Offset(callbacks.geometry.bar?.left?.minus(gap) ?: (bounds.left - gap), bounds.center.y)
                        else Offset(bounds.center.x, (callbacks.geometry.bar?.top ?: bounds.top) - gap),
                    )
                },
            ) {
                val shown = armed?.takeIf { armedElsewhere && !filling }
                // A long press is swallowed (an empty long-click action), so it doesn't count as a tap either.
                ToolButtonFrame(R.string.tools_drawer, drawerOpen || shown != null, onClick = onDrawer, onLongClick = {}) {
                    Icon(shown?.icon() ?: Icons.Outlined.Construction, contentDescription = null)
                }
            }
            if (filling) {
                ToolButtonFrame(R.string.viewer_tool_fill, true, onClick = onFillDown) {
                    Icon(DrawerTool.FILL.icon(), contentDescription = null)
                }
                fillTools()
            } else {
                slots.tools.forEachIndexed { index, tool ->
                    QuickSlot(index, tool, state, armed == tool && tool != null, editable, callbacks, onArm, onDrawer)
                }
            }
        }
    }
}

/** Undo and Redo side by side, or one above the other in the rail. */
@Composable
private fun UndoRedoButtons(undoRedo: UndoRedo, vertical: Boolean) {
    val buttons = @Composable {
        IconButton(onClick = undoRedo.onUndo, enabled = undoRedo.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.edit_undo))
        }
        IconButton(onClick = undoRedo.onRedo, enabled = undoRedo.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.edit_redo))
        }
    }
    if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) { buttons() } else Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
}

/**
 * One quick slot (plan T-b): a tap arms its tool, a tap on the armed tool opens its bubble, hold and move drags
 * it (onto the other slot to swap, off the bar to unpin). An empty slot is a quiet placeholder that turns into a
 * dashed box while a tool is being dragged; a tap on it opens the drawer.
 */
@Composable
private fun QuickSlot(
    index: Int,
    tool: DrawerTool?,
    state: AnnotatePaneState,
    active: Boolean,
    editable: Boolean,
    callbacks: ToolBarCallbacks,
    onArm: (DrawerTool) -> Unit,
    onDrawer: () -> Unit,
) {
    var bubble by remember { mutableStateOf(false) }
    val drag = callbacks.drag
    val target = drag.active && tool == null
    val name = tool?.let { stringResource(it.labelRes()) }
    val description = if (name != null) {
        stringResource(R.string.tools_slot_cd, name)
    } else if (target) {
        stringResource(R.string.tools_slot_here)
    } else {
        stringResource(R.string.tools_slot_empty_cd)
    }
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val dash = MaterialTheme.colorScheme.outline
    val dashPx = with(LocalDensity.current) { floatArrayOf(6.dp.toPx(), 5.dp.toPx()) }
    val strokePx = with(LocalDensity.current) { 1.5.dp.toPx() }
    val cornerPx = with(LocalDensity.current) { 16.dp.toPx() }
    // A slot that isn't composed (Fill and sign) is no drop target.
    DisposableEffect(index) { onDispose { callbacks.geometry.slots[index] = null } }
    val tap = {
        when {
            tool == null -> onDrawer()
            active -> bubble = true
            else -> onArm(tool)
        }
    }
    Box(Modifier.alpha(if (editable || tool == null) 1f else DIMMED)) {
        Column(
            Modifier
                .onGloballyPositioned { callbacks.geometry.slots[index] = it.boundsInRoot() }
                .testTag("quickSlot$index")
                .clip(MaterialTheme.shapes.large)
                .then(if (target) Modifier.drawBehind {
                    drawRoundRect(dash, cornerRadius = CornerRadius(cornerPx), style = Stroke(strokePx, pathEffect = PathEffect.dashPathEffect(dashPx)))
                } else Modifier)
                .toolGestures(
                    holdAndDrag = tool != null,
                    onTap = tap,
                    onHold = { if (tool != null) bubble = true },
                    onDragStart = {
                        bubble = false
                        if (tool != null) drag.start(tool, index, it)
                    },
                    onDrag = drag::move,
                    onDragEnd = {
                        val dropped = drag.tool
                        val from = drag.from
                        drag.end()
                        if (dropped != null) callbacks.onDrop(dropped, from, callbacks.geometry.slotAt(it), callbacks.geometry.overBar(it))
                    },
                    onDragCancel = drag::end,
                )
                .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    selected = active
                    contentDescription = description
                    onClick { tap(); true }
                    if (tool != null) onLongClick { bubble = true; true }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides if (tool == null) color.copy(alpha = EMPTY_ALPHA) else color) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(tool?.icon() ?: Icons.Outlined.AddCircleOutline, contentDescription = null)
                }
                Text(
                    stringResource(tool?.labelRes() ?: R.string.tools_slot_empty),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        if (tool != null) ToolBubble(tool, state, expanded = bubble, onDismiss = { bubble = false })
    }
}

private const val DIMMED = 0.38f
private const val EMPTY_ALPHA = 0.5f
private val FAN_GAP = 40.dp
