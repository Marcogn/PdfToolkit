package com.marcogn.pdftoolkit.ui.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** Robolectric: `MlKitException` validates its message with `TextUtils`. */
@RunWith(AndroidJUnit4::class)
class ScanTest {

    @Test
    fun `unsupported error means not enough memory`() {
        val error = MlKitException("low ram", MlKitException.UNSUPPORTED)
        assertEquals(ScanUnavailable.LOW_MEMORY, scanUnavailableFor(error))
    }

    @Test
    fun `other errors are a generic start failure`() {
        assertEquals(ScanUnavailable.FAILED, scanUnavailableFor(MlKitException("x", MlKitException.NETWORK_ISSUE)))
        assertEquals(ScanUnavailable.FAILED, scanUnavailableFor(IllegalStateException()))
    }

    @Test
    fun `scan file name carries the label and the minute`() {
        assertEquals("Scansione 2026-10-09 1432.pdf", scanFileName("Scansione", LocalDateTime.of(2026, 10, 9, 14, 32, 59)))
    }
}
