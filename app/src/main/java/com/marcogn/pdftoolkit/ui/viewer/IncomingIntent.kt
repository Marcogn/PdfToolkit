package com.marcogn.pdftoolkit.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The PDF an external app asked us to show (spec §4, phase 1): the data of `ACTION_VIEW` (file
 * manager, browser downloads, mail) or the stream of `ACTION_SEND` (the share sheet). Null for a
 * normal launch and for anything that isn't one of those two.
 */
fun Intent.pdfUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}

/**
 * Keeps access to [uri] across restarts, so recents can reopen it, and write access too when the
 * provider gave it, so "overwrite" can be offered (spec §6.7). Providers that only grant temporary
 * access (most `VIEW`/`SEND` intents) refuse: the file still opens now, it just may not be
 * available from recents later.
 */
fun Context.takePersistableAccess(uri: Uri) {
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    try {
        contentResolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (e: SecurityException) {
        // No write grant: read only.
        try {
            contentResolver.takePersistableUriPermission(uri, read)
        } catch (e: SecurityException) {
            // Not persistable: see above.
        }
    }
}
