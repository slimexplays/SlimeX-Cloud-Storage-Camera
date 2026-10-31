package com.slimex.cloudcamera.ui.screens.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.CloudMedia
import com.slimex.cloudcamera.domain.model.MediaFilter
import com.slimex.cloudcamera.domain.model.MediaSort
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.sharing.MediaSharer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CloudGalleryViewModel @Inject constructor(
    private val gallery: CloudGalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val cloudConfigRepository: CloudConfigRepository,
    private val authRepository: AuthRepository,
    private val mediaSharer: MediaSharer,
) : ViewModel() {

    data class ViewerState(
        val media: CloudMedia,
        val filePath: String? = null,
        val loading: Boolean = true,
        val progress: Float = 0f,
        val error: String? = null,
    )

    data class State(
        val loading: Boolean = false,
        val configured: Boolean = false,
        val signedIn: Boolean = false,
        val assets: List<CloudMedia> = emptyList(),
        val thumbnails: Map<String, String> = emptyMap(),
        val filter: MediaFilter = MediaFilter.ALL,
        val sort: MediaSort = MediaSort.NEWEST,
        val search: String = "",
        val error: String? = null,
        val message: String? = null,
        val viewer: ViewerState? = null,
        val deletingId: String? = null,
        val sharingId: String? = null,
    ) {
        val needsSetup: Boolean get() = !configured
        val needsSignIn: Boolean get() = configured && !signedIn
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val thumbnailRequests = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            cloudConfigRepository.config.collect { config ->
                _state.update { it.copy(configured = config != null) }
                if (config != null) load() else _state.update { it.copy(assets = emptyList()) }
            }
        }
        viewModelScope.launch {
            authRepository.status.collect { status ->
                _state.update { it.copy(signedIn = status is AuthStatus.SignedIn) }
                if (status is AuthStatus.SignedIn && _state.value.configured) load()
            }
        }
        viewModelScope.launch { runCatching { localMediaRepository.refresh() } }
    }

    fun refresh() = load(showSpinner = _state.value.assets.isEmpty())

    fun setFilter(filter: MediaFilter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        load(showSpinner = true)
    }

    fun setSort(sort: MediaSort) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        load(showSpinner = true)
    }

    fun setSearch(query: String) {
        _state.update { it.copy(search = query) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            delay(350)
            load(showSpinner = true)
        }
    }

    fun clearSearch() {
        _state.update { it.copy(search = "") }
        load(showSpinner = true)
    }

    fun load(showSpinner: Boolean = true) {
        val current = _state.value
        if (!current.configured) {
            _state.update { it.copy(loading = false, assets = emptyList()) }
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            val query = _state.value.search.trim().takeIf { it.isNotBlank() }
            when (
                val result = gallery.list(
                    filter = _state.value.filter,
                    sort = _state.value.sort,
                    search = query,
                    limit = PAGE_SIZE,
                )
            ) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, assets = result.data, error = null)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.message)
                }
            }
        }
    }

    /** Decrypts (once) the small sidecar preview for a grid tile. */
    fun ensureThumbnail(media: CloudMedia) {
        if (_state.value.thumbnails.containsKey(media.id)) return
        if (!thumbnailRequests.add(media.id)) return
        viewModelScope.launch {
            val result = gallery.thumbnail(media)
            val path = result.getOrNull()?.absolutePath
            if (path != null) {
                _state.update { it.copy(thumbnails = it.thumbnails + (media.id to path)) }
            } else {
                thumbnailRequests.remove(media.id)
            }
        }
    }

    fun openViewer(media: CloudMedia) {
        _state.update { it.copy(viewer = ViewerState(media = media)) }
        viewModelScope.launch {
            when (val result = gallery.open(media) { fraction ->
                _state.update { current ->
                    current.viewer?.let { viewer ->
                        if (viewer.media.id == media.id) {
                            current.copy(viewer = viewer.copy(progress = fraction))
                        } else {
                            current
                        }
                    } ?: current
                }
            }) {
                is AppResult.Success -> _state.update { current ->
                    current.viewer?.let { viewer ->
                        if (viewer.media.id == media.id) {
                            current.copy(
                                viewer = viewer.copy(
                                    filePath = result.data.absolutePath,
                                    loading = false,
                                    progress = 1f,
                                ),
                            )
                        } else {
                            current
                        }
                    } ?: current
                }

                is AppResult.Failure -> _state.update { current ->
                    current.viewer?.let { viewer ->
                        if (viewer.media.id == media.id) {
                            current.copy(
                                viewer = viewer.copy(loading = false, error = result.error.message),
                            )
                        } else {
                            current
                        }
                    } ?: current
                }
            }
        }
    }

    fun closeViewer() = _state.update { it.copy(viewer = null) }

    fun delete(media: CloudMedia) {
        if (_state.value.deletingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(deletingId = media.id) }
            when (val result = gallery.delete(media)) {
                is AppResult.Success -> {
                    _state.update { current ->
                        current.copy(
                            deletingId = null,
                            assets = current.assets.filterNot { it.id == media.id },
                            thumbnails = current.thumbnails - media.id,
                            viewer = current.viewer?.takeIf { it.media.id != media.id },
                            message = "Deleted from your cloud storage",
                        )
                    }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(deletingId = null, error = result.error.message)
                }
            }
        }
    }

    fun share(media: CloudMedia) {
        if (_state.value.sharingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(sharingId = media.id) }
            when (val result = mediaSharer.share(media)) {
                is AppResult.Success -> _state.update {
                    it.copy(sharingId = null, message = "Choose an app to share with")
                }

                is AppResult.Failure -> _state.update {
                    it.copy(sharingId = null, error = result.error.message)
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
