package com.marcogn.pdftoolkit.ui.scan

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.ui.viewer.takePersistableAccess
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val PDF_MIME = "application/pdf"
private val NameStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

/** Default file name of a scan: "Scansione 2026-10-09 1432.pdf" (the label comes from the resources). */
internal fun scanFileName(label: String, now: LocalDateTime = LocalDateTime.now()): String = "$label ${NameStamp.format(now)}.pdf"

/**
 * "Scan" from Home (plan 9): the scanner makes a PDF, [ScanViewModel] keeps a copy, the user chooses where to
 * keep it (`CreateDocument`), and [onSaved] receives the saved file to open in the viewer. If the picker is
 * cancelled, or writing fails, a dialog offers to save again or discard, so the scan is not lost at once.
 * Before that the scan is made searchable (plan 10a, temporary: see [ScanViewModel]).
 * Returns the scanner to start.
 */
@Composable
fun rememberScanToPdf(onSaved: (Uri) -> Unit): DocumentScanner {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: ScanViewModel = hiltViewModel()
    val currentOnSaved = rememberUpdatedState(onSaved)
    // The app-owned copy waiting for a destination; a path, so it survives rotation and process death.
    var staged by rememberSaveable { mutableStateOf<String?>(null) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<ScanOutcome.Unavailable?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { target ->
        val path = staged
        if (path != null && target != null) {
            context.takePersistableAccess(target)
            viewModel.save(path, target)
        } else {
            askDiscard = path != null
        }
    }
    val startSave = { saveLauncher.launch(scanFileName(resources.getString(R.string.scan_file_label))) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.Staged -> {
                    staged = event.path
                    startSave()
                }
                is ScanEvent.Saved -> {
                    staged = null
                    currentOnSaved.value(event.uri)
                }
                ScanEvent.Failed -> {
                    Toast.makeText(context, R.string.scan_save_failed, Toast.LENGTH_LONG).show()
                    askDiscard = staged != null
                }
            }
        }
    }

    val scanner = rememberDocumentScanner(ScanOutput.PDF) { outcome ->
        when (outcome) {
            is ScanOutcome.Pdf -> viewModel.stage(outcome.uri)
            is ScanOutcome.Unavailable -> scanError = outcome
            ScanOutcome.Cancelled, is ScanOutcome.Images -> Unit
        }
    }

    scanError?.let { ScanUnavailableDialog(it) { scanError = null } }
    val recognising by viewModel.recognising.collectAsState()
    recognising?.let { progress ->
        // Temporary (plan 10a): no cancel yet, 10b adds it with the background run.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.scan_recognising_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.scan_recognising_message))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }
    if (askDiscard && staged != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.scan_unsaved_title)) },
            text = { Text(stringResource(R.string.scan_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    startSave()
                }) { Text(stringResource(R.string.scan_unsaved_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDiscard = false
                    staged?.let(viewModel::discard)
                    staged = null
                }) { Text(stringResource(R.string.scan_unsaved_discard)) }
            },
        )
    }
    return scanner
}
