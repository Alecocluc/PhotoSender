package com.appharbor.pherry.data.preferences

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
    private val recentDesktopTargetsKey = stringPreferencesKey("recent_desktop_targets")
    private val downloadPathKey = stringPreferencesKey("download_path")
    private val uploadedHashesKey = stringSetPreferencesKey("uploaded_hashes")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorEnabledKey = booleanPreferencesKey("dynamic_color_enabled")
    private val highSpeedTransferEnabledKey = booleanPreferencesKey("high_speed_transfer_enabled")
    private val autoArchiveEnabledKey = booleanPreferencesKey("auto_archive_enabled")
    private val confirmDestructiveSyncKey = booleanPreferencesKey("confirm_destructive_sync")
    private val wifiOnlyTransferKey = booleanPreferencesKey("wifi_only_transfer")
    private val keepScreenAwakeKey = booleanPreferencesKey("keep_screen_awake")
    private val defaultUploadModeKey = stringPreferencesKey("default_upload_mode")

    val lastIpAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lastIpKey] ?: ""
    }

    val recentDesktopTargets: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[recentDesktopTargetsKey]
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
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

    val confirmDestructiveSync: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[confirmDestructiveSyncKey] ?: true
    }

    val wifiOnlyTransfer: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[wifiOnlyTransferKey] ?: true
    }

    val keepScreenAwake: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keepScreenAwakeKey] ?: true
    }

    val defaultUploadMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[defaultUploadModeKey] ?: "ADD"
    }

    suspend fun saveLastIpAddress(ip: String) {
        context.dataStore.edit { prefs ->
            prefs[lastIpKey] = ip
        }
    }

    suspend fun rememberDesktopTarget(target: String) {
        val normalized = target.trim()
        if (normalized.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[recentDesktopTargetsKey]
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val next = (listOf(normalized) + current.filterNot { it == normalized })
                .take(5)
            prefs[recentDesktopTargetsKey] = next.joinToString("|")
            prefs[lastIpKey] = normalized
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

    suspend fun saveConfirmDestructiveSync(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[confirmDestructiveSyncKey] = enabled
        }
    }

    suspend fun saveWifiOnlyTransfer(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[wifiOnlyTransferKey] = enabled
        }
    }

    suspend fun saveKeepScreenAwake(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keepScreenAwakeKey] = enabled
        }
    }

    suspend fun saveDefaultUploadMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[defaultUploadModeKey] = mode
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
