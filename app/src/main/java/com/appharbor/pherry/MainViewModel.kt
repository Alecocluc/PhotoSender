package com.appharbor.pherry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.network.RememberedComputer
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    init {
        // Restore the last desktop on launch so a WiFi blip / app restart doesn't force re-pairing.
        connectionManager.autoReconnect()
    }

    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val dynamicColorEnabled: StateFlow<Boolean> = appPreferences.dynamicColorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    val serverName: StateFlow<String> = connectionManager.serverName

    /** The saved computer while it isn't answering (the top-bar chip says "offline", not "not paired"). */
    val rememberedComputer: StateFlow<RememberedComputer?> = connectionManager.rememberedComputer

    // null = still loading from DataStore (avoids an onboarding flash for returning users)
    val onboardingCompleted: StateFlow<Boolean?> = appPreferences.onboardingCompleted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun completeOnboarding() {
        viewModelScope.launch { appPreferences.setOnboardingCompleted() }
    }
}
