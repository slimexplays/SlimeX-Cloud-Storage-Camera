package com.slimex.cloudcamera.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.CloudSetupStatus
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
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
class HomeViewModel @Inject constructor(
    private val cloudConfigRepository: CloudConfigRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val uploadRepository: UploadRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val cloudStatus: CloudSetupStatus = CloudSetupStatus.Loading,
        val signedInEmail: String? = null,
        val recent: List<LocalMedia> = emptyList(),
        val queue: UploadQueueSummary = UploadQueueSummary(),
        val summary: LocalStorageSummary = LocalStorageSummary(),
    ) {
        val cloudConfigured: Boolean get() = cloudStatus is CloudSetupStatus.Configured
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                cloudConfigRepository.status,
                localMediaRepository.pending,
                uploadRepository.summary,
            ) { cloud, media, queue -> Triple(cloud, media, queue) }
                .collect { (cloud, media, queue) ->
                    _state.update {
                        it.copy(
                            cloudStatus = cloud,
                            recent = media.take(6),
                            queue = queue,
                            loading = false,
                        )
                    }
                }
        }
        viewModelScope.launch {
            authRepository.status.collect { status ->
                _state.update {
                    it.copy(signedInEmail = (status as? AuthStatus.SignedIn)?.email)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { localMediaRepository.refresh() }
            val summary = localMediaRepository.summary().getOrNull()
            if (summary != null) {
                _state.update { it.copy(summary = summary, loading = false) }
            }
        }
    }
}
