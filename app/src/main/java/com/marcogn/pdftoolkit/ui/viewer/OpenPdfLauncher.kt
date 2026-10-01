package com.marcogn.pdftoolkit.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private const val PDF_MIME = "application/pdf"

/**
 * `OpenDocument` that also asks for write access (and that it can be kept): providers that allow
 * it grant it, which is what makes "overwrite" possible later (spec §6.7). Read-only providers
 * simply grant read access.
 */
private class OpenPdfContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
    )
}

/**
 * SAF picker for one PDF (spec §3.4), no storage permission. Returns the function that opens it;
 * [onPicked] receives the chosen document, with a persistable permission already taken so
 * that recents can reopen it later.
 */
@Composable
fun rememberOpenPdfLauncher(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(OpenPdfContract()) { uri ->
        if (uri != null) {
            context.takePersistableAccess(uri)
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
