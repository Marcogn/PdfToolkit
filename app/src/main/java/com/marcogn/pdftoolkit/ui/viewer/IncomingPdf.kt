package com.marcogn.pdftoolkit.ui.viewer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.files.AllFilesAccess
import com.marcogn.pdftoolkit.data.files.FilePathResolver
import com.marcogn.pdftoolkit.data.settings.FileAccessPreferences
import com.marcogn.pdftoolkit.ui.common.rememberAllFilesAccessRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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
 * is asked once, if the file is in shared storage at all. The whole flow lives here, scoped to the
 * activity, so a rotation or a trip to the system settings doesn't lose the PDF.
 */
@HiltViewModel
class IncomingPdfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: AllFilesAccess,
    private val resolver: FilePathResolver,
    private val preferences: FileAccessPreferences,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Each stage's URI is in the saved state, as the process may die while the user is in Settings or
    // the app is in the background: the prompt's, the one being resolved after it, the one to open.

    /** The URI waiting for the user's answer. */
    val asking: StateFlow<String?> = savedState.getStateFlow(ASKING_KEY, null)

    /** The URI to navigate to; [opened] clears it. */
    val toOpen: StateFlow<String?> = savedState.getStateFlow(TO_OPEN_KEY, null)

    init {
        // A resolution cut short by the process dying: run it again.
        savedState.get<String>(RESOLVING_KEY)?.let { resolveAfterPrompt(it.toUri()) }
    }

    /** Starts the flow for the PDF of a `VIEW`/`SEND` intent. */
    fun handle(uri: Uri) {
        viewModelScope.launch {
            val persisted = context.takePersistableAccess(uri)
            when (val step = firstStep(uri, persisted)) {
                is IncomingStep.Open -> savedState[TO_OPEN_KEY] = step.uri.toString()
                is IncomingStep.Ask -> savedState[ASKING_KEY] = step.uri.toString()
            }
        }
    }

    /** [persisted]: the app could keep its grant for [uri], so recents reopen it as it is. */
    suspend fun firstStep(uri: Uri, persisted: Boolean): IncomingStep = withContext(Dispatchers.IO) {
        when {
            persisted || uri.scheme != ContentResolver.SCHEME_CONTENT -> IncomingStep.Open(uri)
            access.isGranted() -> IncomingStep.Open(byPath(uri))
            // A cloud file or an attachment in the sender's private storage: the access wouldn't help.
            !resolver.isInSharedStorage(uri) -> IncomingStep.Open(uri)
            preferences.promptDeclined.first() -> IncomingStep.Open(uri)
            else -> IncomingStep.Ask(uri)
        }
    }

    /**
     * After the prompt, whatever the answer: opens the waiting URI, by path if the access is now there.
     * The prompt closes at once, so a second answer (a double tap, the return from Settings) does nothing.
     */
    fun answered() {
        val uri = asking.value ?: return
        savedState[ASKING_KEY] = null
        savedState[RESOLVING_KEY] = uri
        resolveAfterPrompt(uri.toUri())
    }

    private fun resolveAfterPrompt(uri: Uri) {
        viewModelScope.launch {
            val target = afterPrompt(uri)
            savedState[RESOLVING_KEY] = null
            savedState[TO_OPEN_KEY] = target.toString()
        }
    }

    suspend fun afterPrompt(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (access.isGranted()) byPath(uri) else uri
    }

    /** "Not now": the prompt isn't shown again (Settings can still turn the access on). */
    fun decline() {
        viewModelScope.launch {
            preferences.setPromptDeclined()
            answered()
        }
    }

    fun opened() {
        savedState[TO_OPEN_KEY] = null
    }

    private fun byPath(uri: Uri): Uri = resolver.resolve(uri)?.let(Uri::fromFile) ?: uri

    private companion object {
        // Not route argument names (CLAUDE.md, SavedStateHandle).
        const val ASKING_KEY = "incomingAllFilesPrompt"
        const val RESOLVING_KEY = "incomingResolving"
        const val TO_OPEN_KEY = "incomingToOpen"
    }
}

/**
 * Opens [startUri], the PDF of a `VIEW`/`SEND` intent, through [onOpen], asking for all files access
 * first when the grant can't be kept (ADR 0007). [canNavigate] is the current entry's resumed guard:
 * the PDF waits until it holds.
 */
@Composable
fun IncomingPdfHandler(
    startUri: Uri?,
    canNavigate: Boolean,
    onOpen: (Uri) -> Unit,
    viewModel: IncomingPdfViewModel = hiltViewModel(),
) {
    LaunchedEffect(startUri) {
        if (startUri != null) viewModel.handle(startUri)
    }
    val toOpen by viewModel.toOpen.collectAsStateWithLifecycle()
    LaunchedEffect(toOpen, canNavigate) {
        val target = toOpen
        if (target != null && canNavigate) {
            viewModel.opened()
            onOpen(target.toUri())
        }
    }

    val asking by viewModel.asking.collectAsStateWithLifecycle()
    val request = rememberAllFilesAccessRequest(onDone = viewModel::answered)
    if (asking != null) {
        AllFilesAccessDialog(
            onAllow = { request(false) },
            onNotNow = viewModel::decline,
            // Back or a tap outside: open the file without the access, and ask again next time.
            onDismiss = viewModel::answered,
        )
    }
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
