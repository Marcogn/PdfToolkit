package com.marcogn.pdftoolkit.ui.merge

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfOpenException
import com.marcogn.pdftoolkit.pdf.edit.PdfEditor
import com.marcogn.pdftoolkit.pdf.render.PageThumbnails
import com.marcogn.pdftoolkit.pdf.render.PdfDocumentOpener
import com.marcogn.pdftoolkit.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** One PDF in the merge list (spec §6.6): first-page preview, name and page count. [hasForm]: it has AcroForm fields. */
data class MergeEntry(
    val id: String,
    val uri: String,
    val name: String,
    val pageCount: Int,
    val thumbnail: Bitmap?,
    val hasForm: Boolean,
)

sealed interface MergeEvent {
    /** [name] is protected by a password: merging such files isn't supported yet. */
    data class Protected(val name: String) : MergeEvent
    data class Unreadable(val name: String) : MergeEvent
}

/**
 * The list of PDFs to merge. It only reads what the list shows (name, page count, a first-page
 * preview); the merge itself is an edit session built by the edit screen, so no merge logic lives here.
 *
 * The URIs, in order, are kept in [SavedStateHandle]: after process death the list is rebuilt from them.
 */
@HiltViewModel
class MergeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val opener: PdfDocumentOpener,
    private val editor: PdfEditor,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<MergeEntry>>(emptyList())
    val entries: StateFlow<List<MergeEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(0)
    /** True while files are being read. */
    val loading: StateFlow<Boolean> = _loading.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _events = Channel<MergeEvent>(Channel.BUFFERED)
    val events: Flow<MergeEvent> = _events.receiveAsFlow()

    private val pending = ArrayDeque<Uri>()
    private var worker: Job? = null

    init {
        val initial = savedStateHandle.get<ArrayList<String>>(KEY_URIS) ?: ArrayList(savedStateHandle.toRoute<Destination.Merge>().uris)
        add(initial.map { it.toUri() })
    }

    /** Appends [uris] at the end, reading them one after the other so the order picked is kept. */
    fun add(uris: List<Uri>) {
        pending.addAll(uris)
        if (worker?.isActive == true) return
        worker = viewModelScope.launch {
            while (pending.isNotEmpty()) {
                val uri = pending.removeFirst()
                _loading.update { it + 1 }
                try {
                    load(uri)
                } finally {
                    _loading.update { it - 1 }
                }
            }
        }
    }

    private suspend fun load(uri: Uri) {
        try {
            val opened = opener.open(uri)
            val name = opened.displayName ?: uri.toString()
            val pageCount = opened.renderer.pageCount
            val thumbnail = try {
                PageThumbnails(opened.renderer, THUMBNAIL_HEIGHT_PX).get(0)
            } finally {
                opened.renderer.close()
            }
            val hasForm = editor.hasFormFields { context.contentResolver.openInputStream(uri) }
            _entries.update { it + MergeEntry(UUID.randomUUID().toString(), uri.toString(), name, pageCount, thumbnail, hasForm) }
            persist()
        } catch (e: PdfOpenException) {
            val name = uri.lastPathSegment ?: uri.toString()
            _events.trySend(
                if (e.failure == OpenFailure.PASSWORD_PROTECTED || e.failure == OpenFailure.PASSWORD_UNSUPPORTED) {
                    MergeEvent.Protected(name)
                } else {
                    MergeEvent.Unreadable(name)
                },
            )
        }
    }

    fun move(from: Int, to: Int) {
        _entries.update { list ->
            if (from !in list.indices || to !in list.indices) return@update list
            list.toMutableList().apply { add(to, removeAt(from)) }
        }
        persist()
    }

    fun remove(id: String) {
        _entries.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    private fun persist() {
        savedStateHandle[KEY_URIS] = ArrayList(_entries.value.map { it.uri })
    }

    private companion object {
        const val KEY_URIS = "uris"
        const val THUMBNAIL_HEIGHT_PX = 192
    }
}
