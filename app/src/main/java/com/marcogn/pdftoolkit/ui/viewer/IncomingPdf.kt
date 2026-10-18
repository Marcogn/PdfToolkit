package com.marcogn.pdftoolkit.ui.viewer

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.files.AllFilesAccess
import com.marcogn.pdftoolkit.data.files.FilePathResolver
import com.marcogn.pdftoolkit.data.files.allFilesAccessSettingsIntents
import com.marcogn.pdftoolkit.data.settings.FileAccessPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What to do with a PDF another app handed over (ADR 0007). */
sealed interface IncomingStep {
    /** Open [uri] now: the original one, or the file behind it by path. */
    data class Open(val uri: Uri) : IncomingStep

    /** Ask for all files access first, then open [uri]. */
    data class Ask(val uri: Uri) : IncomingStep
}

/**
 * A PDF handed over with a temporary grant (issue #47) stops opening from recents once the app closes.
 * With all files access it is opened, and so kept in recents, by its path instead; without it the user
 * is asked once.
 */
@HiltViewModel
class IncomingPdfViewModel @Inject constructor(
    private val access: AllFilesAccess,
    private val resolver: FilePathResolver,
    private val preferences: FileAccessPreferences,
) : ViewModel() {

    /** [persisted]: the app could keep its grant for [uri], so recents reopen it as it is. */
    suspend fun firstStep(uri: Uri, persisted: Boolean): IncomingStep = withContext(Dispatchers.IO) {
        when {
            persisted || uri.scheme != ContentResolver.SCHEME_CONTENT -> IncomingStep.Open(uri)
            access.isGranted() -> IncomingStep.Open(byPath(uri))
            preferences.promptDeclined.first() -> IncomingStep.Open(uri)
            else -> IncomingStep.Ask(uri)
        }
    }

    /** After the prompt, whatever the answer: by path if the access is now there. */
    suspend fun afterPrompt(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (access.isGranted()) byPath(uri) else uri
    }

    /** "Not now": the prompt isn't shown again (Settings can still turn the access on). */
    fun decline() {
        viewModelScope.launch { preferences.setPromptDeclined() }
    }

    private fun byPath(uri: Uri): Uri = resolver.resolve(uri)?.let(Uri::fromFile) ?: uri
}

/**
 * Opens [startUri], the PDF of a `VIEW`/`SEND` intent, through [onOpen], asking for all files access
 * first when the grant can't be kept (ADR 0007). The navigation after the prompt comes from an
 * activity result, so it doesn't wait for a resumed entry.
 */
@Composable
fun IncomingPdfHandler(startUri: Uri?, onOpen: (Uri) -> Unit, viewModel: IncomingPdfViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The URI waiting for an answer, and whether the system page or dialog is showing: both survive
    // the activity being recreated while the user is in Settings.
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var granting by rememberSaveable { mutableStateOf(false) }
    val finish: () -> Unit = {
        val uri = asking
        asking = null
        granting = false
        if (uri != null) scope.launch { onOpen(viewModel.afterPrompt(uri.toUri())) }
    }
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    val legacyPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }

    LaunchedEffect(startUri) {
        if (startUri == null) return@LaunchedEffect
        val persisted = context.takePersistableAccess(startUri)
        when (val step = viewModel.firstStep(startUri, persisted)) {
            is IncomingStep.Open -> onOpen(step.uri)
            is IncomingStep.Ask -> asking = step.uri.toString()
        }
    }

    if (asking != null && !granting) {
        AllFilesAccessDialog(
            onAllow = {
                granting = true
                val launched = if (AllFilesAccess.usesSettingsPage) {
                    settingsPage.launchFirst(context.allFilesAccessSettingsIntents())
                } else {
                    legacyPermissions.launch(AllFilesAccess.LEGACY_PERMISSIONS)
                    true
                }
                if (!launched) finish()
            },
            onNotNow = {
                viewModel.decline()
                finish()
            },
            // Back or a tap outside: open the file without the access, and ask again next time.
            onDismiss = finish,
        )
    }
}

/** Launches the first of [intents] the device can handle; false if none. */
fun ManagedActivityResultLauncher<Intent, ActivityResult>.launchFirst(intents: List<Intent>): Boolean {
    for (intent in intents) {
        try {
            launch(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // Not on this device: try the next one.
        }
    }
    return false
}

@Composable
private fun AllFilesAccessDialog(onAllow: () -> Unit, onNotNow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.all_files_prompt_title)) },
        text = { Text(stringResource(R.string.all_files_prompt_body)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.all_files_prompt_allow)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.all_files_prompt_not_now)) } },
    )
}
