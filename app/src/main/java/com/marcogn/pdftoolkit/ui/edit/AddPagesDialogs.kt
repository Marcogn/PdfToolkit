package com.marcogn.pdftoolkit.ui.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.edit.ImageFit
import com.marcogn.pdftoolkit.domain.edit.InsertionPoint
import com.marcogn.pdftoolkit.domain.edit.PageSizing
import com.marcogn.pdftoolkit.domain.edit.SizePt
import kotlin.math.roundToInt

internal val InsertionPointSaver = listSaver<InsertionPoint, Int>(
    save = { listOf(it.kind.ordinal, it.pageNumber) },
    restore = { InsertionPoint(InsertionPoint.Kind.entries[it[0]], it[1]) },
)

private const val MM_PER_POINT = 25.4f / 72f

/** "210 × 297 mm", for the size the new pages will have. */
private fun SizePt.asMillimetres(): String = "${(width * MM_PER_POINT).roundToInt()} × ${(height * MM_PER_POINT).roundToInt()} mm"

/** Where the new pages go: start, end, or before/after a page number (spec §6.2). */
@Composable
private fun InsertionPointSelector(pageCount: Int, point: InsertionPoint, onChange: (InsertionPoint) -> Unit) {
    val options = listOf(
        InsertionPoint.Kind.START to R.string.add_at_start,
        InsertionPoint.Kind.END to R.string.add_at_end,
        InsertionPoint.Kind.BEFORE_PAGE to R.string.add_before_page,
        InsertionPoint.Kind.AFTER_PAGE to R.string.add_after_page,
    )
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.add_where), style = MaterialTheme.typography.titleSmall)
        options.forEach { (kind, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = point.kind == kind, role = Role.RadioButton, onClick = { onChange(point.copy(kind = kind)) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = point.kind == kind, onClick = null)
                Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (point.kind == InsertionPoint.Kind.BEFORE_PAGE || point.kind == InsertionPoint.Kind.AFTER_PAGE) {
            var text by rememberSaveable { mutableStateOf(point.pageNumber.toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    val digits = value.filter { it.isDigit() }.take(MAX_DIGITS)
                    text = digits
                    onChange(point.copy(pageNumber = (digits.toIntOrNull() ?: 1).coerceIn(1, pageCount)))
                },
                label = { Text(stringResource(R.string.add_page_number, pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.padding(top = 8.dp).width(180.dp),
            )
        }
    }
}

private const val MAX_DIGITS = 5
private const val DISABLED_ALPHA = 0.5f

/** One tappable row of a source dialog: icon, title and a line of explanation, on the full width. */
@Composable
private fun SourceRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The one place to add pages in "Organize pages": another PDF, blank pages, or images (plan U4). */
@Composable
fun AddSourceDialog(
    onFromPdf: () -> Unit,
    onBlank: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onScan: () -> Unit,
    scanAvailable: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organize_add_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SourceRow(Icons.Outlined.PictureAsPdf, stringResource(R.string.add_from_pdf), stringResource(R.string.add_from_pdf_hint), onFromPdf)
                SourceRow(Icons.Outlined.NoteAdd, stringResource(R.string.add_blank), stringResource(R.string.add_blank_hint), onBlank)
                SourceRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.images_from_photos), stringResource(R.string.images_from_photos_hint), onPhotos)
                SourceRow(Icons.Outlined.Folder, stringResource(R.string.images_from_files), stringResource(R.string.images_from_files_hint), onFiles)
                SourceRow(
                    Icons.Outlined.DocumentScanner,
                    stringResource(R.string.add_from_scanner),
                    stringResource(if (scanAvailable) R.string.add_from_scanner_hint else R.string.scan_unavailable_play_services),
                    onScan,
                    enabled = scanAvailable,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}

/** Blank pages: how many (1 to 50), where, and the size they will have. */
@Composable
fun BlankPagesDialog(
    pageCount: Int,
    referenceSize: (InsertionPoint) -> SizePt,
    mixedSizes: Boolean,
    initialPoint: InsertionPoint = InsertionPoint.END_OF_DOCUMENT,
    onConfirm: (count: Int, point: InsertionPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by rememberSaveable { mutableIntStateOf(1) }
    var point by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(initialPoint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_blank)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.add_blank_count), modifier = Modifier.weight(1f))
                    IconButton(onClick = { count = (count - 1).coerceAtLeast(PageSizing.MIN_BLANK_PAGES) }, enabled = count > PageSizing.MIN_BLANK_PAGES) {
                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.add_blank_fewer))
                    }
                    Text(count.toString(), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { count = (count + 1).coerceAtMost(PageSizing.MAX_BLANK_PAGES) }, enabled = count < PageSizing.MAX_BLANK_PAGES) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_blank_more))
                    }
                }
                InsertionPointSelector(pageCount, point) { point = it }
                PagePreview(referenceSize(point))
                Text(
                    stringResource(R.string.add_size_used, referenceSize(point).asMillimetres()) +
                        if (mixedSizes) " " + stringResource(R.string.add_size_mixed) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(count, point) }) { Text(stringResource(R.string.add_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}

/** A row of previews of the images about to become pages (the first few, with "+N" for the rest). */
@Composable
private fun ImageStrip(images: List<PickedImage>, thumbnail: (uri: String) -> Bitmap?) {
    val shown = images.take(MAX_STRIP)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(shown, key = { it.uri }) { image ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, image.uri) {
                value = withContext(Dispatchers.IO) { thumbnail(image.uri) }?.asImageBitmap()
            }
            Box(
                Modifier.size(72.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        if (images.size > shown.size) {
            item {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Text("+${images.size - shown.size}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private const val MAX_STRIP = 8

/** A white page of the proportions of [size], to see what the new blank pages will look like. */
@Composable
private fun PagePreview(size: SizePt) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .height(96.dp)
                .aspectRatio((size.width / size.height).coerceIn(0.3f, 3f))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** Images: fit to the page or original size (applies to all), and where the pages go. */
@Composable
fun ImagesDialog(
    images: List<PickedImage>,
    thumbnail: (uri: String) -> Bitmap?,
    pageCount: Int,
    referenceSize: (InsertionPoint) -> SizePt,
    mixedSizes: Boolean,
    initialPoint: InsertionPoint = InsertionPoint.END_OF_DOCUMENT,
    onConfirm: (mode: ImageFit, point: InsertionPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(ImageFit.FIT_PAGE) }
    var point by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(initialPoint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.images_dialog_title, images.size, images.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ImageStrip(images, thumbnail)
                Column(Modifier.selectableGroup()) {
                    ImageFit.entries.forEach { option ->
                        val (label, hint) = when (option) {
                            ImageFit.FIT_PAGE -> R.string.images_fit_page to R.string.images_fit_page_hint
                            ImageFit.ORIGINAL_SIZE -> R.string.images_original_size to R.string.images_original_size_hint
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = mode == option, role = Role.RadioButton, onClick = { mode = option })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = mode == option, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(stringResource(label))
                                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                InsertionPointSelector(pageCount, point) { point = it }
                if (mode == ImageFit.FIT_PAGE) {
                    Text(
                        stringResource(R.string.add_size_used, referenceSize(point).asMillimetres()) +
                            if (mixedSizes) " " + stringResource(R.string.add_size_mixed) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode, point) }) { Text(stringResource(R.string.add_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}

/** Insertion point of the pages picked from another PDF, shown over the page grid. */
@Composable
fun PickedPagesDialog(
    pickedCount: Int,
    pageCount: Int,
    initialPoint: InsertionPoint = InsertionPoint.END_OF_DOCUMENT,
    onConfirm: (InsertionPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var point by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(initialPoint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.add_pdf_pages_title, pickedCount, pickedCount)) },
        text = { InsertionPointSelector(pageCount, point) { point = it } },
        confirmButton = { TextButton(onClick = { onConfirm(point) }) { Text(stringResource(R.string.add_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}
