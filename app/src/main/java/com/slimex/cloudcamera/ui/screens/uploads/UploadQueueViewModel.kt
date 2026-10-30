package com.slimex.cloudcamera.ui.screens.uploads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.model.UploadTask
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
) : ViewModel() {

    data class State(
        val tasks: List<UploadTask> = emptyList(),
        val summary: UploadQueueSummary = UploadQueueSummary(),
        val cloudConfigured: Boolean = false,
        val autoUpload: Boolean = true,
        val message: String? = null,
    )

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
                )
            }.collect { next ->
                _state.update { current -> next.copy(message = current.message) }
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
            _state.update { it.copy(message = "Upload cancelled") }
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
            uploadRepository.resumePending()
            _state.update { it.copy(message = "Queue resumed") }
        }
    }

    fun setAutoUpload(enabled: Boolean) {
        viewModelScope.launch { runCatching { settingsRepository.setAutoUpload(enabled) } }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
