package com.marcogn.pdftoolkit.ui.viewer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.runtime.mutableStateMapOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.marcogn.pdftoolkit.data.signatures.Signature
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.domain.fill.XfaKind
import com.marcogn.pdftoolkit.pdf.edit.FontSource
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.ui.fill.FillTool
import com.marcogn.pdftoolkit.ui.fill.FillToolButtons
import com.marcogn.pdftoolkit.ui.fill.OverlayPainter
import com.marcogn.pdftoolkit.ui.fill.TextOverlayDialog
import com.marcogn.pdftoolkit.ui.fill.TextTarget
import com.marcogn.pdftoolkit.ui.fill.rememberFillToolsState
import com.marcogn.pdftoolkit.ui.fill.todayText
import com.marcogn.pdftoolkit.ui.signatures.SignatureCreationHost
import com.marcogn.pdftoolkit.ui.signatures.SignaturePickerSheet
import com.marcogn.pdftoolkit.ui.signatures.SignaturesViewModel
import com.marcogn.pdftoolkit.ui.signatures.rememberSignatureCreationState
import android.content.ClipData
import android.content.ContextWrapper
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.ui.annotate.AnnotateTool
import com.marcogn.pdftoolkit.ui.annotate.appliedLabel
import com.marcogn.pdftoolkit.ui.annotate.applyLabel
import com.marcogn.pdftoolkit.ui.annotate.rememberAnnotatePaneState
import com.marcogn.pdftoolkit.ui.common.UndoRedo
import com.marcogn.pdftoolkit.ui.edit.SaveDialog
import com.marcogn.pdftoolkit.ui.edit.SaveErrorDialog
import com.marcogn.pdftoolkit.ui.edit.SaveUiState
import com.marcogn.pdftoolkit.ui.edit.SavedSnackbar
import com.marcogn.pdftoolkit.ui.edit.UnsavedChangesDialog
import com.marcogn.pdftoolkit.ui.edit.shareCopy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import com.marcogn.pdftoolkit.domain.model.PdfTool
import com.marcogn.pdftoolkit.ui.annotate.hint
import com.marcogn.pdftoolkit.ui.common.TransientHint
import com.marcogn.pdftoolkit.ui.common.isLandscape
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import com.marcogn.pdftoolkit.pdf.render.RenderBudget
import com.marcogn.pdftoolkit.ui.search.RevealRequest
import com.marcogn.pdftoolkit.pdf.text.PageTextReader
import com.marcogn.pdftoolkit.pdf.text.TextSelection
import com.marcogn.pdftoolkit.ui.annotate.AnnotationLayer
import com.marcogn.pdftoolkit.ui.annotate.ResolveTextSelection
import com.marcogn.pdftoolkit.ui.annotate.TextSelectionState
import com.marcogn.pdftoolkit.ui.annotate.rememberTextSelectionState
import com.marcogn.pdftoolkit.ui.search.SearchHighlights
import com.marcogn.pdftoolkit.ui.search.SearchNotice
import com.marcogn.pdftoolkit.ui.search.SearchTopBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private const val MODE_CROSSFADE_MS = 200
private const val PANEL_MS = 220

/** Height of the top bar without the status bar, for what floats just under it (the rail, the hint). */
private val TOP_BAR_HEIGHT = 64.dp

/** What a save started from a dialog does once the file is written, besides showing it: leave, or open "Pages". */
private const val AFTER_SAVE_LEAVE = "leave"
private const val AFTER_SAVE_PAGES = "pages"

/** How far from an annotation the eraser still takes it, so a thin underline isn't a precision job. */
private val ERASE_TOLERANCE = 14.dp

