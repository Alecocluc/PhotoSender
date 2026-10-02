package com.appharbor.pherry.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pherry_prefs")

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
    private val lastServerNameKey = stringPreferencesKey("last_server_name")
    private val lastServerEndpointKey = stringPreferencesKey("last_server_endpoint")
    private val recentDesktopTargetsKey = stringPreferencesKey("recent_desktop_targets")
    private val desktopTokensKey = stringPreferencesKey("desktop_tokens")
    private val downloadPathKey = stringPreferencesKey("download_path")
    private val autoBackupEnabledKey = booleanPreferencesKey("auto_backup_enabled")
    private val autoBackupRequiresChargingKey = booleanPreferencesKey("auto_backup_requires_charging")
    private val lastAutoBackupAtKey = longPreferencesKey("last_auto_backup_at")
    private val uploadedHashesKey = stringSetPreferencesKey("uploaded_hashes")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorEnabledKey = booleanPreferencesKey("dynamic_color_enabled")
    private val highSpeedTransferEnabledKey = booleanPreferencesKey("high_speed_transfer_enabled")
    private val autoArchiveEnabledKey = booleanPreferencesKey("auto_archive_enabled")
    private val confirmDestructiveSyncKey = booleanPreferencesKey("confirm_destructive_sync")
    private val wifiOnlyTransferKey = booleanPreferencesKey("wifi_only_transfer")
    private val keepScreenAwakeKey = booleanPreferencesKey("keep_screen_awake")
    private val defaultUploadModeKey = stringPreferencesKey("default_upload_mode")
    private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")

    val lastIpAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lastIpKey] ?: ""
    }

    /**
     * Name the desktop reported at the last successful connection, paired with the endpoint it came
     * from, so a computer that stops answering can still be named. Blank when it doesn't belong to
     * [lastIpAddress] (e.g. the last attempt was a different computer that never answered).
     */
    val lastServerName: Flow<String> = context.dataStore.data.map { prefs ->
        val name = prefs[lastServerNameKey].orEmpty()
        if (prefs[lastServerEndpointKey] == (prefs[lastIpKey] ?: "")) name else ""
    }

    suspend fun saveLastServer(endpoint: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[lastServerEndpointKey] = endpoint.trim()
            prefs[lastServerNameKey] = name.trim()
        }
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

    /**
     * Per-desktop pairing tokens, persisted so reconnects keep delete rights. Keyed by the desktop's
     * stable device id so the token survives the PC's IP changing; legacy entries keyed by "host:port"
     * are migrated onto the id on the next reconnect (see [ConnectionManager]).
     */
    private val desktopTokens: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decodeTokens(prefs[desktopTokensKey])
    }

    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoBackupEnabledKey] ?: false
    }

    val autoBackupRequiresCharging: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoBackupRequiresChargingKey] ?: true
    }

    val lastAutoBackupAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[lastAutoBackupAtKey] ?: 0L
    }

    /** Store a pairing [token] under [key] (a stable device id, or an endpoint for legacy entries). */
    suspend fun rememberDesktopToken(key: String, token: String) {
        val k = key.trim()
        val value = token.trim()
        if (k.isEmpty() || value.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = decodeTokens(prefs[desktopTokensKey]).toMutableMap()
            current[k] = value
            prefs[desktopTokensKey] = encodeTokens(current)
        }
    }

    /** Drop a stored token (used to remove a stale endpoint-keyed entry once migrated to a device id). */
    suspend fun forgetDesktopToken(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = decodeTokens(prefs[desktopTokensKey]).toMutableMap()
            if (current.remove(k) != null) prefs[desktopTokensKey] = encodeTokens(current)
        }
    }

    /** Pairing token bound to a desktop's stable device id. */
    suspend fun tokenForDevice(deviceId: String): String =
        desktopTokens.first()[deviceId.trim()] ?: ""

    /** Legacy lookup: tokens stored by "host:port" before stable-id keying. Kept for migration. */
    suspend fun tokenForEndpoint(endpoint: String): String =
        desktopTokens.first()[endpoint.trim()] ?: ""

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[autoBackupEnabledKey] = enabled }
    }

    suspend fun setAutoBackupRequiresCharging(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[autoBackupRequiresChargingKey] = enabled }
    }

    suspend fun setLastAutoBackupAt(timestampMs: Long) {
        context.dataStore.edit { prefs -> prefs[lastAutoBackupAtKey] = timestampMs }
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

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[onboardingCompletedKey] ?: false
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

    suspend fun setOnboardingCompleted(completed: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[onboardingCompletedKey] = completed
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

    // Tokens are stored as "endpoint=token" entries joined by "|". Endpoints/tokens never contain
    // these separators (IPv4:port + alphanumeric token), so a flat string keeps DataStore simple.
    private fun decodeTokens(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('|')
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = entry.substring(0, idx).trim()
                val value = entry.substring(idx + 1).trim()
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()
    }

    private fun encodeTokens(map: Map<String, String>): String =
        map.entries.joinToString("|") { "${it.key}=${it.value}" }
}
