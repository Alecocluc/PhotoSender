package com.appharbor.photosender.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.preferences.AppPreferences
import com.appharbor.photosender.data.preferences.ThemeMode
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

    val autoArchiveEnabled: StateFlow<Boolean> = appPreferences.autoArchiveEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
}
