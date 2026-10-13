package com.marcogn.pdftoolkit.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.marcogn.pdftoolkit.data.settings.ViewerToolPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolTutorialViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `replaying clears the seen flag so the next drawer opening shows the tutorial`() = runTest {
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ) { File.createTempFile("tutorial", ".preferences_pb").also { it.delete() } }
        val preferences = ViewerToolPreferences(store)
        preferences.setTutorialSeen(true)
        assertTrue(preferences.prefs.first().tutorialSeen)
        ToolTutorialViewModel(preferences).replay()
        assertFalse(preferences.prefs.first().tutorialSeen)
    }
}
