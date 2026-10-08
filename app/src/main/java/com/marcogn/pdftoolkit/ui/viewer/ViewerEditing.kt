package com.marcogn.pdftoolkit.ui.viewer

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationEraser
import com.marcogn.pdftoolkit.pdf.annotations.DocumentAnnotations
import com.marcogn.pdftoolkit.pdf.annotations.ErasePick
import com.marcogn.pdftoolkit.pdf.annotations.FreehandGeometry
import com.marcogn.pdftoolkit.pdf.annotations.FreehandStroke
import com.marcogn.pdftoolkit.pdf.annotations.MarkupFactory
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.toPageSpace
import com.marcogn.pdftoolkit.pdf.text.LineRun
import com.marcogn.pdftoolkit.ui.edit.SaveUiState

/** What the system back does in the viewer, in this order (plan V-a). */
enum class ViewerBackStep {
    CLOSE_SEARCH,
    CLEAR_SELECTION,

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
): ViewerBackStep = when {
    searchOpen -> ViewerBackStep.CLOSE_SEARCH
    selectionActive -> ViewerBackStep.CLEAR_SELECTION
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
    val onOverwriteChange: (Boolean) -> Unit,
    val onFlattenInkChange: (Boolean) -> Unit,
    val onSave: (destination: Uri, overwrite: Boolean) -> Unit,
    val suggestedCopyName: (suffix: String) -> String,
    val onDismissResult: () -> Unit,
)

/**
 * The page tools of the viewer on its [session] (plan V-a): each turns a gesture on a page into an
 * edit, through the page's user space, which [document] gives (the box of every page). Document
 * page indices throughout; the session's page ids are [ViewerEditSession.pageId].
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
}
