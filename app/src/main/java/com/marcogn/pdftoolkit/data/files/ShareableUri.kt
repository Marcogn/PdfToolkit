package com.marcogn.pdftoolkit.data.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Authority of the `FileProvider` that hands files opened by path to other apps (manifest, `file_paths.xml`). */
fun Context.fileProviderAuthority(): String = "$packageName.files"

/**
 * [uri] as another app may receive it: a file opened by path (ADR 0007) becomes a `content://` URI of
 * the app's `FileProvider`, since Android refuses to hand out `file://`. Anything else, or a path the
 * provider doesn't cover, is returned as it is.
 */
fun Context.shareableUri(uri: Uri): Uri {
    if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
    val path = uri.path ?: return uri
    return try {
        FileProvider.getUriForFile(this, fileProviderAuthority(), File(path))
    } catch (e: IllegalArgumentException) {
        uri
    }
}
