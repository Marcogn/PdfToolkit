package com.marcogn.pdftoolkit.ui.recents

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-page thumbnail of a recent, decoded off the main thread. While it loads, or if the file
 * is gone (the cache was cleared), a PDF icon takes its place.
 */
@Composable
fun RecentThumbnail(path: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let { withContext(Dispatchers.IO) { decode(it) } }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Outlined.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private fun decode(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return BitmapFactory.decodeFile(path)?.asImageBitmap()
}
