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

private val Context.signatureDataStore: DataStore<Preferences> by preferencesDataStore(name = "signature_prefs")
private val LEGAL_NOTE_SEEN_KEY = booleanPreferencesKey("legal_note_seen")

/** Whether the "this is not a qualified electronic signature" note has been shown (spec §6.5: once). */
@Singleton
class SignaturePreferences @Inject constructor(@ApplicationContext private val context: Context) {

    val legalNoteSeen: Flow<Boolean> = context.signatureDataStore.data.map { it[LEGAL_NOTE_SEEN_KEY] ?: false }

    suspend fun setLegalNoteSeen() {
        context.signatureDataStore.edit { it[LEGAL_NOTE_SEEN_KEY] = true }
    }
}
