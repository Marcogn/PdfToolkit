package com.marcogn.pdftoolkit.ui.signatures

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.signatures.Signature

enum class CreationMode { DRAW, IMPORT }

/**
 * Where the creation of a signature is: the chooser, the drawing or the import. Saved, so a
 * rotation (the drawing screen asks for landscape) doesn't close it.
 */
@Stable
class SignatureCreationState(chooser: Boolean = false, mode: CreationMode? = null, importUri: String? = null, pickerLaunched: Boolean = false) {
    var chooser by mutableStateOf(chooser)
    var mode by mutableStateOf(mode)
    var importUri by mutableStateOf(importUri)
    var pickerLaunched by mutableStateOf(pickerLaunched)

    /** Opens the "draw or import" chooser. */
    fun start() {
        chooser = true
    }

    fun begin(mode: CreationMode) {
        chooser = false
        this.mode = mode
        importUri = null
        pickerLaunched = false
    }

    fun finish() {
        chooser = false
        mode = null
        importUri = null
        pickerLaunched = false
    }

    companion object {
        val Saver = listSaver<SignatureCreationState, Any?>(
            save = { listOf(it.chooser, it.mode?.name, it.importUri, it.pickerLaunched) },
            restore = { SignatureCreationState(it[0] as Boolean, (it[1] as String?)?.let(CreationMode::valueOf), it[2] as String?, it[3] as Boolean) },
        )
    }
}

@Composable
fun rememberSignatureCreationState(): SignatureCreationState = rememberSaveable(saver = SignatureCreationState.Saver) { SignatureCreationState() }

/**
 * The windows of the creation flow (spec §6.5): chooser, the legal note the first time, then the
 * drawing or the picture import. [onCreated] gets the signature once it is in the archive.
 */
@Composable
fun SignatureCreationHost(state: SignatureCreationState, viewModel: SignaturesViewModel, onCreated: (Signature) -> Unit) {
    val noteSeen by viewModel.legalNoteSeen.collectAsStateWithLifecycle()
    val namePrefix = stringResource(R.string.signature_default_name)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) state.finish() else state.importUri = uri.toString()
    }

    if (state.chooser) {
        AlertDialog(
            onDismissRequest = { state.finish() },
            title = { Text(stringResource(R.string.signature_new)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Draw, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.signature_draw)) },
                        modifier = Modifier.clickable { state.begin(CreationMode.DRAW) },
                    )
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.signature_from_image)) },
                        modifier = Modifier.clickable { state.begin(CreationMode.IMPORT) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { state.finish() }) { Text(stringResource(R.string.save_cancel)) } },
        )
    }

    val mode = state.mode ?: return
    // The note comes first, once; null = not read yet, so nothing flashes.
    when (noteSeen) {
        null -> return
        false -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.about_signature_title)) },
                text = { Text(stringResource(R.string.about_signature_body)) },
                confirmButton = { TextButton(onClick = { viewModel.markLegalNoteSeen() }) { Text(stringResource(R.string.signature_note_ok)) } },
                dismissButton = { TextButton(onClick = { state.finish() }) { Text(stringResource(R.string.save_cancel)) } },
            )
            return
        }
        true -> Unit
    }

    when (mode) {
        CreationMode.DRAW -> SignatureDrawDialog(
            onSave = { strokes, basePx ->
                val saved = viewModel.saveDrawn(strokes, basePx, viewModel.suggestedName(namePrefix))
                state.finish()
                if (saved != null) onCreated(saved)
            },
            onDismiss = { state.finish() },
        )
        CreationMode.IMPORT -> {
            LaunchedEffect(state.pickerLaunched) {
                if (!state.pickerLaunched) {
                    state.pickerLaunched = true
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            val uri = state.importUri
            if (uri != null) ImportFlow(uri, state, viewModel, namePrefix, onCreated)
        }
    }
}

@Composable
private fun ImportFlow(uri: String, state: SignatureCreationState, viewModel: SignaturesViewModel, namePrefix: String, onCreated: (Signature) -> Unit) {
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var unreadable by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val bitmap = viewModel.loadImport(uri.toUri())
        if (bitmap == null) unreadable = true else source = bitmap
    }
    if (unreadable) {
        AlertDialog(
            onDismissRequest = { state.finish() },
            text = { Text(stringResource(R.string.fill_image_unreadable)) },
            confirmButton = { TextButton(onClick = { state.finish() }) { Text(stringResource(R.string.signature_note_ok)) } },
        )
        return
    }
    SignatureImportDialog(
        source = source,
        preview = { crop, remove, threshold -> source?.let { viewModel.previewImport(it, crop, remove, threshold) } },
        onSave = { bitmap ->
            val saved = viewModel.saveImported(bitmap, viewModel.suggestedName(namePrefix))
            state.finish()
            if (saved != null) onCreated(saved)
        },
        onDismiss = { state.finish() },
    )
}
