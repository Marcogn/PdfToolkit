package com.marcogn.pdftoolkit.ui.ocr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.ocr.OcrResult
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import kotlin.math.roundToInt

/** The strip under the top bar while a recognition runs (plan 10b): reading goes on, "Cancel" stops it. */
@Composable
fun OcrProgressStrip(state: OcrUiState.Running, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ocr_progress, (state.fraction * PERCENT).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel, enabled = state.cancellable) { Text(stringResource(R.string.ocr_cancel)) }
            }
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The message for a recognition that wrote nothing because there was nothing to do; null when text was written. */
fun ocrNothingChangedMessage(result: OcrResult): Int? = when (result) {
    OcrResult.WRITTEN -> null
    OcrResult.ALREADY_TEXT -> R.string.ocr_already_text
    OcrResult.NO_TEXT -> R.string.ocr_no_text
}

@Composable
fun OcrErrorDialog(failure: SaveFailure, onDismiss: () -> Unit) {
    val message = when (failure) {
        SaveFailure.SOURCE_UNREADABLE -> R.string.ocr_error_source
        SaveFailure.PROTECTED -> R.string.ocr_error_protected
        SaveFailure.DESTINATION_UNWRITABLE -> R.string.ocr_error_destination
        SaveFailure.OUT_OF_MEMORY -> R.string.ocr_error_memory
        SaveFailure.FAILED -> R.string.ocr_error_generic
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ocr_error_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ocr_error_ok)) } },
    )
}

private const val PERCENT = 100f
