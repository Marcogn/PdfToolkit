package com.marcogn.pdftoolkit.ui.fill

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.ui.edit.PickedImage

/** What the next tap on a page puts there (spec §6.5, free filling). */
enum class FillTool { TEXT, DATE, CHECK, CROSS, SIGNATURE }

/** Where the text dialog writes: a new text at a display point of a page, or an existing overlay. */
data class TextTarget(val pageId: String, val displayX: Float, val displayY: Float, val overlayId: String?, val isDate: Boolean)

/**
 * What "Fill and sign" needs besides the edit session: the armed tool, the selected overlay, the
 * signature waiting to be placed and the open text dialog. Survives rotation.
 */
@Stable
class FillToolsState(
    tool: FillTool? = null,
    selected: String? = null,
    image: PickedImage? = null,
    textTarget: TextTarget? = null,
) {
    var tool by mutableStateOf(tool)
    var selected by mutableStateOf(selected)
    var image by mutableStateOf(image)
    var textTarget by mutableStateOf(textTarget)

    /** Back: first drop the tool or the selection, only then put "Fill and sign" down. */
    val consumesBack: Boolean get() = tool != null || selected != null

    fun back() {
        tool = null
        selected = null
    }

    /** Arms [tool], or puts it down if it is the armed one; the selection goes either way. */
    fun toggle(tool: FillTool) {
        this.tool = if (this.tool == tool) null else tool
        selected = null
    }

    companion object {
        val Saver = listSaver<FillToolsState, Any?>(
            save = { state ->
                listOf(
                    state.tool?.name,
                    state.selected,
                    state.image?.uri,
                    state.image?.dimensions?.widthPx,
                    state.image?.dimensions?.heightPx,
                    state.textTarget?.pageId,
                    state.textTarget?.displayX,
                    state.textTarget?.displayY,
                    state.textTarget?.overlayId,
                    state.textTarget?.isDate,
                )
            },
            restore = { saved ->
                val image = (saved[2] as? String)?.let { uri -> PickedImage(uri, ImageDimensions(saved[3] as Int, saved[4] as Int)) }
                val target = (saved[5] as? String)?.let { page ->
                    TextTarget(page, saved[6] as Float, saved[7] as Float, saved[8] as String?, saved[9] as Boolean)
                }
                FillToolsState((saved[0] as? String)?.let { FillTool.valueOf(it) }, saved[1] as String?, image, target)
            },
        )
    }
}

@Composable
fun rememberFillToolsState(): FillToolsState = rememberSaveable(saver = FillToolsState.Saver) { FillToolsState() }

/**
 * The buttons of "Fill and sign" for a tool strip: the tools, or what can be done with [selected]
 * (edit a text, delete, done). [onSignature] is a tap on "Signature" (arm the only saved signature,
 * open the picker, or create the first: the screen decides), [onPickSignature] its long press, which
 * always opens the picker (plan U8).
 */
@Composable
fun FillToolButtons(
    state: FillToolsState,
    selected: Overlay?,
    onDelete: (Overlay) -> Unit,
    onSignature: () -> Unit,
    onPickSignature: () -> Unit,
) {
    if (selected != null) {
        if (selected is TextOverlay) {
            ToolButton(Icons.Filled.Edit, R.string.fill_edit, active = false) {
                state.textTarget = TextTarget(selected.pageId, 0f, 0f, selected.id, isDate = false)
            }
        }
        ToolButton(Icons.Filled.Delete, R.string.fill_delete, active = false) {
            onDelete(selected)
            state.selected = null
        }
        ToolButton(Icons.Filled.Check, R.string.fill_done, active = false) { state.selected = null }
    } else {
        ToolButton(Icons.Filled.TextFields, R.string.fill_tool_text, state.tool == FillTool.TEXT) { state.toggle(FillTool.TEXT) }
        ToolButton(Icons.Filled.CalendarToday, R.string.fill_tool_date, state.tool == FillTool.DATE) { state.toggle(FillTool.DATE) }
        MarkToolButton(MarkKind.CHECK, R.string.fill_tool_check, state.tool == FillTool.CHECK) { state.toggle(FillTool.CHECK) }
        MarkToolButton(MarkKind.CROSS, R.string.fill_tool_cross, state.tool == FillTool.CROSS) { state.toggle(FillTool.CROSS) }
        ToolButtonFrame(
            R.string.fill_tool_signature,
            state.tool == FillTool.SIGNATURE,
            onClick = { if (state.tool == FillTool.SIGNATURE) state.toggle(FillTool.SIGNATURE) else onSignature() },
            onLongClick = onPickSignature,
        ) { Icon(Icons.Filled.Draw, contentDescription = null) }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: Int, active: Boolean, onClick: () -> Unit) {
    ToolButtonFrame(label, active, onClick) { Icon(icon, contentDescription = null) }
}

@Composable
private fun MarkToolButton(kind: MarkKind, label: Int, active: Boolean, onClick: () -> Unit) {
    ToolButtonFrame(label, active, onClick) { Mark(kind, Modifier.size(18.dp), LocalContentColor.current) }
}

/**
 * Icon over its label; the armed tool is in the primary colour. At least 48 dp across (plan U19), and
 * with [onLongClick] a long press does something else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolButtonFrame(
    label: Int,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { selected = active },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
