package com.marcogn.pdftoolkit.ui.edit

import android.content.ClipData
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.edit.SaveFailure

/**
 * "Save as copy" (default) or "overwrite", the second only when the original accepts writes (spec
 * §6.7). For a document with form fields, [flatten] is the "make final" option (spec §6.5); null hides it.
 * [flattenInk] is "make final" for new drawings (spec §7.4); null hides it.
 */
@Composable
fun SaveDialog(
    overwrite: Boolean,
    canOverwrite: Boolean,
    onOverwriteChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    flatten: Boolean? = null,
    onFlattenChange: (Boolean) -> Unit = {},
    flattenInk: Boolean? = null,
    onFlattenInkChange: (Boolean) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                SaveOption(
                    title = R.string.save_as_copy,
                    hint = R.string.save_as_copy_hint,
                    selected = !overwrite,
                    enabled = true,
                    onSelect = { onOverwriteChange(false) },
                )
                SaveOption(
                    title = R.string.save_overwrite,
                    hint = if (canOverwrite) R.string.save_overwrite_hint else R.string.save_overwrite_unavailable,
                    selected = overwrite,
                    enabled = canOverwrite,
                    onSelect = { onOverwriteChange(true) },
                    // The irreversible warning sits under the option: this dialog is the explicit confirmation (plan U6).
                    warning = if (overwrite) R.string.save_overwrite_warning else null,
                )
                if (flatten != null || flattenInk != null) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (flatten != null) FinalOption(R.string.save_flatten, R.string.save_flatten_hint, flatten, onFlattenChange)
                if (flattenInk != null) FinalOption(R.string.save_flatten_ink, R.string.save_flatten_ink_hint, flattenInk, onFlattenInkChange)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(if (overwrite) R.string.save_overwrite else R.string.save_confirm),
                    color = if (overwrite) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}

/** A "make final" checkbox with its explanation. */
@Composable
private fun FinalOption(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveOption(title: Int, hint: Int, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, warning: Int? = null) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (warning != null) Text(stringResource(warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Save / Discard / Cancel when leaving with changes (spec §6.1). */
@Composable
fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    @StringRes titleRes: Int = R.string.edit_unsaved_title,
    @StringRes messageRes: Int = R.string.edit_unsaved_message,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.edit_unsaved_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.edit_unsaved_discard)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_unsaved_cancel)) }
            }
        },
    )
}

@Composable
fun SaveErrorDialog(failure: SaveFailure, onDismiss: () -> Unit) {
    val message = when (failure) {
        SaveFailure.SOURCE_UNREADABLE -> R.string.save_error_source
        SaveFailure.PROTECTED -> R.string.save_error_protected
        SaveFailure.DESTINATION_UNWRITABLE -> R.string.save_error_destination
        SaveFailure.OUT_OF_MEMORY -> R.string.save_error_memory
        SaveFailure.FAILED -> R.string.save_error_generic
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_error_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_error_ok)) } },
    )
}

/** Snackbar after a copy was saved, with the two actions of spec §6.7. */
@Composable
fun SavedSnackbar(onOpen: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = {
            // A plain TextButton takes the primary colour, unreadable on the snackbar's inverse surface.
            val actionColors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor)
            Row {
                TextButton(onClick = onOpen, colors = actionColors) { Text(stringResource(R.string.save_open)) }
                TextButton(onClick = onShare, colors = actionColors) { Text(stringResource(R.string.save_share)) }
            }
        },
        dismissAction = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = SnackbarDefaults.dismissActionContentColor)
            }
        },
    ) {
        Text(stringResource(R.string.save_done))
    }
}

fun shareCopy(context: android.content.Context, uri: String, title: String) {
    val parsed = uri.toUri()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, parsed)
        clipData = ClipData.newRawUri(null, parsed)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: Exception) {
        // No app to share with.
    }
}
