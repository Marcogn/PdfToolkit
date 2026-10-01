package com.marcogn.pdftoolkit

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point. `PDFBoxResourceLoader.init(this)` (spec §3.2) and the startup cleanup of
 * `cacheDir/work/` (spec §8) go here, with the phases that introduce them.
 */
@HiltAndroidApp
class PdfToolkitApplication : Application()
