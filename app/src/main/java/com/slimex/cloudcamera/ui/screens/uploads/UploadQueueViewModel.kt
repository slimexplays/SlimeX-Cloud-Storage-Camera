package com.slimex.cloudcamera.ui.screens.uploads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.network.ConnectivityObserver
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.model.UploadTask
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadQueueViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val cloudConfigRepository: CloudConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {

    data class State(
        val tasks: List<UploadTask> = emptyList(),
        val summary: UploadQueueSummary = UploadQueueSummary(),
        val cloudConfigured: Boolean = false,
        val signedIn: Boolean = false,
        val online: Boolean = false,
        val autoUpload: Boolean = true,
        val wifiOnly: Boolean = false,
        val message: String? = null,
    ) {
        val blockedReason: String?
            get() = when {
                !cloudConfigured -> "Add your Supabase project in Settings to start uploading."
                !signedIn -> "Sign in to your Supabase project to upload."
                !autoUpload -> "Automatic uploads are paused."
                !online -> "Waiting for a network connection."
                else -> null
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                uploadRepository.tasks,
                uploadRepository.summary,
                cloudConfigRepository.config,
                settingsRepository.settings,
            ) { tasks, summary, config, settings ->
                State(
                    tasks = tasks.sortedByDescending { it.createdAt },
                    summary = summary,
                    cloudConfigured = config != null,
                    autoUpload = settings.autoUpload,
                    wifiOnly = settings.wifiOnlyUpload,
                )
            }.collect { next ->
                _state.update { current ->
                    next.copy(
                        message = current.message,
                        signedIn = current.signedIn,
                        online = current.online,
                    )
                }
            }
        }
        viewModelScope.launch {
            authRepository.status.collect { status ->
                _state.update { it.copy(signedIn = status is AuthStatus.SignedIn) }
            }
        }
        viewModelScope.launch {
            connectivity.status.collect { status ->
                _state.update { it.copy(online = status.online) }
            }
        }
    }

    fun retry(taskId: String) {
        viewModelScope.launch {
            uploadRepository.retry(taskId)
            _state.update { it.copy(message = "Retrying upload…") }
        }
    }

    fun cancel(taskId: String) {
        viewModelScope.launch {
            uploadRepository.cancel(taskId)
            _state.update { it.copy(message = "Upload cancelled. The capture is still on this device.") }
        }
    }

    fun clearFinished() {
        viewModelScope.launch {
            uploadRepository.clearFinished()
            _state.update { it.copy(message = "Finished uploads cleared") }
        }
    }

    fun resumeAll() {
        viewModelScope.launch {
            uploadRepository.resumePending(retryFailed = true)
            _state.update { it.copy(message = "Queue resumed") }
        }
    }

    fun setAutoUpload(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepository.setAutoUpload(enabled) }
            if (enabled) uploadRepository.resumePending()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepository.setWifiOnlyUpload(enabled) }
            uploadRepository.resumePending()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
