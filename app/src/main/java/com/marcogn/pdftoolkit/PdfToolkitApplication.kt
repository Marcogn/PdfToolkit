package com.marcogn.pdftoolkit

import android.app.Application
import com.marcogn.pdftoolkit.data.save.PdfSaver
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

/** Hilt entry point. Initialises PdfBox-Android (spec §3.2) and clears stale save leftovers (spec §8). */
@HiltAndroidApp
class PdfToolkitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        PdfSaver.cleanWorkDir(this)
    }
}
