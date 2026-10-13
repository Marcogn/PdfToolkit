package com.marcogn.pdftoolkit.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.marcogn.pdftoolkit.domain.update.DEFAULT_UPDATE_FREQUENCY
import com.marcogn.pdftoolkit.domain.update.UpdateFrequency
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_prefs")
private val FREQUENCY_KEY = stringPreferencesKey("frequency")
private val LAST_CHECK_AT_KEY = longPreferencesKey("last_check_at")

/**
 * Update check settings (plan A) on Preferences DataStore, one file per area like the others. The
 * pilot (ThePatientGamerHelper) keeps them in SharedPreferences; here reads are `Flow`s and
 * `UpdateManager` reads them with `first()`. The store is a constructor parameter so tests can give
 * each test its own file.
 */
@Singleton
class UpdatePreferences(private val store: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.updateDataStore)

    /** Stored by enum name; an unknown name (renamed constant, hand-edited file) falls back to the default. */
    val frequency: Flow<UpdateFrequency> = store.data.map { preferences ->
        preferences[FREQUENCY_KEY]
            ?.let { name -> UpdateFrequency.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_UPDATE_FREQUENCY
    }

    /** Last check that got an answer from GitHub (update or not); `null` = never. */
    val lastCheckAt: Flow<Instant?> = store.data.map { preferences ->
        preferences[LAST_CHECK_AT_KEY]?.takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
    }

    suspend fun setFrequency(value: UpdateFrequency) {
        store.edit { it[FREQUENCY_KEY] = value.name }
    }

    suspend fun setLastCheckAt(value: Instant) {
        store.edit { it[LAST_CHECK_AT_KEY] = value.toEpochMilli() }
    }
}
