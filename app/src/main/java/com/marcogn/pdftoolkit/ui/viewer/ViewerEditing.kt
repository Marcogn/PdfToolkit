package com.marcogn.pdftoolkit.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkShape
import com.marcogn.pdftoolkit.domain.fill.Overlay
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationEraser
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.annotations.ErasePick
import com.marcogn.pdftoolkit.pdf.annotations.FreehandGeometry
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import com.marcogn.pdftoolkit.pdf.annotations.MarkupFactory
import com.marcogn.pdftoolkit.pdf.render.OverlayGeometry
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.marcogn.pdftoolkit.pdf.text.LineRun
import com.marcogn.pdftoolkit.ui.edit.PickedImage
import com.marcogn.pdftoolkit.ui.edit.SaveUiState

/** What the system back does in the viewer, in this order (plan V-a). */
enum class ViewerBackStep {
    CLOSE_SEARCH,
    CLEAR_SELECTION,

    /** Fill and sign: drops its placement tool or the selected overlay, and stays armed. */
    DROP_FILL_TOOL,

    /** Puts the armed tool down: the tool bar goes, the edits stay. */
    PUT_TOOL_DOWN,

    /** Save / Discard / Cancel (spec §6.1). */
    ASK_TO_SAVE,
    LEAVE,
}

/**
 * The step a back press takes: the innermost thing open goes first, and leaving with unsaved edits
 * asks first. While a save runs, leaving is allowed (the save goes on in the background, ADR 0003).
 */
fun viewerBackStep(
    searchOpen: Boolean,
    selectionActive: Boolean,
    toolArmed: Boolean,
    hasUnsavedChanges: Boolean,
    saving: Boolean,
    fillToolActive: Boolean = false,
): ViewerBackStep = when {
    searchOpen -> ViewerBackStep.CLOSE_SEARCH
    selectionActive -> ViewerBackStep.CLEAR_SELECTION
    fillToolActive -> ViewerBackStep.DROP_FILL_TOOL
    toolArmed -> ViewerBackStep.PUT_TOOL_DOWN
    hasUnsavedChanges && !saving -> ViewerBackStep.ASK_TO_SAVE
    else -> ViewerBackStep.LEAVE
}

/** What the viewer's save UI reads and does (plan V-a); [ViewerScreen] builds it from the view model. */
class ViewerSaveUi(
    val state: SaveUiState,
    val overwriteChoice: Boolean,
    val canOverwrite: Boolean,
    val flattenInk: Boolean,
    /** "Make final" for the form as chosen, null = the default of spec §6.5 (on with a signature). */
    val flattenForm: Boolean?,
    val onOverwriteChange: (Boolean) -> Unit,
    val onFlattenInkChange: (Boolean) -> Unit,
    val onFlattenFormChange: (Boolean) -> Unit,
    val onSave: (destination: Uri, overwrite: Boolean) -> Unit,
    val suggestedCopyName: (suffix: String) -> String,
    val onDismissResult: () -> Unit,
)

/** What "Fill and sign" in the viewer reads and asks of the view model (plan V-c); built by [ViewerScreen]. */
class ViewerFillUi(
    /** The form, null until [load] is first called. */
    val form: FormLoad?,
    val load: () -> Unit,
    /** The text as the PDF will hold it (characters the font lacks are dropped). */
    val sanitize: (String) -> String,
    /** A signature file copied into the app and measured, ready to place; null if unreadable. */
    val importImage: suspend (Uri) -> PickedImage?,
    /** An image overlay's file decoded for the screen. */
    val image: suspend (String) -> Bitmap?,
)

/**
 * The page tools of the viewer on its [session] (plans V-a, V-c): each turns a gesture on a page into
 * an edit, through the page's user space, which [document] gives (the box of every page). Document
 * page indices throughout, points in the page's display points (page points of the viewer); the
 * session's page ids are [ViewerEditSession.pageId]. The fill tools build an overlay and leave adding
 * it to the caller, which may select it.
 */
class ViewerPageTools(private val session: ViewerEditSession, private val document: DocumentAnnotations) {

    /** How page [index] places its user space on the page as displayed; null outside the document. */
    fun spaceOf(index: Int): PdfPageSpace? = document.pageBoxes.getOrNull(index)?.toPageSpace()

