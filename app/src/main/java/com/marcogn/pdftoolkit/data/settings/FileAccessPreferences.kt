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

private val Context.fileAccessDataStore: DataStore<Preferences> by preferencesDataStore(name = "file_access_prefs")
private val PROMPT_DECLINED_KEY = booleanPreferencesKey("all_files_prompt_declined")

/** Whether the user said "Not now" to all files access (ADR 0007): the prompt is then not shown again. */
@Singleton
class FileAccessPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    val promptDeclined: Flow<Boolean> = context.fileAccessDataStore.data.map { it[PROMPT_DECLINED_KEY] ?: false }

    suspend fun setPromptDeclined() {
        context.fileAccessDataStore.edit { it[PROMPT_DECLINED_KEY] = true }
    }
}
