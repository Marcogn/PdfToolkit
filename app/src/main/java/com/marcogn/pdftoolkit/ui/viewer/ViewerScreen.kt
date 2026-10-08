package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.OpenFailure
import com.marcogn.pdftoolkit.domain.model.PdfTool

/**
 * Viewer (spec §4.2): shows the loading, password, error and ready states of the document. The
 * reading experience itself is in [ReadyViewer].
 */
@Composable
fun ViewerScreen(
    startTool: PdfTool?,
    onBack: () -> Unit,
    onOpenEdit: (uri: String, tool: PdfTool, page: Int, reopenViewer: Boolean) -> Unit,
    onReopen: (uri: String) -> Unit,
    onOpenCopy: (uri: String) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val overwriteChoice by viewModel.overwriteChoice.collectAsStateWithLifecycle()
    val canOverwrite by viewModel.canOverwrite.collectAsStateWithLifecycle()
    val flattenInk by viewModel.flattenInkChoice.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is ViewerUiState.Ready -> ReadyViewer(
            state = state,
            budget = viewModel.budget,
            readingMode = readingMode,
            onReadingModeChange = viewModel::setReadingMode,
            onPageChanged = viewModel::onPageChanged,
            startTool = startTool,
            onOpenEdit = onOpenEdit,
            save = ViewerSaveUi(
                state = saveState,
                overwriteChoice = overwriteChoice,
                canOverwrite = canOverwrite,
                flattenInk = flattenInk,
                onOverwriteChange = viewModel::setOverwriteChoice,
                onFlattenInkChange = viewModel::setFlattenInkChoice,
                onSave = viewModel::save,
                suggestedCopyName = viewModel::suggestedCopyName,
                onDismissResult = viewModel::dismissSaveResult,
            ),
            onBack = onBack,
            onReopen = onReopen,
            onOpenCopy = onOpenCopy,
        )
        ViewerUiState.Loading -> StatusScaffold(onBack) { CircularProgressIndicator() }
        is ViewerUiState.PasswordRequired -> StatusScaffold(onBack) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            PasswordDialog(
                wrongPassword = state.wrongPassword,
                onSubmit = viewModel::submitPassword,
                onCancel = onBack,
            )
        }
        is ViewerUiState.Error -> StatusScaffold(onBack) {
            OpenError(
                failure = state.failure,
                inRecents = state.inRecents,
                onHome = onBack,
                onRemoveFromRecents = { viewModel.removeFromRecents(onDone = onBack) },
            )
        }
    }
}

/** Top bar with only the back arrow and [content] centred: the states before the document shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusScaffold(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** Spec §5: understandable message and a way back to Home. */
@Composable
private fun OpenError(
    failure: OpenFailure,
    inRecents: Boolean,
    onHome: () -> Unit,
    onRemoveFromRecents: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            stringResource(R.string.viewer_error_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(failure.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onHome) { Text(stringResource(R.string.viewer_error_home)) }
        if (failure == OpenFailure.NOT_FOUND && inRecents) {
            OutlinedButton(onClick = onRemoveFromRecents) { Text(stringResource(R.string.viewer_error_remove_recent)) }
        }
    }
}

private fun OpenFailure.messageRes(): Int = when (this) {
    OpenFailure.NOT_FOUND -> R.string.viewer_error_not_found
    OpenFailure.PASSWORD_PROTECTED -> R.string.viewer_error_password
    OpenFailure.PASSWORD_UNSUPPORTED -> R.string.viewer_error_password_unsupported
    OpenFailure.UNREADABLE -> R.string.viewer_error_unreadable
}
