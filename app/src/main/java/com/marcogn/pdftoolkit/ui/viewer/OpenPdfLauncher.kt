package com.marcogn.pdftoolkit.ui.viewer

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private const val PDF_MIME = "application/pdf"

/**
 * SAF picker for one PDF (spec §3.4), no storage permission. Returns the function that opens it;
 * [onPicked] receives the chosen document, with a persistable read permission already taken so
 * that recents can reopen it later.
 */
@Composable
fun rememberOpenPdfLauncher(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.takePersistableReadPermission(uri)
            onPicked(uri)
        }
    }
    return {
        try {
            launcher.launch(arrayOf(PDF_MIME))
        } catch (e: ActivityNotFoundException) {
            // No document picker on the device: nothing to open.
        }
    }
}
