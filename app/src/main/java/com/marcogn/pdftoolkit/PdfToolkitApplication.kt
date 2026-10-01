package com.marcogn.pdftoolkit

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Punto d'ingresso Hilt. Qui arriveranno `PDFBoxResourceLoader.init(this)` (SPEC §3.2) e la
 * pulizia di `cacheDir/work/` all'avvio (SPEC §8), con le fasi che li introducono.
 */
@HiltAndroidApp
class PdfToolkitApplication : Application()
