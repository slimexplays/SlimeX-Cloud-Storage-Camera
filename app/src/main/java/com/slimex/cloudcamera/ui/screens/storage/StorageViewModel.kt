package com.slimex.cloudcamera.ui.screens.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.model.ProviderUsage
import com.slimex.cloudcamera.domain.model.RemoteUsage
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.domain.repository.MediaStorageGateway
import com.slimex.cloudcamera.domain.repository.R2ConfigRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
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
    private val uploadRepository: UploadRepository,
    private val authRepository: AuthRepository,
    private val r2ConfigRepository: R2ConfigRepository,
    private val storage: MediaStorageGateway,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val summary: LocalStorageSummary = LocalStorageSummary(),
        val queue: UploadQueueSummary = UploadQueueSummary(),
        val remoteUsage: RemoteUsage? = null,
        val remoteLoading: Boolean = false,
        val remoteError: String? = null,
        val cloudConfigured: Boolean = false,
        val signedIn: Boolean = false,
        val mediaProviderLabel: String = "Cloudflare R2",
        val mediaConfigured: Boolean = false,
        val bucketReady: Boolean? = null,
        val verifiedUsage: ProviderUsage? = null,
        val verifiedLoading: Boolean = false,
        val verifiedError: String? = null,
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
        viewModelScope.launch {
            r2ConfigRepository.config.collect { r2 ->
                _state.update {
                    it.copy(
                        mediaConfigured = r2 != null,
                        mediaProviderLabel = r2?.providerName
                            ?: com.slimex.cloudcamera.core.constants.AppConstants.DEFAULT_STORAGE_PROVIDER,
                    )
                }
                if (r2 != null) loadVerifiedUsage() else _state.update {
                    it.copy(verifiedUsage = null, verifiedError = null, bucketReady = null)
                }
            }
        }
        viewModelScope.launch {
            authRepository.status.collect { status ->
                _state.update { it.copy(signedIn = status is AuthStatus.SignedIn) }
            }
        }
        viewModelScope.launch {
            uploadRepository.summary.collect { summary ->
                _state.update { it.copy(queue = summary) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (val result = localMediaRepository.summary()) {
                is AppResult.Success -> _state.update { it.copy(loading = false, summary = result.data) }
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, message = result.error.message)
                }
            }
            if (_state.value.cloudConfigured) loadRemoteUsage()
            if (_state.value.mediaConfigured) loadVerifiedUsage()
        }
    }

    /**
     * Verified usage comes from the storage provider (the Worker lists the caller's own prefix),
     * which is why it is shown separately from the counts recorded in Supabase.
     */
    private fun loadVerifiedUsage() {
        viewModelScope.launch {
            _state.update { it.copy(verifiedLoading = true, verifiedError = null) }
            val bucketReady = when (val health = storage.health()) {
                is AppResult.Success -> health.data.bucketReady
                is AppResult.Failure -> null
            }
            val usage = storage.usage()
            _state.update { current ->
                current.copy(
                    verifiedLoading = false,
                    bucketReady = bucketReady,
                    verifiedUsage = when (usage) {
                        is AppResult.Success -> usage.data
                        is AppResult.Failure -> current.verifiedUsage
                    },
                    verifiedError = when (usage) {
                        is AppResult.Failure -> usage.error.message
                        is AppResult.Success -> null
                    },
                )
            }
        }
    }

    private fun loadRemoteUsage() {
        viewModelScope.launch {
            _state.update { it.copy(remoteLoading = true, remoteError = null) }
            when (val result = cloudGalleryRepository.usage()) {
                is AppResult.Success -> _state.update {
                    it.copy(remoteLoading = false, remoteUsage = result.data, remoteError = null)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(remoteLoading = false, remoteError = result.error.message)
                }
            }
        }
    }

    /** Clears thumbnails, viewer and share caches — never the un-uploaded captures. */
    fun clearCache() {
        viewModelScope.launch {
            when (val result = localMediaRepository.clearCache()) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(message = "Freed ${formatFreed(result.data)} of cached files")
                    }
                    refresh()
                }

                is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    private fun formatFreed(bytes: Long): String = com.slimex.cloudcamera.core.util.Formatters.bytes(bytes)

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
