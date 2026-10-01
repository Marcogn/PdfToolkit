package com.marcogn.pdftoolkit.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readingDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_prefs")
private val READING_MODE_KEY = stringPreferencesKey("reading_mode")

/**
 * Reading mode on Preferences DataStore (spec §8). One global preference [ASSUNZIONE, spec §4.2]:
 * the mode picked in the viewer menu is also the default shown in Settings.
 */
@Singleton
class ReadingPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    val readingMode: Flow<ReadingMode> = context.readingDataStore.data.map { preferences ->
        preferences[READING_MODE_KEY]?.let { stored ->
            runCatching { ReadingMode.valueOf(stored) }.getOrNull()
        } ?: ReadingMode.CONTINUOUS
    }

    suspend fun setReadingMode(mode: ReadingMode) {
        context.readingDataStore.edit { preferences -> preferences[READING_MODE_KEY] = mode.name }
    }
}
