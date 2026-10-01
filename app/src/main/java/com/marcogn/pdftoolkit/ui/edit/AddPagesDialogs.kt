package com.marcogn.pdftoolkit.ui.edit

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

private val InsertionPointSaver = listSaver<InsertionPoint, Int>(
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

/** First step of "Add pages": where the new pages come from. */
@Composable
fun AddPagesSourceDialog(onFromPdf: () -> Unit, onBlank: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_add_pages)) },
        text = {
            Column {
                TextButton(onClick = onFromPdf, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_from_pdf)) }
                TextButton(onClick = onBlank, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_blank)) }
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
    onConfirm: (count: Int, point: InsertionPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by rememberSaveable { mutableIntStateOf(1) }
    var point by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(InsertionPoint.END_OF_DOCUMENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_blank)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** Where the images to add come from: the system photo picker or a file. */
@Composable
fun ImageSourceDialog(onPhotos: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_insert_images)) },
        text = {
            Column {
                TextButton(onClick = onPhotos, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.images_from_photos)) }
                TextButton(onClick = onFiles, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.images_from_files)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}

/** Images: fit to the page or original size (applies to all), and where the pages go. */
@Composable
fun ImagesDialog(
    imageCount: Int,
    pageCount: Int,
    referenceSize: (InsertionPoint) -> SizePt,
    mixedSizes: Boolean,
    onConfirm: (mode: ImageFit, point: InsertionPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(ImageFit.FIT_PAGE) }
    var point by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(InsertionPoint.END_OF_DOCUMENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.images_dialog_title, imageCount, imageCount)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    onConfirm: (InsertionPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var point by rememberSaveable(stateSaver = InsertionPointSaver) { mutableStateOf(InsertionPoint.END_OF_DOCUMENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.add_pdf_pages_title, pickedCount, pickedCount)) },
        text = { InsertionPointSelector(pageCount, point) { point = it } },
        confirmButton = { TextButton(onClick = { onConfirm(point) }) { Text(stringResource(R.string.add_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}
