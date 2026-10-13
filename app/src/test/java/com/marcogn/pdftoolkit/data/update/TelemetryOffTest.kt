package com.marcogn.pdftoolkit.data.update

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Plan A, ADR 0006: the only connection the app makes is the update check. ML Kit's usage metrics go
 * through datatransport's `cct` backend, registered as a `backend:` meta-data on
 * `TransportBackendDiscovery`; the app manifest removes it, so the runtime refuses those events before
 * storing them. This fails if a library update brings a backend back (merged manifest).
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryOffTest {

    @Test
    fun `no datatransport backend is registered`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery")
        val metaData = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA).metaData
        val backends = metaData?.keySet().orEmpty().filter { it.startsWith("backend:") }
        assertTrue("datatransport backends still registered: $backends", backends.isEmpty())
    }
}
