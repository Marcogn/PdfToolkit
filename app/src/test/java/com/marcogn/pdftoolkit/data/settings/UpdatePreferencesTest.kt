package com.marcogn.pdftoolkit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.marcogn.pdftoolkit.domain.update.UpdateFrequency
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePreferencesTest {

    private fun store() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ) { File.createTempFile("update-prefs", ".preferences_pb").also { it.delete() } }

    @Test
    fun `defaults to weekly and never checked`() = runTest {
        val preferences = UpdatePreferences(store())
        assertEquals(UpdateFrequency.WEEKLY, preferences.frequency.first())
        assertNull(preferences.lastCheckAt.first())
    }

    @Test
    fun `frequency and last check are stored`() = runTest {
        val preferences = UpdatePreferences(store())
        UpdateFrequency.entries.forEach {
            preferences.setFrequency(it)
            assertEquals(it, preferences.frequency.first())
        }
        val at = Instant.ofEpochMilli(1_760_000_000_000)
        preferences.setLastCheckAt(at)
        assertEquals(at, preferences.lastCheckAt.first())
    }

    @Test
    fun `an unknown stored frequency falls back to the default`() = runTest {
        val store = store()
        store.edit { it[stringPreferencesKey("frequency")] = "FORTNIGHTLY" }
        assertEquals(UpdateFrequency.WEEKLY, UpdatePreferences(store).frequency.first())
    }
}
