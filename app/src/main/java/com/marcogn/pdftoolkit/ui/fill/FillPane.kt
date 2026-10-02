package com.marcogn.pdftoolkit.ui.fill

import android.graphics.Typeface
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.edit.ImageDimensions
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkShape
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.domain.fill.XfaKind
import com.marcogn.pdftoolkit.pdf.edit.FontSource
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.ui.edit.FillDocuments
import com.marcogn.pdftoolkit.ui.edit.FillLoad
import com.marcogn.pdftoolkit.ui.edit.PickedImage

/** What the next tap on a page puts there (spec §6.5, free filling). */
enum class FillTool { TEXT, DATE, CHECK, CROSS, SIGNATURE }

/** Where the text dialog writes: a new text at a display point of a page, or an existing overlay. */
data class TextTarget(val pageId: String, val displayX: Float, val displayY: Float, val overlayId: String?, val isDate: Boolean)

/**
 * UI state of "Fill and sign" that the edit session doesn't hold: the armed tool, the selected
 * overlay, the image waiting to be placed and the open text dialog. Survives rotation.
 */
@Stable
class FillPaneState(
    tool: FillTool? = null,
    selected: String? = null,
    image: PickedImage? = null,
    textTarget: TextTarget? = null,
) {
    var tool by mutableStateOf(tool)
    var selected by mutableStateOf(selected)
    var image by mutableStateOf(image)
    var textTarget by mutableStateOf(textTarget)

    /** Back: first drop the tool or the selection, only then leave the pane. */
    val consumesBack: Boolean get() = tool != null || selected != null

    fun back() {
        tool = null
        selected = null
    }

    companion object {
        val Saver = listSaver<FillPaneState, Any?>(
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
                FillPaneState((saved[0] as? String)?.let { FillTool.valueOf(it) }, saved[1] as String?, image, target)
            },
        )
    }
}

@Composable
fun rememberFillPaneState(): FillPaneState = rememberSaveable(saver = FillPaneState.Saver) { FillPaneState() }

/** What the pane asks of the edit screen. */
interface FillActions : FillPageContent {
    fun addOverlay(overlay: Overlay)
    fun updateOverlay(overlay: Overlay)
    fun removeOverlay(id: String)
    fun setField(field: FormField, value: FieldValue, typing: Boolean)
    fun newOverlayId(): String
    fun sanitize(text: String): String
    fun message(text: String)
}

/**
 * "Fill and sign" (spec §6.5): the session's pages one at a time, each zoomable, with the form
 * fields as Compose controls and the overlays drawn on top. Tapping a page with a tool armed puts
 * the tool's overlay there; without a tool it selects the overlay under the finger.
 */
@Composable
fun FillPane(
    pages: List<PageItem>,
    overlays: List<Overlay>,
    values: Map<String, FieldValue>,
    load: FillLoad?,
    state: FillPaneState,
    actions: FillActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = remember { OverlayPainter(Typeface.createFromAsset(context.assets, FontSource.ASSET_PATH)) }
    when (load) {
        null, FillLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        FillLoad.Failed -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.fill_load_failed), textAlign = TextAlign.Center)
        }
        is FillLoad.Ready -> FillPages(pages, overlays, values, load.documents, state, actions, painter, modifier)
    }

    state.textTarget?.let { target ->
        val existing = target.overlayId?.let { id -> overlays.firstOrNull { it.id == id } as? TextOverlay }
        val removed = stringResource(R.string.fill_text_removed)
        TextOverlayDialog(
            initialText = existing?.text ?: if (target.isDate) todayText(context) else "",
            initialSize = existing?.fontSize ?: TextBlock.DEFAULT_FONT_SIZE,
            isNew = existing == null,
            onConfirm = { raw, fontSize ->
                state.textTarget = null
                val text = actions.sanitize(raw)
                if (text != TextBlock.sanitize(raw) { true }) actions.message(removed)
                if (text.isBlank()) return@TextOverlayDialog
                val (width, height) = painter.textBoxSize(text, fontSize)
                if (existing != null) {
                    actions.updateOverlay(existing.copy(text = text, fontSize = fontSize, box = OverlayGeometry.resizedFromTopLeft(existing.box, width, height)))
                } else {
                    placeText(target, text, fontSize, width, height, pages, load, actions)
                }
            },
            onDismiss = { state.textTarget = null },
        )
    }
}

