package com.appharbor.photosender.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastIpKey = stringPreferencesKey("last_ip_address")
    private val downloadPathKey = stringPreferencesKey("download_path")
    private val uploadedHashesKey = stringSetPreferencesKey("uploaded_hashes")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorEnabledKey = booleanPreferencesKey("dynamic_color_enabled")
    private val highSpeedTransferEnabledKey = booleanPreferencesKey("high_speed_transfer_enabled")
    private val autoArchiveEnabledKey = booleanPreferencesKey("auto_archive_enabled")

    val lastIpAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lastIpKey] ?: ""
    }

    val downloadPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[downloadPathKey] ?: ""
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[themeModeKey] } ?: ThemeMode.SYSTEM
    }

    val dynamicColorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[dynamicColorEnabledKey] ?: false
    }

    val highSpeedTransferEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[highSpeedTransferEnabledKey] ?: true
    }

    val autoArchiveEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoArchiveEnabledKey] ?: false
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

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[themeModeKey] = mode.name
        }
    }

    suspend fun saveDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[dynamicColorEnabledKey] = enabled
        }
    }

    suspend fun saveHighSpeedTransferEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[highSpeedTransferEnabledKey] = enabled
        }
    }

    suspend fun saveAutoArchiveEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[autoArchiveEnabledKey] = enabled
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
