package com.slimex.cloudcamera.ui.screens.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudAsset
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.gallery.SignedUrlCache
import com.slimex.cloudcamera.sharing.MediaSharer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CloudGalleryViewModel @Inject constructor(
    private val cloudGalleryRepository: CloudGalleryRepository,
    private val cloudConfigRepository: CloudConfigRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val signedUrlCache: SignedUrlCache,
    private val mediaSharer: MediaSharer,
) : ViewModel() {

    enum class Tab { CLOUD, DEVICE }

    data class State(
        val tab: Tab = Tab.CLOUD,
        val loading: Boolean = false,
        val cloudConfigured: Boolean = false,
        val cloudHost: String? = null,
        val assets: List<CloudAsset> = emptyList(),
        val localMedia: List<LocalMedia> = emptyList(),
        val signedUrls: Map<String, String> = emptyMap(),
        val error: String? = null,
        val message: String? = null,
        val deleting: Boolean = false,
        val preview: CloudAsset? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            cloudConfigRepository.config.collect { config ->
                _state.update {
                    it.copy(
                        cloudConfigured = config != null,
                        cloudHost = config?.host,
                        assets = if (config == null) emptyList() else it.assets,
                    )
                }
                if (config != null) loadCloudAssets()
            }
        }
        viewModelScope.launch {
            localMediaRepository.media.collect { media ->
                _state.update { it.copy(localMedia = media) }
            }
        }
        viewModelScope.launch { runCatching { localMediaRepository.refresh() } }
    }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.CLOUD && _state.value.cloudConfigured && _state.value.assets.isEmpty()) {
            loadCloudAssets()
        }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { localMediaRepository.refresh() } }
        if (_state.value.tab == Tab.CLOUD && _state.value.cloudConfigured) {
            loadCloudAssets()
        }
    }

    private fun loadCloudAssets() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = cloudGalleryRepository.listAssets(limit = 60)) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, assets = result.data, error = null)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.message)
                }
            }
        }
    }

    /**
     * Signed URLs are fetched lazily, one per visible tile, and cached with their expiry so
     * thumbnails keep working without leaking expired links.
     */
    fun ensureSignedUrl(asset: CloudAsset) {
        signedUrlCache.get(asset.id)?.let { cached ->
            if (_state.value.signedUrls[asset.id] != cached) {
                _state.update { it.copy(signedUrls = it.signedUrls + (asset.id to cached)) }
            }
            return
        }
        if (_state.value.signedUrls.containsKey(asset.id)) return
        viewModelScope.launch {
            val result = cloudGalleryRepository.signedUrl(asset)
            if (result is AppResult.Success) {
                signedUrlCache.put(asset.id, result.data, SIGNED_URL_TTL_SECONDS)
                _state.update { it.copy(signedUrls = it.signedUrls + (asset.id to result.data)) }
            }
        }
    }

    /** Shares a local capture through the Android chooser (FileProvider backed). */
    fun shareLocal(item: LocalMedia) {
        when (val result = mediaSharer.share(item)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
        }
    }

    fun openPreview(asset: CloudAsset) {
        _state.update { it.copy(preview = asset) }
        ensureSignedUrl(asset)
    }

    fun closePreview() = _state.update { it.copy(preview = null) }

    fun deleteAsset(asset: CloudAsset) {
        if (_state.value.deleting) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            when (val result = cloudGalleryRepository.deleteAsset(asset)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        deleting = false,
                        assets = it.assets.filterNot { item -> item.id == asset.id },
                        preview = null,
                        message = "Removed from your cloud storage",
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(deleting = false, message = result.error.message)
                }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val SIGNED_URL_TTL_SECONDS = 3_600L
    }
}