/** The new text's first line is centred vertically on the tap, its left edge just left of it. */
private fun placeText(target: TextTarget, text: String, fontSize: Float, width: Float, height: Float, pages: List<PageItem>, load: FillLoad?, actions: FillActions) {
    val documents = (load as? FillLoad.Ready)?.documents ?: return
    val page = pages.firstOrNull { it.id == target.pageId } ?: return
    val space = documents.space(page) ?: return
    val (x, baseline) = TextBlock.lineOrigin(0, fontSize)
    val firstLineMiddle = baseline - (TextBlock.ASCENT - TextBlock.DESCENT) / 2f * fontSize
    val topLeft = Offset(target.displayX - x, target.displayY - firstLineMiddle)
    val box = OverlayGeometry.uprightAt(space, topLeft + Offset(width / 2f, height / 2f), width, height)
    actions.addOverlay(TextOverlay(actions.newOverlayId(), page.id, box, text, fontSize))
}

@Composable
private fun FillPages(
    pages: List<PageItem>,
    overlays: List<Overlay>,
    values: Map<String, FieldValue>,
    documents: FillDocuments,
    state: FillPaneState,
    actions: FillActions,
    painter: OverlayPainter,
    modifier: Modifier,
) {
    val pagerState = rememberPagerState { pages.size }
    Column(modifier.fillMaxSize()) {
        if (documents.form?.xfa == XfaKind.DYNAMIC) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fill_xfa_unsupported), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
        Box(Modifier.weight(1f)) {
            HorizontalPager(pagerState, key = { pages[it].id }, modifier = Modifier.fillMaxSize()) { index ->
                val page = pages[index]
                val space = documents.space(page)
                val sourceSpace = documents.sourceSpace(page)
                if (space == null || sourceSpace == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@HorizontalPager
                }
                FillPage(
                    item = page,
                    space = space,
                    sourceSpace = sourceSpace,
                    overlays = overlays.filter { it.pageId == page.id },
                    fields = documents.fieldsOn(page),
                    values = values,
                    selectedOverlay = state.selected,
                    painter = painter,
                    content = actions,
                    pageColor = androidx.compose.ui.graphics.Color.White,
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectionColor = MaterialTheme.colorScheme.primary,
                    onTap = { user, display -> onPageTap(page, space, user, display, overlays, state, actions) },
                    onFieldChange = actions::setField,
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
}

private fun onPageTap(
    page: PageItem,
    space: PdfPageSpace,
    user: Offset,
    display: Offset,
    overlays: List<Overlay>,
    state: FillPaneState,
    actions: FillActions,
) {
    when (val tool = state.tool) {
        null -> {
            // The topmost overlay under the finger; a tap elsewhere clears the selection.
            state.selected = overlays.lastOrNull { it.pageId == page.id && OverlayGeometry.contains(it.box, user) }?.id
        }
        FillTool.CHECK, FillTool.CROSS -> {
            // Stays armed: ticking several boxes in a row is the usual case.
            val box = OverlayGeometry.uprightAt(space, display, MarkShape.DEFAULT_SIZE, MarkShape.DEFAULT_SIZE)
            actions.addOverlay(MarkOverlay(actions.newOverlayId(), page.id, box, if (tool == FillTool.CHECK) MarkKind.CHECK else MarkKind.CROSS))
        }
        FillTool.TEXT, FillTool.DATE -> {
            state.tool = null
            state.textTarget = TextTarget(page.id, display.x, display.y, overlayId = null, isDate = tool == FillTool.DATE)
        }
        FillTool.SIGNATURE -> {
            val image = state.image ?: return
            state.tool = null
            state.image = null
            val (width, height) = imageSize(image.dimensions.widthPx, image.dimensions.heightPx, space.displaySize.width)
            val box = OverlayGeometry.uprightAt(space, display, width, height)
            val overlay = ImageOverlay(actions.newOverlayId(), page.id, box, image.uri)
            actions.addOverlay(overlay)
            state.selected = overlay.id
        }
    }
}

/**
 * The tool bar of the pane, in the screen's bottom bar: the tools, or what can be done with the
 * selected overlay. [onPickSignature] picks the image for the signature tool.
 */
@Composable
fun FillToolBar(state: FillPaneState, overlays: List<Overlay>, actions: FillActions, onPickSignature: () -> Unit) {
    val selected = state.selected?.let { id -> overlays.firstOrNull { it.id == id } }
    Column {
        if (state.tool != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.fill_hint_place),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        BottomAppBar {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    if (selected is TextOverlay) {
                        ToolButton(Icons.Filled.Edit, R.string.fill_edit, active = false) {
                            state.textTarget = TextTarget(selected.pageId, 0f, 0f, selected.id, isDate = false)
                        }
                    }
                    ToolButton(Icons.Filled.Delete, R.string.fill_delete, active = false) {
                        actions.removeOverlay(selected.id)
                        state.selected = null
                    }
                    ToolButton(Icons.Filled.Check, R.string.fill_done, active = false) { state.selected = null }
                } else {
                    ToolButton(Icons.Filled.TextFields, R.string.fill_tool_text, state.tool == FillTool.TEXT) { state.toggle(FillTool.TEXT) }
                    ToolButton(Icons.Filled.CalendarToday, R.string.fill_tool_date, state.tool == FillTool.DATE) { state.toggle(FillTool.DATE) }
                    MarkToolButton(MarkKind.CHECK, R.string.fill_tool_check, state.tool == FillTool.CHECK) { state.toggle(FillTool.CHECK) }
                    MarkToolButton(MarkKind.CROSS, R.string.fill_tool_cross, state.tool == FillTool.CROSS) { state.toggle(FillTool.CROSS) }
                    ToolButton(Icons.Filled.Draw, R.string.fill_tool_signature, state.tool == FillTool.SIGNATURE) {
                        if (state.tool == FillTool.SIGNATURE) state.toggle(FillTool.SIGNATURE) else onPickSignature()
                    }
                }
            }
        }
    }
}

private fun FillPaneState.toggle(tool: FillTool) {
    this.tool = if (this.tool == tool) null else tool
    selected = null
}

@Composable
private fun ToolButton(icon: ImageVector, label: Int, active: Boolean, onClick: () -> Unit) {
    ToolButtonFrame(label, active, onClick) { Icon(icon, contentDescription = null) }
}

@Composable
private fun MarkToolButton(kind: MarkKind, label: Int, active: Boolean, onClick: () -> Unit) {
    ToolButtonFrame(label, active, onClick) { Mark(kind, Modifier.size(18.dp), LocalContentColor.current) }
}

/** Icon over its label; the armed tool is in the primary colour. */
@Composable
private fun ToolButtonFrame(label: Int, active: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    TextButton(onClick = onClick, modifier = Modifier.semantics { selected = active }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CompositionLocalProvider(LocalContentColor provides color) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Text(stringResource(label), color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A new image is 150 pt wide, or 40% of the page if that is narrower, with its own proportions. */
private fun imageSize(widthPx: Int, heightPx: Int, pageWidth: Float): Pair<Float, Float> {
    val width = minOf(IMAGE_WIDTH_PT, pageWidth * IMAGE_PAGE_FRACTION)
    return width to width * heightPx / widthPx.coerceAtLeast(1)
}

private const val IMAGE_WIDTH_PT = 150f
private const val IMAGE_PAGE_FRACTION = 0.4f
