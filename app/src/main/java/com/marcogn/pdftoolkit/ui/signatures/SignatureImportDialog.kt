package com.marcogn.pdftoolkit.ui.signatures

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.signatures.CropFractions
import com.marcogn.pdftoolkit.domain.signature.BackgroundRemoval
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A crop smaller than this fraction of the picture, per side, can't be asked for. */
private const val MIN_CROP = 0.08f

/** Wait this long after a change before recomputing the preview, so dragging stays smooth. */
private const val PREVIEW_DEBOUNCE_MS = 80L

private val cropSaver = listSaver<CropFractions, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { CropFractions(it[0], it[1], it[2], it[3]) },
)

/**
 * Import of a signature from a picture (spec §6.5): crop it, optionally turn the white background
 * transparent, check the result on a chequer, save. [source] is the decoded picture, null while it loads.
 */
@Composable
fun SignatureImportDialog(
    source: Bitmap?,
    preview: suspend (CropFractions, removeBackground: Boolean, threshold: Float) -> Bitmap?,
    onSave: suspend (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    var crop by rememberSaveable(stateSaver = cropSaver) { mutableStateOf(CropFractions.Full) }
    var removeBackground by rememberSaveable { mutableStateOf(true) }
    var threshold by rememberSaveable { mutableFloatStateOf(BackgroundRemoval.DEFAULT_THRESHOLD) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var computing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source, crop, removeBackground, threshold) {
        if (source == null) return@LaunchedEffect
        computing = true
        delay(PREVIEW_DEBOUNCE_MS)
        result = preview(crop, removeBackground, threshold)
        computing = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (source == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Surface
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.save_cancel)) }
                    Text(stringResource(R.string.signature_import_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(
                        enabled = result != null && !computing && !saving,
                        onClick = {
                            val bitmap = result ?: return@Button
                            saving = true
                            scope.launch {
                                onSave(bitmap)
                                saving = false
                            }
                        },
                    ) { Text(stringResource(R.string.signature_save)) }
                }
                Text(stringResource(R.string.signature_crop_hint), style = MaterialTheme.typography.bodyMedium)
                CropEditor(source, crop, { crop = it }, Modifier.weight(1f).fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.signature_remove_background), modifier = Modifier.weight(1f))
                    Switch(checked = removeBackground, onCheckedChange = { removeBackground = it })
                }
                if (removeBackground) {
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = MIN_THRESHOLD..MAX_THRESHOLD,
                    )
                }
                Checkerboard(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT)) {
                    val shown = result
                    if (shown != null) {
                        Image(shown.asImageBitmap(), contentDescription = stringResource(R.string.signature_preview), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
                    } else if (!computing) {
                        Text(
                            stringResource(R.string.signature_nothing_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private val PREVIEW_HEIGHT = 110.dp
private const val MIN_THRESHOLD = 0.3f
private const val MAX_THRESHOLD = 0.95f

/** The picture fitted in the available space with a crop rectangle: drag a corner to resize it, the inside to move it. */
@Composable
private fun CropEditor(bitmap: Bitmap, crop: CropFractions, onChange: (CropFractions) -> Unit, modifier: Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val handleColor = MaterialTheme.colorScheme.primary
    val currentCrop by rememberUpdatedState(crop)
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(bitmap) {
                var grab = Grab.NONE
                detectDragGestures(
                    onDragStart = { start ->
                        val fit = fitRect(size, bitmap)
                        grab = Grab.at(start, currentCrop.toRect(fit), HANDLE_RADIUS_PX)
                    },
                    onDragEnd = { grab = Grab.NONE },
                    onDragCancel = { grab = Grab.NONE },
                ) { change, drag ->
                    val fit = fitRect(size, bitmap)
                    if (fit.width <= 0f || fit.height <= 0f) return@detectDragGestures
                    change.consume()
                    currentOnChange(grab.apply(currentCrop, drag.x / fit.width, drag.y / fit.height))
                }
            },
    ) {
        size = IntSize(this.size.width.toInt(), this.size.height.toInt())
        val fit = fitRect(size, bitmap)
        drawImage(image, dstOffset = androidx.compose.ui.unit.IntOffset(fit.left.toInt(), fit.top.toInt()), dstSize = IntSize(fit.width.toInt(), fit.height.toInt()))
        val rect = crop.toRect(fit)
        val shade = Color.Black.copy(alpha = 0.45f)
        // Dim everything outside the crop.
        drawRect(shade, fit.topLeft, Size(fit.width, rect.top - fit.top))
        drawRect(shade, Offset(fit.left, rect.bottom), Size(fit.width, fit.bottom - rect.bottom))
        drawRect(shade, Offset(fit.left, rect.top), Size(rect.left - fit.left, rect.height))
        drawRect(shade, Offset(rect.right, rect.top), Size(fit.right - rect.right, rect.height))
        drawRect(handleColor, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
        listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).forEach { drawCircle(handleColor, 7.dp.toPx(), it) }
    }
}

private const val HANDLE_RADIUS_PX = 72f

private fun fitRect(container: IntSize, bitmap: Bitmap): Rect {
    if (container.width <= 0 || container.height <= 0) return Rect.Zero
    val scale = minOf(container.width.toFloat() / bitmap.width, container.height.toFloat() / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun CropFractions.toRect(fit: Rect) =
    Rect(fit.left + left * fit.width, fit.top + top * fit.height, fit.left + right * fit.width, fit.top + bottom * fit.height)

/** What a drag on the crop rectangle changes. */
private enum class Grab {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE;

    /** [dx] and [dy] are fractions of the picture. */
    fun apply(crop: CropFractions, dx: Float, dy: Float): CropFractions = when (this) {
        NONE -> crop
        TOP_LEFT -> crop.copy(left = (crop.left + dx).coerceIn(0f, crop.right - MIN_CROP), top = (crop.top + dy).coerceIn(0f, crop.bottom - MIN_CROP))
        TOP_RIGHT -> crop.copy(right = (crop.right + dx).coerceIn(crop.left + MIN_CROP, 1f), top = (crop.top + dy).coerceIn(0f, crop.bottom - MIN_CROP))
        BOTTOM_LEFT -> crop.copy(left = (crop.left + dx).coerceIn(0f, crop.right - MIN_CROP), bottom = (crop.bottom + dy).coerceIn(crop.top + MIN_CROP, 1f))
        BOTTOM_RIGHT -> crop.copy(right = (crop.right + dx).coerceIn(crop.left + MIN_CROP, 1f), bottom = (crop.bottom + dy).coerceIn(crop.top + MIN_CROP, 1f))
        MOVE -> {
            val moveX = dx.coerceIn(-crop.left, 1f - crop.right)
            val moveY = dy.coerceIn(-crop.top, 1f - crop.bottom)
            CropFractions(crop.left + moveX, crop.top + moveY, crop.right + moveX, crop.bottom + moveY)
        }
    }

    companion object {
        /** The corner within [radius] pixels of [point], else MOVE when inside the rectangle, else NONE. */
        fun at(point: Offset, rect: Rect, radius: Float): Grab {
            val corners = listOf(TOP_LEFT to rect.topLeft, TOP_RIGHT to rect.topRight, BOTTOM_LEFT to rect.bottomLeft, BOTTOM_RIGHT to rect.bottomRight)
            val nearest = corners.minBy { (_, corner) -> (corner - point).getDistance() }
            return when {
                (nearest.second - point).getDistance() <= radius -> nearest.first
                rect.contains(point) -> MOVE
                else -> NONE
            }
        }
    }
}
