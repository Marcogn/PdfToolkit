package com.marcogn.pdftoolkit.ui.signatures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Longest side a list thumbnail is decoded at. */
private const val THUMBNAIL_SIDE_PX = 480

/** A saved signature on paper white, whatever the theme: signatures are dark ink. */
@Composable
fun SignatureThumbnail(file: File, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val bitmap by produceState<Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeScaled(file) }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.White) }
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun decodeScaled(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_SIDE_PX) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** A grey-and-white chequer under [content] so transparent parts of a picture show as such. */
@Composable
fun Checkerboard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val cell = with(LocalDensity.current) { 8.dp.toPx() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFFFFFFFF))
            var row = 0
            var y = 0f
            while (y < size.height) {
                var x = if (row % 2 == 0) cell else 0f
                while (x < size.width) {
                    drawRect(Color(0xFFE0E0E0), Offset(x, y), Size(minOf(cell, size.width - x), minOf(cell, size.height - y)))
                    x += 2 * cell
                }
                y += cell
                row++
            }
        }
        content()
    }
}
