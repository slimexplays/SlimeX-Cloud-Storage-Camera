package com.slimex.cloudcamera.ui.screens.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.model.RemoteUsage
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val localMediaRepository: LocalMediaRepository,
    private val cloudGalleryRepository: CloudGalleryRepository,
    private val cloudConfigRepository: CloudConfigRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val summary: LocalStorageSummary = LocalStorageSummary(),
        val remoteUsage: RemoteUsage? = null,
        val remoteLoading: Boolean = false,
        val remoteError: String? = null,
        val cloudConfigured: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            cloudConfigRepository.config.collect { config ->
                _state.update { it.copy(cloudConfigured = config != null) }
                if (config != null) loadRemoteUsage() else _state.update {
                    it.copy(remoteUsage = null, remoteError = null)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { localMediaRepository.refresh() }
            when (val result = localMediaRepository.summary()) {
                is AppResult.Success -> _state.update { it.copy(loading = false, summary = result.data) }
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, message = result.error.message)
                }
            }
            if (_state.value.cloudConfigured) loadRemoteUsage()
        }
    }

    private fun loadRemoteUsage() {
        viewModelScope.launch {
            _state.update { it.copy(remoteLoading = true, remoteError = null) }
            when (val result = cloudGalleryRepository.remoteUsage()) {
                is AppResult.Success -> _state.update {
                    it.copy(remoteLoading = false, remoteUsage = result.data)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(remoteLoading = false, remoteError = result.error.message)
                }
            }
        }
    }

    fun clearThumbnails() {
        viewModelScope.launch {
            when (val result = localMediaRepository.clearThumbnails()) {
                is AppResult.Success -> {
                    _state.update { it.copy(message = "Cleared ${result.data / 1024} KB of cached previews") }
                    refresh()
                }
                is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    fun deleteAllLocal() {
        viewModelScope.launch {
            when (val result = localMediaRepository.deleteAll()) {
                is AppResult.Success -> {
                    _state.update { it.copy(message = "Deleted ${result.data} local files") }
                    refresh()
                }
                is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
