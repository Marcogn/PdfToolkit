package com.marcogn.pdftoolkit.ui.update

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.marcogn.pdftoolkit.data.settings.UpdatePreferences
import com.marcogn.pdftoolkit.data.update.ApkDownloader
import com.marcogn.pdftoolkit.data.update.ApkInstaller
import com.marcogn.pdftoolkit.data.update.ApkVerifier
import com.marcogn.pdftoolkit.data.update.FileWriteGate
import com.marcogn.pdftoolkit.data.update.GitHubReleaseClient
import com.marcogn.pdftoolkit.data.update.UpdateManager
import com.marcogn.pdftoolkit.domain.update.ApkRejection
import com.marcogn.pdftoolkit.domain.update.UnusableReleaseReason
import com.marcogn.pdftoolkit.domain.update.UpdateFailure
import com.marcogn.pdftoolkit.domain.update.UpdateFrequency
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeGate(initial: Boolean = false) : FileWriteGate {
    override val busy = MutableStateFlow(initial)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class UpdateUiTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installer = ApkInstaller(context)
    private val gate = FakeGate()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var preferences: UpdatePreferences
    private lateinit var manager: UpdateManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Each test its own DataStore file, so the process-wide `preferencesDataStore` delegate (which
        // survives between Robolectric tests) cannot leak a stored frequency.
        val file = File.createTempFile("update-prefs", ".preferences_pb").also { it.delete() }
        preferences = UpdatePreferences(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { file },
        )
        manager = UpdateManager(GitHubReleaseClient(), ApkDownloader(context), ApkVerifier(context), installer, preferences, gate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `every failure maps to a non-blank message`() {
        val failures = listOf<UpdateFailure>(
            UpdateFailure.Network("x"), UpdateFailure.RateLimited, UpdateFailure.Http(500),
            UpdateFailure.InvalidResponse(null), UpdateFailure.Storage, UpdateFailure.SizeMismatch,
            UpdateFailure.ChecksumMismatch, UpdateFailure.InstallFailed(1, null), UpdateFailure.InstallFailed(1, "boom"),
        ) + UnusableReleaseReason.entries.map { UpdateFailure.UnusableRelease(it) } +
            ApkRejection.entries.map { UpdateFailure.VerificationFailed(it) }
        failures.forEach { assertTrue("$it", it.toMessage(context.resources).isNotBlank()) }
    }

    @Test
    fun `http and install detail end up in the message`() {
        assertTrue(UpdateFailure.Http(503).toMessage(context.resources).contains("503"))
        assertTrue(UpdateFailure.InstallFailed(1, "boom").toMessage(context.resources).contains("boom"))
    }

    @Test
    fun `different signature gets its own reason`() {
        val signature = UpdateFailure.VerificationFailed(ApkRejection.DIFFERENT_SIGNATURE).toMessage(context.resources)
        val notNewer = UpdateFailure.VerificationFailed(ApkRejection.NOT_NEWER).toMessage(context.resources)
        assertTrue(signature != notNewer)
    }

    @Test
    fun `frequency defaults to weekly and the selection is persisted`() = runTest(dispatcher) {
        val viewModel = UpdateViewModel(manager, installer, preferences, gate)
        assertEquals(UpdateFrequency.WEEKLY, preferences.frequency.first())

        UpdateFrequency.entries.forEach { option ->
            viewModel.onFrequencySelected(option)
            advanceUntilIdle()
            assertEquals(option, preferences.frequency.first())
        }
    }

    @Test
    fun `install is blocked while a save or a recognition is running`() = runTest(dispatcher) {
        val viewModel = UpdateViewModel(manager, installer, preferences, gate)
        val collector = launch { viewModel.installBlocked.collect { } }
        advanceUntilIdle()
        assertEquals(false, viewModel.installBlocked.value)
        gate.busy.value = true
        advanceUntilIdle()
        assertEquals(true, viewModel.installBlocked.value)
        collector.cancel()
    }
}
