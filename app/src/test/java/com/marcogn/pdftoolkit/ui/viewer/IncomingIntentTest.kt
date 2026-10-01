package com.marcogn.pdftoolkit.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingIntentTest {

    private val pdf: Uri = "content://com.example.files/doc.pdf".toUri()

    @Test
    fun viewIntentCarriesTheDocumentInItsData() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(pdf, "application/pdf")
        assertEquals(pdf, intent.pdfUri())
    }

    @Test
    fun sendIntentCarriesTheDocumentInItsStream() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, pdf)
        assertEquals(pdf, intent.pdfUri())
    }

    @Test
    fun launcherAndOtherIntentsHaveNoDocument() {
        assertNull(Intent(Intent.ACTION_MAIN).pdfUri())
        assertNull(Intent(Intent.ACTION_SEND).setType("application/pdf").pdfUri())
        assertNull(Intent(Intent.ACTION_SENDTO).setData(pdf).pdfUri())
    }
}
