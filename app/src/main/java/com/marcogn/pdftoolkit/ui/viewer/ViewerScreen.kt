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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.OpenFailure

/**
 * Viewer, phase 1a: continuous mode with zoom and pan. Page indicator, menu, single-page mode,
 * scrubber, thumbnails and the full error screen come in phase 1b.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(onBack: () -> Unit, viewModel: ViewerViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val viewportState = rememberSaveable(saver = PdfViewportState.Saver) { PdfViewportState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = (uiState as? ViewerUiState.Ready)?.displayName.orEmpty()
                    Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val state = uiState) {
                ViewerUiState.Loading -> CircularProgressIndicator()
                is ViewerUiState.Ready -> PdfViewport(
                    pageSizes = state.pageSizes,
                    bitmaps = state.bitmaps,
                    budget = viewModel.budget,
                    state = viewportState,
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxSize(),
                )
                is ViewerUiState.Error -> OpenError(state.failure, onBack)
            }
        }
    }
}

@Composable
private fun OpenError(failure: OpenFailure, onBack: () -> Unit) {
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
            stringResource(failure.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack) { Text(stringResource(R.string.viewer_error_back)) }
    }
}

private fun OpenFailure.messageRes(): Int = when (this) {
    OpenFailure.NOT_FOUND -> R.string.viewer_error_not_found
    OpenFailure.PASSWORD_PROTECTED -> R.string.viewer_error_password
    OpenFailure.UNREADABLE -> R.string.viewer_error_unreadable
}
