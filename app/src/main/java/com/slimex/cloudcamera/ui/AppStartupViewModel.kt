package com.slimex.cloudcamera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppStartupState {
    data object Loading : AppStartupState
    data class Ready(val onboardingCompleted: Boolean) : AppStartupState
}

/**
 * Decides where the app opens (onboarding vs. home) and wakes the upload queue.
 * Any failure here falls back to onboarding instead of crashing.
 */
@HiltViewModel
class AppStartupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val uploadRepository: UploadRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<AppStartupState>(AppStartupState.Loading)
    val state: StateFlow<AppStartupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.current() }.getOrNull()
            runCatching { settingsRepository.deviceId() }
            // Keep the branded splash on screen long enough to read the brand, never longer.
            delay(SPLASH_MIN_MILLIS)
            _state.value = AppStartupState.Ready(settings?.onboardingCompleted ?: false)
        }
        viewModelScope.launch {
            // Fire and forget: resumes any pending uploads after a cold start.
            runCatching { async { uploadRepository.resumePending() }.await() }
        }
    }

    private companion object {
        const val SPLASH_MIN_MILLIS = 650L
    }
}
