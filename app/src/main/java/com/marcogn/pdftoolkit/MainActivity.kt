package com.marcogn.pdftoolkit

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.domain.model.ThemeMode
import com.marcogn.pdftoolkit.ui.navigation.PdfToolkitNavGraph
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import com.marcogn.pdftoolkit.ui.theme.ThemeViewModel
import com.marcogn.pdftoolkit.ui.viewer.pdfUri
import dagger.hilt.android.AndroidEntryPoint

/**
 * `AppCompatActivity`, not `ComponentActivity`: `AppCompatDelegate.setApplicationLocales()`
 * (per-app language in Settings) requires this base class, otherwise the language change is
 * silently ignored. Same choice as the reference projects; the UI is still all Compose.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A VIEW/SEND intent is handled once: after a recreation (rotation) the saved state
        // already has the viewer on the back stack.
        val startUri = if (savedInstanceState == null) intent.pdfUri() else null
        setContent {
            PdfToolkitApp(startUri)
        }
    }
}

@Composable
private fun PdfToolkitApp(startUri: Uri?, themeViewModel: ThemeViewModel = hiltViewModel()) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by themeViewModel.dynamicColor.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.SISTEMA -> isSystemInDarkTheme()
        ThemeMode.CHIARO -> false
        ThemeMode.SCURO -> true
    }
    PdfToolkitTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        Surface(modifier = Modifier.fillMaxSize()) {
            PdfToolkitNavGraph(startUri = startUri)
        }
    }
}
