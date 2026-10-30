package com.slimex.cloudcamera.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.domain.model.AppSettings
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraPreferencesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    fun setPhotoQuality(quality: PhotoQuality) {
        viewModelScope.launch { runCatching { settingsRepository.setPhotoQuality(quality) } }
    }

    fun setVideoQuality(quality: VideoQualityPreference) {
        viewModelScope.launch { runCatching { settingsRepository.setVideoQuality(quality) } }
    }

    fun setRecordAudio(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setRecordVideoAudio(enabled) } }
    }
}
