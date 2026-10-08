package com.marcogn.pdftoolkit.ui.fill

import android.graphics.Typeface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.marcogn.pdftoolkit.ui.common.ToolStrip
import com.marcogn.pdftoolkit.ui.common.TransientHint
import com.marcogn.pdftoolkit.ui.common.UndoRedo
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.marcogn.pdftoolkit.ui.common.PageIndicatorChip
import com.marcogn.pdftoolkit.ui.common.ReportCurrentPage
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
    initialPageId: String? = null,
    onPageChanged: (pageId: String) -> Unit = {},
    onChangeSignature: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = remember { OverlayPainter(Typeface.createFromAsset(context.assets, FontSource.ASSET_PATH)) }
    when (load) {
        null, FillLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        FillLoad.Failed -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.fill_load_failed), textAlign = TextAlign.Center)
        }
        is FillLoad.Ready -> FillPages(pages, overlays, values, load.documents, state, actions, painter, initialPageId, onPageChanged, onChangeSignature, modifier)
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
                    placeText(target, text, fontSize, width, height, pages, (load as? FillLoad.Ready)?.documents, actions)
                }
            },
            onDismiss = { state.textTarget = null },
        )
    }
}

/**
 * The new text's first line is centred vertically on the tap, its left edge just left of it.
 * Returns the id of the overlay added, or null if the page can't be found.
 */
private fun placeText(target: TextTarget, text: String, fontSize: Float, width: Float, height: Float, pages: List<PageItem>, documents: FillDocuments?, actions: FillActions): String? {
    if (documents == null) return null
    val page = pages.firstOrNull { it.id == target.pageId } ?: return null
    val space = documents.space(page) ?: return null
    val (x, baseline) = TextBlock.lineOrigin(0, fontSize)
    val firstLineMiddle = baseline - (TextBlock.ASCENT - TextBlock.DESCENT) / 2f * fontSize
    val topLeft = Offset(target.displayX - x, target.displayY - firstLineMiddle)
    val box = OverlayGeometry.uprightAt(space, topLeft + Offset(width / 2f, height / 2f), width, height)
    val overlay = TextOverlay(actions.newOverlayId(), page.id, box, text, fontSize)
    actions.addOverlay(overlay)
    return overlay.id
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
    initialPageId: String?,
    onPageChanged: (pageId: String) -> Unit,
    onChangeSignature: () -> Unit,
    modifier: Modifier,
) {
    val pagerState = rememberPagerState(initialPage = pages.indexOfFirst { it.id == initialPageId }.coerceAtLeast(0)) { pages.size }
    ReportCurrentPage(pagerState, pages, onPageChanged)
    val context = LocalContext.current
    // "Date" puts today's date where the page is tapped and selects it: no dialog (plan U12); "Edit" is one tap away.
    val placeDate: (PageItem, Offset) -> Unit = { page, display ->
        val text = actions.sanitize(todayText(context))
        val fontSize = TextBlock.DEFAULT_FONT_SIZE
        val (width, height) = painter.textBoxSize(text, fontSize)
        placeText(TextTarget(page.id, display.x, display.y, overlayId = null, isDate = true), text, fontSize, width, height, pages, documents, actions)
            ?.let { state.selected = it }
    }
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
                    toolArmed = state.tool != null,
                    onTap = { user, display -> onPageTap(page, space, user, display, overlays, state, actions, placeDate) },
                    onOverlaySelect = { state.selected = it },
                    onOverlayChange = actions::updateOverlay,
                    onFieldChange = actions::setField,
                )
            }
            // Shown for a few seconds when the tool or the selection changes, over the page instead of taking room from it (plan U7).
            val hint = when {
                state.selected != null -> stringResource(R.string.fill_hint_move)
                state.tool != null -> stringResource(R.string.fill_hint_place)
                else -> null
            }
            val signatureArmed = state.tool == FillTool.SIGNATURE && state.selected == null
            TransientHint(
                hint,
                key = Triple(state.tool, state.selected != null, state.image?.uri),
                action = if (signatureArmed) stringResource(R.string.fill_change_signature) to onChangeSignature else null,
            )
            PageIndicatorChip(pagerState, pages.size, Modifier.align(Alignment.BottomCenter).padding(8.dp))
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
    placeDate: (PageItem, Offset) -> Unit,
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
        FillTool.DATE -> {
            state.tool = null
            placeDate(page, display)
        }
        FillTool.TEXT -> {
            state.tool = null
            state.textTarget = TextTarget(page.id, display.x, display.y, overlayId = null, isDate = false)
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
 * The tools of the pane, as a bar at the bottom of the screen or (with [side]) a rail at its end: the
 * tools, or what can be done with the selected overlay. [onSignature] is a tap on "Signature" (arm the
 * only saved signature, or open the picker, or create the first: the screen decides), [onPickSignature]
 * its long press, which always opens the picker (plan U8).
 */
@Composable
fun FillToolBar(
    state: FillPaneState,
    overlays: List<Overlay>,
    actions: FillActions,
    undoRedo: UndoRedo,
    side: Boolean,
    onSignature: () -> Unit,
    onPickSignature: () -> Unit,
) {
    val selected = state.selected?.let { id -> overlays.firstOrNull { it.id == id } }
    ToolStrip(side, undoRedo, Modifier.then(if (side) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())) {
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
            ToolButtonFrame(
                R.string.fill_tool_signature,
                state.tool == FillTool.SIGNATURE,
                onClick = { if (state.tool == FillTool.SIGNATURE) state.toggle(FillTool.SIGNATURE) else onSignature() },
                onLongClick = onPickSignature,
            ) { Icon(Icons.Filled.Draw, contentDescription = null) }
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

/** A new image is 150 pt wide, or 40% of the page if that is narrower, with its own proportions. */
private fun imageSize(widthPx: Int, heightPx: Int, pageWidth: Float): Pair<Float, Float> {
    val width = minOf(IMAGE_WIDTH_PT, pageWidth * IMAGE_PAGE_FRACTION)
    return width to width * heightPx / widthPx.coerceAtLeast(1)
}

private const val IMAGE_WIDTH_PT = 150f
private const val IMAGE_PAGE_FRACTION = 0.4f
