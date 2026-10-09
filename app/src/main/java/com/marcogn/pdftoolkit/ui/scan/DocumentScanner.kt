package com.marcogn.pdftoolkit.ui.scan

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.marcogn.pdftoolkit.R

/** What the scanner hands back: one PDF (scan to a new file) or one JPEG per page (pages added to a document). */
enum class ScanOutput { PDF, IMAGES }

/** Why the scanner can't be used on this device (plan 9, spec §7.1). */
enum class ScanUnavailable {
    /** Google Play services is missing, disabled or too old: it provides the scanner's UI and models. */
    NO_PLAY_SERVICES,

    /** Less than the 1.7 GB of RAM the scanner needs ([MlKitException.UNSUPPORTED]). */
    LOW_MEMORY,

    /** The scanner failed to start for another reason (models not yet downloaded, no connection for that). */
    FAILED,
}

sealed interface ScanOutcome {
    data class Pdf(val uri: Uri) : ScanOutcome
    data class Images(val uris: List<Uri>) : ScanOutcome
    data object Cancelled : ScanOutcome
    data class Unavailable(val reason: ScanUnavailable) : ScanOutcome
}

/** Maps the failure of `getStartScanIntent` to a reason; the scanner signals low RAM with `UNSUPPORTED`. */
internal fun scanUnavailableFor(error: Throwable): ScanUnavailable =
    if ((error as? MlKitException)?.errorCode == MlKitException.UNSUPPORTED) ScanUnavailable.LOW_MEMORY else ScanUnavailable.FAILED

/** Cheap check done up front, so Home and the Add dialog can show the tool disabled with its explanation. */
fun playServicesAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

/** Result of [rememberDocumentScanner]: [launch] opens the scanner; [available] is false without Play services. */
class DocumentScanner internal constructor(val available: Boolean, val launch: () -> Unit)

/**
 * ML Kit Document Scanner ([guide](https://developers.google.com/ml-kit/vision/doc-scanner/android)): the UI,
 * the models and the camera run in Google Play services, so the app needs no camera permission. Only the
 * format asked for in [output] is requested, since producing a PDF takes time. [onOutcome] gets the result
 * on the main thread; the returned URIs belong to Play services' scan folder and are read straight away.
 */
@Composable
fun rememberDocumentScanner(output: ScanOutput, onOutcome: (ScanOutcome) -> Unit): DocumentScanner {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOutcome = rememberUpdatedState(onOutcome)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val outcome = if (result.resultCode != Activity.RESULT_OK || scan == null) {
            ScanOutcome.Cancelled
        } else {
            when (output) {
                ScanOutput.PDF -> scan.pdf?.uri?.let { ScanOutcome.Pdf(it) }
                ScanOutput.IMAGES -> scan.pages?.map { it.imageUri }?.takeIf { it.isNotEmpty() }?.let { ScanOutcome.Images(it) }
            } ?: ScanOutcome.Cancelled
        }
        currentOutcome.value(outcome)
    }
    // Checked again whenever the app comes back: Play services may have been updated or enabled meanwhile.
    var available by remember { mutableStateOf(playServicesAvailable(context)) }
    LifecycleResumeEffect(context) {
        available = playServicesAvailable(context)
        onPauseOrDispose {}
    }
    // `getStartScanIntent` is asynchronous (the first use may download models): a second tap meanwhile must not
    // start a second scanner.
    var starting by remember { mutableStateOf(false) }
    return remember(launcher, activity, available, output) {
        DocumentScanner(available) {
            if (starting) return@DocumentScanner
            if (activity == null || !available) {
                currentOutcome.value(ScanOutcome.Unavailable(ScanUnavailable.NO_PLAY_SERVICES))
            } else {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setResultFormats(
                        when (output) {
                            ScanOutput.PDF -> GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                            ScanOutput.IMAGES -> GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
                        },
                    )
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                starting = true
                GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                    .addOnSuccessListener {
                        starting = false
                        launcher.launch(IntentSenderRequest.Builder(it).build())
                    }
                    .addOnFailureListener {
                        starting = false
                        currentOutcome.value(ScanOutcome.Unavailable(scanUnavailableFor(it)))
                    }
            }
        }
    }
}

@StringRes
fun ScanUnavailable.messageRes(): Int = when (this) {
    ScanUnavailable.NO_PLAY_SERVICES -> R.string.scan_unavailable_play_services
    ScanUnavailable.LOW_MEMORY -> R.string.scan_unavailable_memory
    ScanUnavailable.FAILED -> R.string.scan_unavailable_failed
}