/**
 * The open document (spec §4.2): top bar with page indicator and menu, the pages in the chosen
 * [ReadingMode], the scrubber and the thumbnail bar, the text search (spec §5.1) and the tools bar
 * (plans V-b, V-c): the page tools (markup, drawing, eraser, fill and sign) act here; "Pages" opens the
 * edit screen.
 *
 * [currentPage] lives here and is fed by whichever mode is on screen, so switching mode, the top
 * bar and the thumbnail bar always agree. Jumps (scrubber, thumbnails, "go to page") are sent
 * through [jumps] and executed by the mode on screen; search results are brought to the centre of
 * the screen through [reveals].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyViewer(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    onPageChanged: (Int) -> Unit,
    startTool: PdfTool?,
    onOpenPages: (uri: String, page: Int, reopenViewer: Boolean) -> Unit,
    save: ViewerSaveUi,
    fill: ViewerFillUi,
    onBack: () -> Unit,
    onReopen: (uri: String) -> Unit,
    onOpenCopy: (uri: String) -> Unit,
) {
    val pageCount = state.pageSizes.size
    var currentPage by rememberSaveable { mutableIntStateOf(state.startPage) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    // A single tap on the page hides the top bar, the tools bar and the system bars, and shows them again (plan U20).
    var immersive by rememberSaveable { mutableStateOf(false) }
    val jumps = remember { Channel<Int>(Channel.CONFLATED) }
    val reveals = remember { Channel<RevealRequest>(Channel.CONFLATED) }
    val search = state.search
    val searchState by search.state.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var handledFocusToken by rememberSaveable { mutableIntStateOf(searchState.focusToken) }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            search.start()
            search.setQuery(searchText)
        } else {
            search.close()
        }
    }
    // Scroll to the current result when the search asks for it (first result, next, previous), not
    // when more pages get indexed or the screen turns.
    LaunchedEffect(searchState.focusToken) {
        if (searchOpen && searchState.focusToken != handledFocusToken) {
            handledFocusToken = searchState.focusToken
            searchState.currentMatch?.let { reveals.trySend(RevealRequest(searchState.focusToken, it)) }
        }
    }
    val closeSearch = {
        searchOpen = false
        searchText = ""
    }

    // Text selection and Copy (spec §7.4): a press and hold selects the word, the handles stretch it.
    val selection = rememberTextSelectionState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.viewer_text_copied)
    val copyLabel = stringResource(R.string.viewer_selection_title)
    ResolveTextSelection(selection) { key -> key.toIntOrNull()?.let { state.textReader.selectionModel(it) } }
    LaunchedEffect(searchOpen) {
        if (searchOpen) selection.clear()
    }
    // The text of a long-pressed page loads in the background: a release must wait for it (the finger may lift first).
    val selecting = remember { object { var job: Job? = null } }
    val onLongPress: (Int, Offset) -> Unit = onLongPress@{ page, point ->
        if (searchOpen) return@onLongPress
        selecting.job = scope.launch {
            val model = state.textReader.selectionModel(page) ?: return@launch
            val word = model.wordAt(point) ?: return@launch
            selection.select(page.toString(), model, word)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val copySelection = {
        val text = selection.clipboardText
        if (text.isNotEmpty()) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
            // Android 13 and later confirm a copy themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
            }
        }
        selection.clear()
    }
    // Editing on the pages (plan V-a, ADR 0005): the viewer's own session, drawn over the file as it is.
    val editing = state.editing
    val edits by editing.edits.collectAsStateWithLifecycle()
    val availability by state.editAvailability.collectAsStateWithLifecycle()
    val documentAnnotations by state.annotations.collectAsStateWithLifecycle()
    val pageTools = remember(editing, documentAnnotations, availability) {
        documentAnnotations?.takeIf { availability == EditAvailability.READY }?.let { ViewerPageTools(editing, it) }
    }
    // Opened from Home on Highlight or Draw: that tool is armed as soon as the document can take it.
    val annotateTools = rememberAnnotatePaneState(if (startTool == PdfTool.DRAW) AnnotateTool.PEN else AnnotateTool.HIGHLIGHT)
    var annotating by rememberSaveable { mutableStateOf(startTool == PdfTool.HIGHLIGHT || startTool == PdfTool.DRAW) }
    // "Fill and sign" (plan V-c): its own family of tools, never armed together with a markup or drawing tool.
    var filling by rememberSaveable { mutableStateOf(startTool == PdfTool.FILL_AND_SIGN) }
    val fillTools = rememberFillToolsState()
    // A tool is in effect only while it is armed and the document can be edited.
    val armed = annotating && pageTools != null
    val armedTool = annotateTools.tool.takeIf { armed }
    val fillArmed = filling && pageTools != null
    val applyKind = armedTool?.kind ?: MarkupKind.HIGHLIGHT
    val putFillDown = {
        filling = false
        fillTools.back()
    }
    LaunchedEffect(searchOpen) {
        // Search and the tools don't mix (plan V-a): opening search puts the tool down.
        if (searchOpen) {
            annotating = false
            putFillDown()
        }
    }
    // Strokes just finished, drawn here until the session (a state flow, a frame later) has them.
    val pending = remember { mutableStateListOf<NewAnnotation>() }
    LaunchedEffect(edits) { pending.removeAll { p -> edits.session.annotations.added.any { it.id == p.id } } }
    val brush = armedTool?.freehand
    val brushColor = brush?.let(annotateTools::colorFor)
    val brushWidth = brush?.let(annotateTools::widthFor)
    val drawing = remember(pageTools, brush, brushColor, brushWidth) {
        val tools = pageTools
        if (tools == null || brush == null || brushColor == null || brushWidth == null) {
            null
        } else {
            ViewportDrawing(brush, brushColor, brushWidth) { page, stroke ->
                tools.inkAnnotation(page, stroke, brush, brushColor)?.let { annotation ->
                    pending += annotation
                    if (!editing.addAnnotation(annotation)) pending -= annotation
                }
            }
        }
    }
    val density = LocalDensity.current
    val eraseTolerancePx = with(density) { ERASE_TOLERANCE.toPx() }
    val unavailableMessage = stringResource(
        when (availability) {
            EditAvailability.PROTECTED -> R.string.edit_error_protected
            EditAvailability.UNREADABLE -> R.string.annotate_load_failed
            else -> R.string.viewer_edit_loading
        },
    )
    val showUnavailable = {
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        Unit
    }
    // A tap on the button of the armed family puts the tool down; on another one arms its last tool (plan V-b).
    val onGroup: (ViewerToolGroup) -> Unit = { group ->
        if (pageTools == null) {
            showUnavailable()
        } else if (annotating && annotateTools.tool.group() == group) {
            annotating = false
        } else {
            annotateTools.choose(annotateTools.toolOf(group))
            annotating = true
            putFillDown()
            showThumbnails = false
            immersive = false
        }
    }
    val onFill: () -> Unit = {
        if (pageTools == null) {
            showUnavailable()
        } else if (filling) {
            putFillDown()
        } else {
            filling = true
            annotating = false
            selection.clear()
            showThumbnails = false
            immersive = false
        }
    }
    // Opened on a tool that this document can't take (protected, unreadable): say so once, nothing is armed.
    var startToolChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(availability) {
        if (!startToolChecked && availability != EditAvailability.LOADING) {
            startToolChecked = true
            if ((annotating || filling) && availability != EditAvailability.READY) {
                annotating = false
                filling = false
                showUnavailable()
            }
        }
    }

    // Fill and sign (plan V-c): the form is read the first time it is needed, to fill it or to draw restored values.
    val fillContent = edits.session.fill
    LaunchedEffect(fillArmed, fillContent.fields.isNotEmpty(), availability) {
        if (availability == EditAvailability.READY && (fillArmed || fillContent.fields.isNotEmpty())) fill.load()
    }
    val form = (fill.form as? FormLoad.Ready)?.form
    val fieldsByPage = remember(form) {
        form?.fields.orEmpty().flatMap { field -> field.widgets.map { it.pageIndex to field } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, fields) -> fields.distinct() }
    }
    val fieldsUnreadable = stringResource(R.string.fill_fields_unreadable)
    val xfaMessage = stringResource(R.string.fill_xfa_unsupported)
    LaunchedEffect(fillArmed, fill.form) {
        // Said when "Fill and sign" is armed and the form turns out not to be fillable; free filling still works.
        val message = when {
            !fillArmed -> null
            fill.form is FormLoad.Failed -> fieldsUnreadable
            form?.xfa == XfaKind.DYNAMIC -> xfaMessage
            else -> null
        }
        if (message != null) snackbarHostState.showSnackbar(message)
    }
    // An undo can take the selected overlay away.
    LaunchedEffect(fillContent.overlays) {
        if (fillTools.selected != null && fillContent.overlays.none { it.id == fillTools.selected }) fillTools.selected = null
    }
    val overlayPainter = remember { lazy { OverlayPainter(Typeface.createFromAsset(context.assets, FontSource.ASSET_PATH)) } }
    val overlayImages = remember { mutableStateMapOf<String, Bitmap>() }
    val overlayImageUris = fillContent.overlays.filterIsInstance<ImageOverlay>().map { it.imageUri }.toSet()
    LaunchedEffect(overlayImageUris) {
        overlayImageUris.filterNot { it in overlayImages }.forEach { uri -> fill.image(uri)?.let { overlayImages[uri] = it } }
    }
    val viewportFill = if (pageTools != null && (fillArmed || !fillContent.isEmpty)) {
        ViewportFillContent(
            overlays = fillContent.overlays,
            images = overlayImages,
            painter = overlayPainter.value,
            spaceOf = pageTools::spaceOf,
            fieldsOn = { page -> fieldsByPage[page].orEmpty() },
            values = fillContent.fields,
            editing = fillArmed,
            grabbing = fillArmed && fillTools.tool == null,
            selected = fillTools.selected.takeIf { fillArmed },
            onSelect = { fillTools.selected = it },
            onOverlayChange = { editing.updateOverlay(it) },
            onFieldChange = { field: FormField, value, typing -> editing.setField(field, value, typing) },
        )
    } else {
        null
    }
    // Signatures (phase 4b, plan U8): from "My signatures", or created on the spot; one saved signature is armed directly.
    val signaturesViewModel: SignaturesViewModel = hiltViewModel()
    val savedSignatures by signaturesViewModel.signatures.collectAsStateWithLifecycle()
    val signatureCreation = rememberSignatureCreationState()
    var showSignatureSheet by rememberSaveable { mutableStateOf(false) }
    val imageUnreadable = stringResource(R.string.fill_image_unreadable)
    val placeSignature: (Signature) -> Unit = { signature ->
        scope.launch {
            val image = fill.importImage(signaturesViewModel.file(signature).toUri())
            if (image == null) {
                snackbarHostState.showSnackbar(imageUnreadable)
            } else {
                fillTools.image = image
                fillTools.tool = FillTool.SIGNATURE
                fillTools.selected = null
            }
        }
    }
    SignatureCreationHost(signatureCreation, signaturesViewModel, onCreated = placeSignature)
    if (showSignatureSheet) {
        SignaturePickerSheet(
            viewModel = signaturesViewModel,
            onPick = {
                showSignatureSheet = false
                placeSignature(it)
            },
            onNew = {
                showSignatureSheet = false
                signatureCreation.start()
            },
            onDismiss = { showSignatureSheet = false },
        )
    }
    val onSignature: () -> Unit = {
        val list = savedSignatures
        when {
            list == null || list.size > 1 -> showSignatureSheet = true
            list.isEmpty() -> signatureCreation.start()
            else -> placeSignature(list.first())
        }
    }
    val textRemoved = stringResource(R.string.fill_text_removed)
    // Text measured as the screen and the PDF draw it; characters the font lacks are dropped (and the reader told).
    val sanitized: (String) -> String = { raw ->
        val text = fill.sanitize(raw)
        if (text != TextBlock.sanitize(raw) { true }) scope.launch { snackbarHostState.showSnackbar(textRemoved) }
        text
    }
    // A tap with "Fill and sign" armed: the armed tool puts its overlay there, else the overlay under the finger is selected.
    val onFillTap: (PageTap) -> Unit = { tap ->
        val tools = pageTools
        if (tools != null) {
            val page = tap.documentPage
            when (fillTools.tool) {
                null -> {
                    val hit = tools.overlayAt(page, tap.point, fillContent.overlays)
                    when {
                        hit != null -> fillTools.selected = hit.id
                        fillTools.selected != null -> fillTools.selected = null
                        else -> immersive = !immersive
                    }
                }
                // Stays armed: ticking several boxes in a row is the usual case.
                FillTool.CHECK -> tools.newMark(page, tap.point, MarkKind.CHECK)?.let { editing.addOverlay(it) }
                FillTool.CROSS -> tools.newMark(page, tap.point, MarkKind.CROSS)?.let { editing.addOverlay(it) }
                // Today's date where the page is tapped, selected: no dialog (plan U12); "Edit" is one tap away.
                FillTool.DATE -> {
                    fillTools.tool = null
                    val text = sanitized(todayText(context))
                    val fontSize = TextBlock.DEFAULT_FONT_SIZE
                    val (width, height) = overlayPainter.value.textBoxSize(text, fontSize)
                    tools.newText(page, tap.point, text, fontSize, width, height)?.let { overlay ->
                        if (editing.addOverlay(overlay)) fillTools.selected = overlay.id
                    }
                }
                FillTool.TEXT -> {
                    fillTools.tool = null
                    fillTools.textTarget = TextTarget(ViewerEditSession.pageId(page), tap.point.x, tap.point.y, overlayId = null, isDate = false)
                }
                FillTool.SIGNATURE -> fillTools.image?.let { image ->
                    fillTools.tool = null
                    fillTools.image = null
                    tools.newImage(page, tap.point, image)?.let { overlay ->
                        if (editing.addOverlay(overlay)) fillTools.selected = overlay.id
                    }
                }
            }
        }
    }

    // Saving from the viewer (plan V-a): the save dialog of the edit screen, the same engine (ADR 0005).
    val saving = save.state is SaveUiState.Saving
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    // What follows a save started from a dialog: leave (plan U3), or open "Pages" on the saved file (with changes
    // pending, plan V-b); null for a plain Save.
    var afterSave by rememberSaveable { mutableStateOf<String?>(null) }
    // "Pages" asked for while the viewer has unsaved changes: the "save first" dialog is up.
    var showSaveFirst by rememberSaveable { mutableStateOf(false) }
    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) save.onSave(uri, false) else afterSave = null
    }
    val copySuffix = stringResource(R.string.save_copy_suffix)
    val startSave = {
        showSaveDialog = false
        if (save.overwriteChoice && save.canOverwrite) {
            // The save dialog is the explicit confirmation (plan U6).
            save.onSave(state.uri.toUri(), true)
        } else {
            copyLauncher.launch(save.suggestedCopyName(copySuffix))
        }
    }
    val savedMessage = stringResource(R.string.save_done)
    val shareTitle = stringResource(R.string.viewer_share_title)
    LaunchedEffect(save.state) {
        when (val result = save.state) {
            // The file on screen is the old one: leave, open the edit screen on the new one, or show the new one in its place (ADR 0003).
            is SaveUiState.Overwritten -> when (val next = afterSave) {
                null -> onReopen(result.uri)
                AFTER_SAVE_LEAVE -> {
                    afterSave = null
                    Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                    onBack()
                }
                else -> {
                    afterSave = null
                    onOpenPages(result.uri, currentPage, true)
                }
            }
            is SaveUiState.Saved -> afterSave?.let { next ->
                afterSave = null
                save.onDismissResult()
                if (next == AFTER_SAVE_LEAVE) {
                    Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                    onBack()
                } else {
                    // The copy is the file the pages are worked on: the viewer shows it too, under the edit screen.
                    onOpenPages(result.uri, currentPage, true)
                }
            }
            is SaveUiState.Failed -> afterSave = null
            else -> Unit
        }
    }
    // Pages works on the saved file: with changes pending the reader saves or discards first (plan V-b).
    val openPages: () -> Unit = {
        if (edits.hasUnsavedChanges && !saving) showSaveFirst = true else onOpenPages(state.uri, currentPage, false)
    }
    val requestExit = {
        if (edits.hasUnsavedChanges && !saving) showUnsaved = true else onBack()
    }
    val backStep = viewerBackStep(searchOpen, selection.isActive, annotating || filling, edits.hasUnsavedChanges, saving, fillArmed && fillTools.consumesBack)
    BackHandler(enabled = backStep != ViewerBackStep.LEAVE) {
        when (backStep) {
            ViewerBackStep.CLOSE_SEARCH -> closeSearch()
            ViewerBackStep.CLEAR_SELECTION -> selection.clear()
            ViewerBackStep.DROP_FILL_TOOL -> fillTools.back()
            ViewerBackStep.PUT_TOOL_DOWN -> {
                annotating = false
                putFillDown()
            }
            ViewerBackStep.ASK_TO_SAVE -> showUnsaved = true
            ViewerBackStep.LEAVE -> onBack()
        }
    }
    // The tools bar is out except in full screen, while searching and over the thumbnails (plan V-b).
    val toolBarVisible = !immersive && !searchOpen && !showThumbnails
    val railed = isLandscape()
    // How much of the page area the bar covers, so the scrubber and the snackbars keep clear of it.
    var toolBarHeight by remember { mutableStateOf(0.dp) }
    var toolBarWidth by remember { mutableStateOf(0.dp) }
    // Selecting is for reading and for the markup tools; a brush or the eraser takes the touch, and with Fill and
    // sign a long press grabs an overlay.
    val pageSelection = selection.takeIf { !fillArmed && (armedTool == null || armedTool.kind != null) }

    val highlights = remember(searchOpen, searchState.matches, searchState.current) {
        if (searchOpen) SearchHighlights.of(searchState.matches, searchState.current) else SearchHighlights.None
    }
    val annotations = remember(documentAnnotations, edits.session.annotations, pending.toList()) {
        val session = edits.session.annotations
        val waiting = pending.filter { p -> session.added.none { it.id == p.id } }
        AnnotationLayer.of(documentAnnotations, session.copy(added = session.added + waiting), ViewerEditSession::pageId)
    }
    val reportPage: (Int) -> Unit = { page ->
        if (page != currentPage) {
            currentPage = page
            onPageChanged(page)
        }
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) immersive = false
    }
    ImmersiveMode(immersive)
    val onTap: (PageTap?) -> Unit = { tap ->
        when {
            selection.isActive -> selection.clear()
            fillArmed -> if (tap != null) {
                onFillTap(tap)
            } else if (fillTools.selected != null) {
                fillTools.selected = null
            } else if (fillTools.tool == null) {
                immersive = !immersive
            }
            // The eraser takes what is under the finger; a tap beside everything does nothing.
            armedTool == AnnotateTool.ERASER -> tap?.let { pageTools?.erase(it.documentPage, it.point, eraseTolerancePx / it.screenPxPerPoint) }
            // A tap with a brush is a dot, not a request for full screen.
            armedTool?.freehand != null -> Unit
            !searchOpen -> immersive = !immersive
        }
    }
    // Puts the selection into the session as [kind]; false if there is nothing to mark up. The selection is cleared.
    val applyMarkup: (MarkupKind) -> Boolean = apply@{ kind ->
        val page = selection.key?.toIntOrNull()
        val annotation = if (page != null && selection.range != null) {
            pageTools?.markupAnnotation(page, selection.runs, kind, annotateTools.colorFor(kind))
        } else {
            null
        }
        selection.clear()
        annotation != null && editing.addAnnotation(annotation)
    }
    // With a markup tool armed the mark is made as soon as the finger lifts, after the long press or after
    // dragging a handle to stretch it; the snackbar's Undo takes it back (plan V-b follow-up).
    val appliedMessage = armedTool?.kind?.let { stringResource(it.appliedLabel()) }
    val undoLabel = stringResource(R.string.edit_undo)
    val onSelectionReleased: () -> Unit = {
        val kind = armedTool?.kind
        if (kind != null && appliedMessage != null) {
            scope.launch {
                selecting.job?.join()
                if (applyMarkup(kind)) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch {
                        if (snackbarHostState.showSnackbar(appliedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                            editing.undo()
                        }
                    }
                }
            }
        }
    }
    // Copy and Highlight float by the selected text, within reach, instead of replacing the top bar (plan U21).
    val selectionBar: @Composable () -> Unit = {
        val range = selection.range
        val page = selection.key?.toIntOrNull()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = copySelection) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.viewer_selection_copy), modifier = Modifier.padding(start = 8.dp))
                }
                // Marked up here, on the page being read (plan V-a): the armed markup tool, or a highlight.
                val tools = pageTools
                if (range != null && page != null && tools != null) {
                    val kind = applyKind
                    TextButton(onClick = { applyMarkup(kind) }) {
                        Icon(Icons.Outlined.BorderColor, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(kind.applyLabel()), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    // The floating bar is for reading and for the other tools; a markup tool applies on release, no bar needed.
    val pageSelectionBar = selectionBar.takeIf { armedTool?.kind == null }

    // The bars float over the pages, which always fill the screen: showing or hiding them (plan U20) never
    // resizes the page area, so the document doesn't move or re-render under the reader's finger.
    val topBar: @Composable () -> Unit = {
            if (searchOpen) {
                SearchTopBar(
                    query = searchText,
                    onQueryChange = {
                        searchText = it
                        search.setQuery(it)
                    },
                    state = searchState,
                    onPrevious = search::previous,
                    onNext = search::next,
                    onClose = closeSearch,
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            // A tap opens "Go to page" (plan U13), as in most readers; the menu entry stays.
                            Text(
                                stringResource(R.string.viewer_page_indicator, currentPage + 1, pageCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClickLabel = stringResource(R.string.viewer_menu_go_to_page)) { showGoTo = true }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = requestExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        // "Save" as a word whenever there is something to save, as in the edit screen (plan U16).
                        if (edits.hasUnsavedChanges) {
                            TextButton(onClick = { showSaveDialog = true }, enabled = !saving) { Text(stringResource(R.string.edit_save)) }
                        }
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_search))
                        }
                        if (!annotating && !filling) {
                            IconToggleButton(checked = showThumbnails, onCheckedChange = { showThumbnails = it }) {
                                Icon(Icons.Outlined.ViewCarousel, contentDescription = stringResource(R.string.cd_thumbnails))
                            }
                        }
                        ViewerMenu(
                            readingMode = readingMode,
                            onReadingModeChange = onReadingModeChange,
                            onGoToPage = { showGoTo = true },
                            onInfo = { showInfo = true },
                            uri = state.uri.toUri(),
                        )
                    },
                )
            }
    }

    Scaffold(
        // No insets: the page area is the whole screen; each floating element pads itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            Column(Modifier.navigationBarsPadding().padding(bottom = if (toolBarVisible && !railed) toolBarHeight else 0.dp)) {
                (save.state as? SaveUiState.Saved)?.let { saved ->
                    SavedSnackbar(
                        onOpen = {
                            save.onDismissResult()
                            onOpenCopy(saved.uri)
                        },
                        onShare = { shareCopy(context, saved.uri, shareTitle) },
                        onDismiss = save.onDismissResult,
                    )
                }
                SnackbarHost(snackbarHostState)
            }
        },
    ) { padding ->
        val pageDescription = stringResource(R.string.cd_viewer_page, currentPage + 1, pageCount)
        val nextPageLabel = stringResource(R.string.cd_viewer_next_page)
        val previousPageLabel = stringResource(R.string.cd_viewer_previous_page)
        // The pages are drawn on a Canvas, which TalkBack can't read: the container says where the
        // reader is and offers page turns (the scrubber and the thumbnails still jump anywhere).
        Box(
            Modifier.padding(padding).fillMaxSize().semantics {
                contentDescription = pageDescription
                customActions = listOf(
                    CustomAccessibilityAction(nextPageLabel) {
                        if (currentPage < pageCount - 1) jumps.trySend(currentPage + 1)
                        currentPage < pageCount - 1
                    },
                    CustomAccessibilityAction(previousPageLabel) {
                        if (currentPage > 0) jumps.trySend(currentPage - 1)
                        currentPage > 0
                    },
                )
            },
        ) {
            Crossfade(
                targetState = readingMode,
                animationSpec = tween(MODE_CROSSFADE_MS),
                label = "readingMode",
                modifier = Modifier.fillMaxSize(),
            ) { mode ->
                when (mode) {
                    ReadingMode.CONTINUOUS -> ContinuousPages(state, budget, currentPage, jumps, reveals, highlights, annotations, pageSelection, onLongPress, onTap, pageSelectionBar, onSelectionReleased, drawing, viewportFill, reportPage)
                    ReadingMode.SINGLE_PAGE -> SinglePages(state, budget, currentPage, jumps, reveals, highlights, annotations, pageSelection, onLongPress, onTap, pageSelectionBar, onSelectionReleased, drawing, viewportFill, reportPage)
                }
            }

            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                AnimatedVisibility(
                    visible = searchOpen || !immersive,
                    enter = slideInVertically(tween(PANEL_MS)) { -it } + fadeIn(tween(PANEL_MS)),
                    exit = slideOutVertically(tween(PANEL_MS)) { -it } + fadeOut(tween(PANEL_MS)),
                ) { topBar() }
                // Non-blocking progress (spec §6.7): reading goes on while the file is written.
                (save.state as? SaveUiState.Saving)?.let { saving ->
                    if (saving.fraction > 0f) {
                        LinearProgressIndicator(progress = { saving.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (searchOpen) {
                    if (searchState.isIndexing) {
                        val indexingDescription = stringResource(R.string.cd_search_indexing)
                        LinearProgressIndicator(
                            progress = { searchState.indexedPages.toFloat() / pageCount.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = indexingDescription },
                        )
                    }
                    SearchNotice(searchState, Modifier.fillMaxWidth())
                }
            }

            PageScrubber(
                pageCount = pageCount,
                currentPage = currentPage,
                onPageSelected = { jumps.trySend(it) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(
                    bottom = when {
                        showThumbnails -> ThumbnailBarHeight
                        toolBarVisible && !railed -> toolBarHeight
                        else -> 0.dp
                    },
                    end = if (toolBarVisible && railed) toolBarWidth else 0.dp,
                ),
            )

            // A few seconds when a tool is armed, over the page rather than a permanent row (plan U7).
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = if (immersive) 0.dp else TOP_BAR_HEIGHT)) {
                if (fillArmed) {
                    // Shown for a few seconds when the fill tool or the selection changes (plan U7).
                    val hint = when {
                        fillTools.selected != null -> stringResource(R.string.fill_hint_move)
                        fillTools.tool != null -> stringResource(R.string.fill_hint_place)
                        form?.hasFields == true -> stringResource(R.string.fill_hint_fields)
                        else -> null
                    }
                    val signatureArmed = fillTools.tool == FillTool.SIGNATURE && fillTools.selected == null
                    TransientHint(
                        hint,
                        key = Triple(fillTools.tool, fillTools.selected != null, fillTools.image?.uri),
                        action = if (signatureArmed) stringResource(R.string.fill_change_signature) to { showSignatureSheet = true } else null,
                    )
                } else {
                    TransientHint(armedTool?.hint(), key = armedTool)
                }
            }

            // The tools (plan V-b): over the pages, a bar at the bottom or a rail at the end in landscape; gone in full screen.
            AnimatedVisibility(
                visible = toolBarVisible,
                enter = if (railed) slideInHorizontally(tween(PANEL_MS)) { it } else slideInVertically(tween(PANEL_MS)) { it },
                exit = if (railed) slideOutHorizontally(tween(PANEL_MS)) { it } else slideOutVertically(tween(PANEL_MS)) { it },
                modifier = if (railed) {
                    // Under the top bar, which spans the whole width.
                    Modifier.align(Alignment.CenterEnd).systemBarsPadding().padding(top = TOP_BAR_HEIGHT)
                } else {
                    Modifier.align(Alignment.BottomCenter)
                },
            ) {
                Box(
                    Modifier.onSizeChanged {
                        toolBarHeight = with(density) { it.height.toDp() }
                        toolBarWidth = with(density) { it.width.toDp() }
                    },
                ) {
                    ViewerToolBar(
                        state = annotateTools,
                        armed = armed,
                        editable = pageTools != null,
                        undoRedo = if (edits.session.canUndo || edits.session.canRedo) {
                            UndoRedo(edits.session.canUndo, edits.session.canRedo, { editing.undo() }, { editing.redo() })
                        } else {
                            null
                        },
                        side = railed,
                        onGroup = onGroup,
                        filling = fillArmed,
                        onFill = onFill,
                        onPages = openPages,
                        fillTools = {
                            FillToolButtons(
                                state = fillTools,
                                selected = fillTools.selected?.let { id -> fillContent.overlays.firstOrNull { it.id == id } },
                                onDelete = { editing.removeOverlay(it.id) },
                                onSignature = onSignature,
                                onPickSignature = { showSignatureSheet = true },
                            )
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = showThumbnails,
                enter = slideInVertically(tween(PANEL_MS)) { it },
                exit = slideOutVertically(tween(PANEL_MS)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ThumbnailBar(
                    pageSizes = state.pageSizes,
                    currentPage = currentPage,
                    thumbnails = state.thumbnails,
                    onPageSelected = { jumps.trySend(it) },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = pageCount,
            onGo = {
                showGoTo = false
                jumps.trySend(it)
            },
            onDismiss = { showGoTo = false },
        )
    }
    if (showInfo) {
        DocumentInfoDialog(
            name = state.displayName.orEmpty(),
            pageCount = pageCount,
            sizeBytes = state.sizeBytes,
            onDismiss = { showInfo = false },
        )
    }
    if (showSaveDialog) {
        SaveDialog(
            overwrite = save.overwriteChoice && save.canOverwrite,
            canOverwrite = save.canOverwrite,
            onOverwriteChange = save.onOverwriteChange,
            onConfirm = startSave,
            onDismiss = {
                showSaveDialog = false
                afterSave = null
            },
            // "Make final" for the form is offered once it has been read and has fields (spec §6.5).
            flatten = if (form?.hasFields == true) save.flattenForm ?: edits.session.fill.hasSignature else null,
            onFlattenChange = save.onFlattenFormChange,
            flattenInk = if (edits.session.annotations.hasInk) save.flattenInk else null,
            onFlattenInkChange = save.onFlattenInkChange,
        )
    }
    fillTools.textTarget?.let { target ->
        val existing = target.overlayId?.let { id -> fillContent.overlays.firstOrNull { it.id == id } as? TextOverlay }
        TextOverlayDialog(
            initialText = existing?.text ?: if (target.isDate) todayText(context) else "",
            initialSize = existing?.fontSize ?: TextBlock.DEFAULT_FONT_SIZE,
            isNew = existing == null,
            onConfirm = { raw, fontSize ->
                fillTools.textTarget = null
                val text = sanitized(raw)
                if (text.isNotBlank()) {
                    val (width, height) = overlayPainter.value.textBoxSize(text, fontSize)
                    if (existing != null) {
                        editing.updateOverlay(existing.copy(text = text, fontSize = fontSize, box = OverlayGeometry.resizedFromTopLeft(existing.box, width, height)))
                    } else {
                        val page = ViewerEditSession.pageIndexOf(target.pageId)
                        val overlay = page?.let { pageTools?.newText(it, Offset(target.displayX, target.displayY), text, fontSize, width, height) }
                        if (overlay != null && editing.addOverlay(overlay)) fillTools.selected = overlay.id
                    }
                }
            },
            onDismiss = { fillTools.textTarget = null },
        )
    }
    if (showUnsaved) {
        UnsavedChangesDialog(
            onSave = {
                showUnsaved = false
                afterSave = AFTER_SAVE_LEAVE
                showSaveDialog = true
            },
            onDiscard = {
                showUnsaved = false
                editing.discard()
                fillTools.back()
                onBack()
            },
            onDismiss = { showUnsaved = false },
        )
    }
    if (showSaveFirst) {
        UnsavedChangesDialog(
            titleRes = R.string.viewer_save_first_title,
            messageRes = R.string.viewer_save_first_message,
            onSave = {
                showSaveFirst = false
                afterSave = AFTER_SAVE_PAGES
                showSaveDialog = true
            },
            onDiscard = {
                showSaveFirst = false
                editing.discard()
                fillTools.back()
                onOpenPages(state.uri, currentPage, false)
            },
            onDismiss = { showSaveFirst = false },
        )
    }
    (save.state as? SaveUiState.Failed)?.let { failed ->
        SaveErrorDialog(failed.failure, onDismiss = save.onDismissResult)
    }
}

/** Hides the status and navigation bars while [immersive]; they come back on a swipe from the edge, and on leaving. */
@Composable
private fun ImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller?.hide(WindowInsetsCompat.Type.systemBars()) else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Overflow menu (spec §4.2): reading mode, go to page, share, document information. */
@Composable
private fun ViewerMenu(
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    onGoToPage: () -> Unit,
    onInfo: () -> Unit,
    uri: Uri,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.viewer_share_title)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModeItem(R.string.viewer_menu_mode_continuous, readingMode == ReadingMode.CONTINUOUS) {
                expanded = false
                onReadingModeChange(ReadingMode.CONTINUOUS)
            }
            ModeItem(R.string.viewer_menu_mode_single, readingMode == ReadingMode.SINGLE_PAGE) {
                expanded = false
                onReadingModeChange(ReadingMode.SINGLE_PAGE)
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_menu_go_to_page)) },
                onClick = {
                    expanded = false
                    onGoToPage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_menu_share)) },
                onClick = {
                    expanded = false
                    sharePdf(context, uri, shareTitle)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_menu_info)) },
                onClick = {
                    expanded = false
                    onInfo()
                },
            )
        }
    }
}

