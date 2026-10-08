package com.marcogn.pdftoolkit.ui.viewer

import androidx.lifecycle.SavedStateHandle
import com.marcogn.pdftoolkit.data.save.SaveRequest
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.edit.EditSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** What the viewer's editing shows: the [session] and whether it holds changes not saved yet. */
data class ViewerEdits(val session: EditSession, val hasUnsavedChanges: Boolean)

/**
 * The edit session of the viewer (plan V-a, ADR 0005): what lies **on** the pages of the file as it
 * is saved (annotations, and fill content from V-c), never the page list. Its pages are the
 * document's own, in order and unturned, with ids `p<index>` ([pageId]), so the viewer keeps
 * rendering the file with `PdfRenderer` and only draws the pending edits on top.
 *
 * Undo and redo are the session's. The edits (not the history) are kept in [handle], so a screen
 * rotation and process death restore them; after a process death a session that had been saved as
 * a copy counts as unsaved again, which only makes the exit ask once more.
 */
class ViewerEditSession(private val handle: SavedStateHandle, private val pageCount: Int) {

    private val _edits = MutableStateFlow(ViewerEdits(restore(), hasUnsavedChanges = false))
    val edits: StateFlow<ViewerEdits> = _edits.asStateFlow()

    /** [key] of what was last saved, null before any save. */
    private var savedKey: String? = null

    init {
        publish(_edits.value.session)
    }

    val session: EditSession get() = _edits.value.session
    val hasUnsavedChanges: Boolean get() = _edits.value.hasUnsavedChanges

    fun newAnnotationId(): String = "a" + UUID.randomUUID().toString().take(ID_LENGTH)

    fun addAnnotation(annotation: NewAnnotation) = apply { it.addAnnotation(annotation) }

    fun removeAnnotation(id: String) = apply { it.removeAnnotation(id) }

    fun removeExistingAnnotation(ref: AnnotationRef) = apply { it.removeExistingAnnotation(ref) }

    fun undo() = apply { it.undo() }

    fun redo() = apply { it.redo() }

    /** Back to the file as it is: every edit and the history go. */
    fun discard() {
        savedKey = null
        publish(EditSession.of(pageCount))
    }

    /**
     * What the background save needs to write the current session over [sourceUri] into
     * [destinationUri] (the same file for an overwrite). [flattenForm] null: on when a signature was
     * placed (spec §6.5); [flattenInk] applies only when strokes were drawn.
     */
    fun saveRequest(sourceUri: String, destinationUri: String, flattenForm: Boolean?, flattenInk: Boolean): SaveRequest {
        val current = session
        return SaveRequest(
            sourceUri = sourceUri,
            destinationUri = destinationUri,
            sourcePageCount = pageCount,
            pages = current.encode(),
            fill = current.encodeFill(),
            flattenForm = flattenForm ?: current.fill.hasSignature,
            annotations = current.encodeAnnotations(),
            flattenInk = flattenInk && current.annotations.hasInk,
        )
    }

    /** The key of the session a save was started for, to hand back to [markSaved] when it is done. */
    fun currentKey(): String = key(session)

    /** The save of the session with [key] is done: nothing to save until the next change. */
    fun markSaved(key: String) {
        savedKey = key
        publish(session)
    }

    /** True if the operation changed something. */
    private fun apply(operation: (EditSession) -> EditSession): Boolean {
        val current = session
        val updated = operation(current)
        if (updated === current) return false
        publish(updated)
        return true
    }

    private fun publish(session: EditSession) {
        handle[KEY_FILL] = session.encodeFill()
        handle[KEY_ANNOTATIONS] = session.encodeAnnotations()
        _edits.value = ViewerEdits(session, hasUnsavedChanges = session.isModified && key(session) != savedKey)
    }

    private fun restore(): EditSession {
        val fill = handle.get<String>(KEY_FILL).orEmpty()
        val annotations = handle.get<String>(KEY_ANNOTATIONS).orEmpty()
        val fresh = EditSession.of(pageCount)
        if (fill.isEmpty() && annotations.isEmpty()) return fresh
        return EditSession.decode(fresh.encode(), pageCount, fill, annotations) ?: fresh
    }

    private fun key(session: EditSession) = session.encodeFill() + "\n" + session.encodeAnnotations()

    companion object {
        // Not the route's argument names (`uri`): SavedStateHandle holds those too.
        private const val KEY_FILL = "viewerFill"
        private const val KEY_ANNOTATIONS = "viewerAnnotations"
        private const val ID_LENGTH = 8
        private const val PAGE_ID_PREFIX = "p"

        /** The session's id of page [index] of the document (the ids [EditSession.of] gives). */
        fun pageId(index: Int): String = PAGE_ID_PREFIX + index

        /** Inverse of [pageId]; null for an id that isn't one of the document's pages. */
        fun pageIndexOf(id: String): Int? = id.removePrefix(PAGE_ID_PREFIX).takeIf { id.startsWith(PAGE_ID_PREFIX) }?.toIntOrNull()
    }
}
