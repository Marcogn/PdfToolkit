package com.marcogn.pdftoolkit.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.saveDataStore: DataStore<Preferences> by preferencesDataStore(name = "save_prefs")
private val OVERWRITE_KEY = booleanPreferencesKey("overwrite")

/** Last "save as copy / overwrite" choice (spec §8). A copy is the default (spec §6.7). */
@Singleton
class SavePreferences @Inject constructor(@ApplicationContext private val context: Context) {

    val overwrite: Flow<Boolean> = context.saveDataStore.data.map { it[OVERWRITE_KEY] ?: false }

    suspend fun setOverwrite(overwrite: Boolean) {
        context.saveDataStore.edit { it[OVERWRITE_KEY] = overwrite }
    }
}
