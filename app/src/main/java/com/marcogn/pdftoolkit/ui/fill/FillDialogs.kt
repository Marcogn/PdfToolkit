package com.marcogn.pdftoolkit.ui.fill

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.fill.TextBlock
import java.util.Date
import kotlin.math.roundToInt

private const val MAX_LINES = 6

/** Text of a text or date overlay and its size (spec §6.5: adjustable font size). */
@Composable
internal fun TextOverlayDialog(
    initialText: String,
    initialSize: Float,
    isNew: Boolean,
    onConfirm: (text: String, fontSize: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var size by rememberSaveable { mutableFloatStateOf(initialSize) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.fill_text_add else R.string.fill_text_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    maxLines = MAX_LINES,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    stringResource(R.string.fill_text_size, size.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(
                    value = size,
                    onValueChange = { size = it.roundToInt().toFloat() },
                    valueRange = TextBlock.MIN_FONT_SIZE..TextBlock.MAX_FONT_SIZE,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.text, size) }, enabled = text.text.isNotBlank()) { Text(stringResource(R.string.fill_text_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}

/** Today in the app's language, as numbers with a four-digit year (spec §6.5: "formato della lingua dell'app"). */
internal fun todayText(context: Context): String {
    val locale = context.resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, "ddMMyyyy")
    return java.text.SimpleDateFormat(pattern, locale).format(Date())
}
