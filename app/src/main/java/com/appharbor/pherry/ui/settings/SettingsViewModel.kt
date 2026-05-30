package com.appharbor.pherry.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.preferences.ThemeMode
import com.appharbor.pherry.data.upload.BackupScheduler
import com.appharbor.pherry.ui.gallery.UploadMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val backupScheduler: BackupScheduler,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val dynamicColorEnabled: StateFlow<Boolean> = appPreferences.dynamicColorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val highSpeedTransferEnabled: StateFlow<Boolean> = appPreferences.highSpeedTransferEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val confirmDestructiveSync: StateFlow<Boolean> = appPreferences.confirmDestructiveSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val wifiOnlyTransfer: StateFlow<Boolean> = appPreferences.wifiOnlyTransfer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val keepScreenAwake: StateFlow<Boolean> = appPreferences.keepScreenAwake
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoBackupEnabled: StateFlow<Boolean> = appPreferences.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoBackupRequiresCharging: StateFlow<Boolean> = appPreferences.autoBackupRequiresCharging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val defaultUploadMode: StateFlow<String> = appPreferences.defaultUploadMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UploadMode.ADD.name)

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch {
            appPreferences.saveThemeMode(mode)
        }
    }

    fun onDynamicColorChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.saveDynamicColorEnabled(enabled)
        }
    }

    fun onHighSpeedTransferChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.saveHighSpeedTransferEnabled(enabled)
        }
    }

    fun onConfirmDestructiveSyncChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.saveConfirmDestructiveSync(enabled)
        }
    }

    fun onWifiOnlyTransferChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.saveWifiOnlyTransfer(enabled)
        }
    }

    fun onKeepScreenAwakeChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.saveKeepScreenAwake(enabled)
        }
    }

    /**
     * Turn on auto-backup. [includeExisting] decides the starting point: when true the checkpoint is
     * reset to the epoch so the next run sweeps the whole existing library; when false it starts from
     * "now", so only media added afterwards is sent.
     */
    fun enableAutoBackup(includeExisting: Boolean) {
        viewModelScope.launch {
            appPreferences.setAutoBackupEnabled(true)
            appPreferences.setLastAutoBackupAt(if (includeExisting) 0L else System.currentTimeMillis())
            backupScheduler.schedule(appPreferences.autoBackupRequiresCharging.first())
        }
    }

    fun disableAutoBackup() {
        viewModelScope.launch {
            appPreferences.setAutoBackupEnabled(false)
            backupScheduler.cancel()
        }
    }

    fun onAutoBackupChargingChanged(requiresCharging: Boolean) {
        viewModelScope.launch {
            appPreferences.setAutoBackupRequiresCharging(requiresCharging)
            if (appPreferences.autoBackupEnabled.first()) {
                backupScheduler.schedule(requiresCharging)
            }
        }
    }

    fun onDefaultUploadModeSelected(mode: UploadMode) {
        viewModelScope.launch {
            appPreferences.saveDefaultUploadMode(mode.name)
        }
    }
}
