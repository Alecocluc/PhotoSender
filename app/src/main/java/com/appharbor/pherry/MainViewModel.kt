package com.appharbor.pherry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.network.RememberedComputer
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.preferences.ThemeMode
import com.appharbor.pherry.data.upload.UploadManager
import com.appharbor.pherry.data.upload.TransferReason
import com.appharbor.pherry.ui.transfer.reasonCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val uploadManager: UploadManager,
) : ViewModel() {

    init {
        // Restore the last desktop on launch so a WiFi blip / app restart doesn't force re-pairing.
        connectionManager.autoReconnect()
    }

    val upgradeReviewState = uploadManager.upgradeReviewState
    private val _approvingUpgrade = MutableStateFlow(false)
    val approvingUpgrade = _approvingUpgrade.asStateFlow()
    private val _upgradeError = MutableStateFlow<String?>(null)
    val upgradeError = _upgradeError.asStateFlow()
    private val _upgradeApproved = MutableSharedFlow<Unit>()
    val upgradeApproved = _upgradeApproved.asSharedFlow()

    fun approveUpgrade() {
        if (_approvingUpgrade.value) return
        _approvingUpgrade.value = true
        _upgradeError.value = null
        viewModelScope.launch {
            try {
                uploadManager.approveLegacyAdoption()
                _upgradeApproved.emit(Unit)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                val reason = connectionManager.connectionReason.value.takeUnless { it == TransferReason.NONE }
                    ?: uploadManager.upgradeReviewState.value.reason.takeUnless { it == TransferReason.NONE || it == TransferReason.UPGRADE_REVIEW_REQUIRED }
                    ?: TransferReason.UNKNOWN
                _upgradeError.value = reasonCopy(reason, serverName.value.ifBlank { "your computer" })?.detail
            } finally { _approvingUpgrade.value = false }
        }
    }

    fun refreshUpgradeReview() {
        _upgradeError.value = null
        viewModelScope.launch {
            try { uploadManager.checkUpgradeReview() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _upgradeError.value = "Couldn't check the existing backup. Open Pherry Desktop and try again." }
        }
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
