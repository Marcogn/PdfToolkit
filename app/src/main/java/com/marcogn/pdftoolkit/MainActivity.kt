package com.marcogn.pdftoolkit

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.domain.model.ThemeMode
import com.marcogn.pdftoolkit.ui.navigation.PdfToolkitNavGraph
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import com.marcogn.pdftoolkit.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * `AppCompatActivity` e non `ComponentActivity`: `AppCompatDelegate.setApplicationLocales()`
 * (lingua per-app in Impostazioni) richiede questa classe base, altrimenti il cambio di lingua
 * viene ignorato in silenzio. Stessa scelta dei progetti di riferimento; la UI resta tutta Compose.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PdfToolkitApp()
        }
    }
}

@Composable
private fun PdfToolkitApp(themeViewModel: ThemeViewModel = hiltViewModel()) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by themeViewModel.dynamicColor.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.SISTEMA -> isSystemInDarkTheme()
        ThemeMode.CHIARO -> false
        ThemeMode.SCURO -> true
    }
    PdfToolkitTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        Surface(modifier = Modifier.fillMaxSize()) {
            PdfToolkitNavGraph()
        }
    }
}
