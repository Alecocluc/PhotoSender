package com.appharbor.photosender.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.preferences.AppPreferences
import com.appharbor.photosender.data.preferences.ThemeMode
import com.appharbor.photosender.ui.gallery.UploadMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val dynamicColorEnabled: StateFlow<Boolean> = appPreferences.dynamicColorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val highSpeedTransferEnabled: StateFlow<Boolean> = appPreferences.highSpeedTransferEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // TODO: Wire this flag into an actual archive/cleanup worker; currently only persisted via settings.
    val autoArchiveEnabled: StateFlow<Boolean> = appPreferences.autoArchiveEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val confirmDestructiveSync: StateFlow<Boolean> = appPreferences.confirmDestructiveSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val wifiOnlyTransfer: StateFlow<Boolean> = appPreferences.wifiOnlyTransfer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val keepScreenAwake: StateFlow<Boolean> = appPreferences.keepScreenAwake
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

    fun onAutoArchiveChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.saveAutoArchiveEnabled(enabled)
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

    fun onDefaultUploadModeSelected(mode: UploadMode) {
        viewModelScope.launch {
            appPreferences.saveDefaultUploadMode(mode.name)
        }
    }
}
