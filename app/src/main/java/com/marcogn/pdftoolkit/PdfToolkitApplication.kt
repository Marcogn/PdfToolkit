package com.marcogn.pdftoolkit

import android.app.Application
import com.marcogn.pdftoolkit.data.images.ImageImporter
import com.marcogn.pdftoolkit.data.save.PdfSaver
import com.marcogn.pdftoolkit.data.update.ApkDownloader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hilt entry point. Initialises PdfBox-Android (spec §3.2) and clears stale save leftovers (spec §8). */
@HiltAndroidApp
class PdfToolkitApplication : Application() {

    @Inject lateinit var apkDownloader: ApkDownloader

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        PdfSaver.cleanWorkDir(this)
        ImageImporter.cleanImagesDir(this)
        // Plan A: update APKs of a previous process (installed, refused or interrupted).
        applicationScope.launch { apkDownloader.deleteLeftovers() }
    }
}