    /**
     * A finished freehand [stroke] of [kind] on [page] (its display points) as an Ink annotation in
     * [color], or null if it doesn't show on the page at all (drawn on the background around it).
     */
    fun inkAnnotation(page: Int, stroke: FreehandStroke, kind: FreehandKind, color: AnnotationColor): NewAnnotation? {
        val space = spaceOf(page) ?: return null
        val ink = FreehandGeometry.toInk(stroke, space.displayToUser, highlighter = kind == FreehandKind.HIGHLIGHTER) ?: return null
        if (!FreehandGeometry.touchesPage(ink, space)) return null
        return NewAnnotation(session.newAnnotationId(), ViewerEditSession.pageId(page), ink, AnnotationStyle(color))
    }

    /** Text markup of [kind] in [color] over [runs] (display points of [page]); null without text. */
    fun markupAnnotation(page: Int, runs: List<LineRun>, kind: MarkupKind, color: AnnotationColor): NewAnnotation? {
        val space = spaceOf(page) ?: return null
        return MarkupFactory.build(session.newAnnotationId(), ViewerEditSession.pageId(page), kind, color, runs, space)
    }

    /**
     * The eraser at [point] (display points of [page]), [tolerancePt] points around it: the topmost
     * annotation there, added in this session or already in the file, goes. True if one did.
     */
    fun erase(page: Int, point: Offset, tolerancePt: Float): Boolean {
        val space = spaceOf(page) ?: return false
        val user = space.displayToUser.map(point)
        val edits = session.session.annotations
        val pick = AnnotationEraser.pick(
            added = edits.addedOn(ViewerEditSession.pageId(page)),
            existing = document.on(page),
            removed = edits.removed,
            point = UserPoint(user.x, user.y),
            tolerance = tolerancePt,
        )
        return when (pick) {
            is ErasePick.Added -> session.removeAnnotation(pick.id)
            is ErasePick.Existing -> session.removeExistingAnnotation(pick.ref)
            null -> false
        }
    }

    /** The topmost of [overlays] at [point] of [page] (the one drawn last), or null. */
    fun overlayAt(page: Int, point: Offset, overlays: List<Overlay>): Overlay? {
        val space = spaceOf(page) ?: return null
        val user = space.displayToUser.map(point)
        val pageId = ViewerEditSession.pageId(page)
        return overlays.lastOrNull { it.pageId == pageId && OverlayGeometry.contains(it.box, user) }
    }

    /** A tick or a cross centred on [point] of [page] (spec §6.5). */
    fun newMark(page: Int, point: Offset, kind: MarkKind): MarkOverlay? {
        val space = spaceOf(page) ?: return null
        val box = OverlayGeometry.uprightAt(space, point, MarkShape.DEFAULT_SIZE, MarkShape.DEFAULT_SIZE)
        return MarkOverlay(session.newOverlayId(), ViewerEditSession.pageId(page), box, kind)
    }

    /**
     * [image] (a signature) centred on [point] of [page], upright on screen: 150 pt wide, or 40% of the
     * page if that is narrower, with the image's own proportions.
     */
    fun newImage(page: Int, point: Offset, image: PickedImage): ImageOverlay? {
        val space = spaceOf(page) ?: return null
        val width = minOf(IMAGE_WIDTH_PT, space.displaySize.width * IMAGE_PAGE_FRACTION)
        val height = width * image.dimensions.heightPx / image.dimensions.widthPx.coerceAtLeast(1)
        val box = OverlayGeometry.uprightAt(space, point, width, height)
        return ImageOverlay(session.newOverlayId(), ViewerEditSession.pageId(page), box, image.uri)
    }

    /**
     * [text] at [fontSize] on [page], [width] x [height] points as measured for the screen: its first
     * line is centred vertically on [point], its left edge just left of it, where the finger was.
     */
    fun newText(page: Int, point: Offset, text: String, fontSize: Float, width: Float, height: Float): TextOverlay? {
        val space = spaceOf(page) ?: return null
        val (x, baseline) = TextBlock.lineOrigin(0, fontSize)
        val firstLineMiddle = baseline - (TextBlock.ASCENT - TextBlock.DESCENT) / 2f * fontSize
        val topLeft = Offset(point.x - x, point.y - firstLineMiddle)
        val box = OverlayGeometry.uprightAt(space, topLeft + Offset(width / 2f, height / 2f), width, height)
        return TextOverlay(session.newOverlayId(), ViewerEditSession.pageId(page), box, text, fontSize)
    }

    private companion object {
        const val IMAGE_WIDTH_PT = 150f
        const val IMAGE_PAGE_FRACTION = 0.4f
    }
}
