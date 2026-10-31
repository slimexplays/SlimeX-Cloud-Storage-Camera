package com.slimex.cloudcamera.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.domain.model.AppSettings
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.CloudSetupStatus
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.security.SecureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cloudConfigRepository: CloudConfigRepository,
    private val authRepository: AuthRepository,
    secureStore: SecureStore,
) : ViewModel() {

    data class State(
        val settings: AppSettings = AppSettings(),
        val cloudStatus: CloudSetupStatus = CloudSetupStatus.Loading,
        val secureStorageWarning: String? = secureStore.storageWarning,
        val signedInEmail: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            cloudConfigRepository.status.collect { status ->
                _state.update { it.copy(cloudStatus = status) }
            }
        }
        viewModelScope.launch {
            authRepository.status.collect { status ->
                _state.update { it.copy(signedInEmail = (status as? AuthStatus.SignedIn)?.email) }
            }
        }
    }

    fun setAutoUpload(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setAutoUpload(enabled) } }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setWifiOnlyUpload(enabled) } }
    }

    /** Blocks capture while offline so no media is buffered on the device at all. */
    fun setStrictZeroLocalStorage(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setStrictZeroLocalStorage(enabled) } }
    }

    fun setRecordAudio(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setRecordVideoAudio(enabled) } }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setNotificationsEnabled(enabled) } }
    }
}
