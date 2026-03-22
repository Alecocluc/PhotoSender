package com.appharbor.photosender.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "photosender_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastIpKey = stringPreferencesKey("last_ip_address")
    private val downloadPathKey = stringPreferencesKey("download_path")
    private val uploadedHashesKey = stringSetPreferencesKey("uploaded_hashes")

    val lastIpAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lastIpKey] ?: ""
    }

    val downloadPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[downloadPathKey] ?: ""
    }

    suspend fun saveLastIpAddress(ip: String) {
        context.dataStore.edit { prefs ->
            prefs[lastIpKey] = ip
        }
    }

    suspend fun saveDownloadPath(path: String) {
        context.dataStore.edit { prefs ->
            prefs[downloadPathKey] = path
        }
    }

    val uploadedHashes: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[uploadedHashesKey] ?: emptySet()
    }

    suspend fun addUploadedHash(hash: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[uploadedHashesKey] ?: emptySet()
            prefs[uploadedHashesKey] = current + hash
        }
    }

    suspend fun clearUploadedHashes() {
        context.dataStore.edit { prefs ->
            prefs[uploadedHashesKey] = emptySet()
        }
    }
}
