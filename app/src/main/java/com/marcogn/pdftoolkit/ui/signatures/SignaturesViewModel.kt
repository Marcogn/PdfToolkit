package com.marcogn.pdftoolkit.ui.signatures

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.images.ImageImporter
import com.marcogn.pdftoolkit.data.settings.SignaturePreferences
import com.marcogn.pdftoolkit.data.signatures.CropFractions
import com.marcogn.pdftoolkit.data.signatures.Signature
import com.marcogn.pdftoolkit.data.signatures.SignatureRendering
import com.marcogn.pdftoolkit.data.signatures.SignatureRepository
import com.marcogn.pdftoolkit.domain.signature.InkStroke
import com.marcogn.pdftoolkit.domain.signature.SignatureType
import com.marcogn.pdftoolkit.pdf.edit.PageImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** The signature archive and the two ways to add to it (spec §6.5), for "My signatures" and for the fill pane. */
@HiltViewModel
class SignaturesViewModel @Inject constructor(
    private val repository: SignatureRepository,
    private val imageImporter: ImageImporter,
    private val imageLoader: PageImageLoader,
    private val preferences: SignaturePreferences,
) : ViewModel() {

    /** Null until the first load, so the empty message doesn't flash. */
    val signatures: StateFlow<List<Signature>?> = repository.signatures
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Null until read. */
    val legalNoteSeen: StateFlow<Boolean?> = preferences.legalNoteSeen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun file(signature: Signature): File = repository.file(signature)

    fun markLegalNoteSeen() {
        viewModelScope.launch { preferences.setLegalNoteSeen() }
    }

    fun rename(signature: Signature, name: String) {
        viewModelScope.launch { repository.rename(signature.id, name.trim()) }
    }

    fun setDefault(signature: Signature, default: Boolean) {
        viewModelScope.launch { repository.setDefault(signature.id, default) }
    }

    fun delete(signature: Signature) {
        viewModelScope.launch { repository.delete(signature) }
    }

    suspend fun suggestedName(prefix: String): String = repository.suggestedName(prefix)

    /** Renders and saves a drawing. Null if nothing was drawn or the file can't be written. */
    suspend fun saveDrawn(strokes: List<InkStroke>, basePx: Float, name: String): Signature? {
        val bitmap = withContext(Dispatchers.Default) { SignatureRendering.render(strokes, basePx) } ?: return null
        return repository.save(bitmap, name, SignatureType.DRAWN)
    }

    /** Copies the picked image and decodes it for the crop screen; null if it can't be read. */
    suspend fun loadImport(uri: Uri): Bitmap? {
        val copy = imageImporter.import(uri) ?: return null
        return withContext(Dispatchers.IO) { imageLoader.thumbnail(copy, SignatureRendering.MAX_SIDE_PX) }
    }

    /** The picked image after the crop and the background removal, as it would be saved; null if nothing is left. */
    suspend fun previewImport(source: Bitmap, crop: CropFractions, removeBackground: Boolean, threshold: Float): Bitmap? =
        withContext(Dispatchers.Default) { SignatureRendering.processImport(source, crop, removeBackground, threshold) }

    suspend fun saveImported(bitmap: Bitmap, name: String): Signature? = repository.save(bitmap, name, SignatureType.IMPORTED)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