@Composable
private fun ModeItem(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        onClick = onClick,
    )
}

/** Hands the document itself to the system share sheet (read access granted to the receiver). */
private fun sharePdf(context: android.content.Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: Exception) {
        // No app to share with, or a file:// URI the system refuses to hand out: nothing to do.
    }
}

@Composable
private fun ContinuousPages(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    startPage: Int,
    jumps: Channel<Int>,
    reveals: Channel<RevealRequest>,
    highlights: SearchHighlights,
    annotations: AnnotationLayer,
    selection: TextSelectionState?,
    onLongPress: (Int, Offset) -> Unit,
    onTap: (PageTap?) -> Unit,
    selectionBar: (@Composable () -> Unit)?,
    onSelectionReleased: () -> Unit,
    drawing: ViewportDrawing?,
    fill: ViewportFillContent?,
    onPageChanged: (Int) -> Unit,
) {
    val viewportState = rememberSaveable(saver = PdfViewportState.Saver) {
        PdfViewportState(ViewportAnchor(startPage, pageFractionY = 0f, contentFractionX = 0.5f, zoom = 1f))
    }
    LaunchedEffect(viewportState) {
        snapshotFlow { viewportState.currentPage }.collect { if (it >= 0) onPageChanged(it) }
    }
    LaunchedEffect(viewportState, jumps) {
        jumps.receiveAsFlow().collect { viewportState.jumpToPage(it) }
    }
    LaunchedEffect(viewportState, reveals) {
        reveals.receiveAsFlow().collect { viewportState.centerOnPageRect(it.match.pageIndex, it.match.bounds) }
    }
    PdfViewport(
        pageSizes = state.pageSizes,
        bitmaps = state.bitmaps,
        budget = budget,
        state = viewportState,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        highlights = highlights,
        annotations = annotations,
        selection = selection,
        onLongPress = onLongPress,
        onTap = onTap,
        selectionBar = selectionBar,
        onSelectionReleased = onSelectionReleased,
        drawing = drawing,
        fill = fill,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * One page per screen, swipe to turn (spec §4.2). Each page is a [PdfViewport] of its own with its
 * own zoom; at fit width, or at the edge of a zoomed page, a horizontal drag goes to the pager.
 * The pager keeps the neighbours composed, which also prepares their bitmaps.
 */
@Composable
private fun SinglePages(
    state: ViewerUiState.Ready,
    budget: RenderBudget,
    startPage: Int,
    jumps: Channel<Int>,
    reveals: Channel<RevealRequest>,
    highlights: SearchHighlights,
    annotations: AnnotationLayer,
    selection: TextSelectionState?,
    onLongPress: (Int, Offset) -> Unit,
    onTap: (PageTap?) -> Unit,
    selectionBar: (@Composable () -> Unit)?,
    onSelectionReleased: () -> Unit,
    drawing: ViewportDrawing?,
    fill: ViewportFillContent?,
    onPageChanged: (Int) -> Unit,
) {
    // A search result waits here until its page is composed and has a layout, then it is centred.
    var pendingReveal by remember { mutableStateOf<RevealRequest?>(null) }
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, state.pageSizes.size - 1)) { state.pageSizes.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
    }
    LaunchedEffect(pagerState, jumps) {
        jumps.receiveAsFlow().collect { pagerState.scrollToPage(it) }
    }
    LaunchedEffect(pagerState, reveals) {
        reveals.receiveAsFlow().collect {
            pendingReveal = it
            pagerState.scrollToPage(it.match.pageIndex)
        }
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        key = { it },
        // A brush takes every one-finger drag, so the pages don't turn under it.
        userScrollEnabled = drawing == null,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val viewportState = rememberSaveable(page, saver = PdfViewportState.Saver) { PdfViewportState() }
        LaunchedEffect(viewportState, pendingReveal) {
            pendingReveal?.takeIf { it.match.pageIndex == page }?.let {
                viewportState.centerOnPageRect(0, it.match.bounds)
                pendingReveal = null
            }
        }
        PdfViewport(
            pageSizes = listOf(state.pageSizes[page]),
            bitmaps = state.bitmaps,
            budget = budget,
            state = viewportState,
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            pageIndexOffset = page,
            requestSource = page,
            yieldHorizontalToParent = true,
            highlights = highlights,
            annotations = annotations,
            selection = selection,
            onLongPress = onLongPress,
            onTap = onTap,
            selectionBar = selectionBar,
            onSelectionReleased = onSelectionReleased,
            // One ink layer at a time (the library's advice): only on the page that is shown.
            drawing = drawing?.takeIf { pagerState.settledPage == page },
            fill = fill,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The text model of page [index] for selecting, or null if the page can't be read. */
private suspend fun PageTextReader.selectionModel(index: Int): TextSelection? =
    try {
        TextSelection(page(index))
    } catch (e: java.io.IOException) {
        null
    }
