package com.slimex.cloudcamera.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.ProviderUsage
import com.slimex.cloudcamera.domain.model.RemoteUsage
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.domain.repository.MediaStorageGateway
import com.slimex.cloudcamera.domain.repository.R2ConfigRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import com.slimex.cloudcamera.security.SecureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the single "Cloud configuration" screen:
 *
 *   • Supabase — authentication + metadata database
 *   • Cloudflare R2 — encrypted media, reached through the user's Worker
 *   • Cloud Storage Status — verified provider usage next to the recorded metadata counts
 *
 * Nothing here stores or displays an R2 credential: the app only knows the Worker endpoint.
 */
@HiltViewModel
class CloudConfigurationViewModel @Inject constructor(
    private val supabaseRepository: CloudConfigRepository,
    private val r2Repository: R2ConfigRepository,
    private val storage: MediaStorageGateway,
    private val gallery: CloudGalleryRepository,
    private val uploads: UploadRepository,
    private val authRepository: AuthRepository,
    secureStore: SecureStore,
) : ViewModel() {

    data class SupabaseCard(
        val url: String = "",
        val key: String = "",
        val keyVisible: Boolean = false,
        val savedHost: String? = null,
        val savedAt: Long? = null,
        val testing: Boolean = false,
        val saving: Boolean = false,
        val report: ConnectionReport? = null,
        val fieldError: String? = null,
        val confirmDisconnect: Boolean = false,
    ) {
        val configured: Boolean get() = savedHost != null
        val canSubmit: Boolean get() = url.isNotBlank() && key.isNotBlank() && !testing && !saving
    }

    data class R2Card(
        val workerUrl: String = "",
        val providerName: String = AppConstants.DEFAULT_STORAGE_PROVIDER,
        val savedHost: String? = null,
        val savedAt: Long? = null,
        val testing: Boolean = false,
        val saving: Boolean = false,
        val report: ConnectionReport? = null,
        val fieldError: String? = null,
        val confirmDisconnect: Boolean = false,
    ) {
        val configured: Boolean get() = savedHost != null
        val canSubmit: Boolean get() = workerUrl.isNotBlank() && !testing && !saving
    }

    data class StorageStatus(
        val providerLabel: String = AppConstants.DEFAULT_STORAGE_PROVIDER,
        val configured: Boolean = false,
        val workerReachable: Boolean? = null,
        val bucketReady: Boolean? = null,
        val verified: ProviderUsage? = null,
        val metadata: RemoteUsage? = null,
        val freeQuotaBytes: Long? = null,
        val loading: Boolean = false,
        val requiresSignIn: Boolean = false,
        val error: String? = null,
        val checkedAtMillis: Long? = null,
        val pendingUploads: Int = 0,
        val failedUploads: Int = 0,
    )

    data class State(
        val loading: Boolean = true,
        val supabase: SupabaseCard = SupabaseCard(),
        val r2: R2Card = R2Card(),
        val status: StorageStatus = StorageStatus(),
        val signedIn: Boolean = false,
        val message: String? = null,
        val secureStorageWarning: String? = null,
    )

    private val _state = MutableStateFlow(State(secureStorageWarning = secureStore.storageWarning))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val supabase = supabaseRepository.current()
            val r2 = r2Repository.current()
            _state.update { current ->
                current.copy(
                    loading = false,
                    supabase = current.supabase.copy(
                        url = supabase?.projectUrl.orEmpty(),
                        key = supabase?.publishableKey.orEmpty(),
                        savedHost = supabase?.host,
                        savedAt = supabase?.savedAt?.takeIf { it > 0 },
                    ),
                    r2 = current.r2.copy(
                        workerUrl = r2?.workerUrl.orEmpty(),
                        providerName = r2?.providerName ?: AppConstants.DEFAULT_STORAGE_PROVIDER,
                        savedHost = r2?.host,
                        savedAt = r2?.savedAt?.takeIf { it > 0 },
                    ),
                    status = current.status.copy(
                        providerLabel = r2?.providerName ?: AppConstants.DEFAULT_STORAGE_PROVIDER,
                        configured = r2 != null,
                    ),
                )
            }
            refreshStatus()
        }
        viewModelScope.launch {
            authRepository.status.collect { auth ->
                _state.update { it.copy(signedIn = auth is AuthStatus.SignedIn) }
            }
        }
        viewModelScope.launch {
            uploads.summary.collect { summary ->
                _state.update {
                    it.copy(
                        status = it.status.copy(
                            pendingUploads = summary.pending + summary.paused,
                            failedUploads = summary.failed,
                        ),
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------- Supabase

    fun onSupabaseUrlChange(value: String) {
        _state.update { it.copy(supabase = it.supabase.copy(url = value, fieldError = null)) }
    }

    fun onSupabaseKeyChange(value: String) {
        _state.update { it.copy(supabase = it.supabase.copy(key = value, fieldError = null)) }
    }

    fun toggleSupabaseKeyVisibility() {
        _state.update { it.copy(supabase = it.supabase.copy(keyVisible = !it.supabase.keyVisible)) }
    }

    fun testSupabase() {
        val card = _state.value.supabase
        if (card.testing) return
        viewModelScope.launch {
            _state.update { it.copy(supabase = it.supabase.copy(testing = true, fieldError = null, report = null)) }
            val result = supabaseRepository.testConnection(
                projectUrl = card.url,
                publishableKey = card.key,
                onStep = { report ->
                    _state.update { it.copy(supabase = it.supabase.copy(report = report)) }
                },
            )
            _state.update { current ->
                when (result) {
                    is AppResult.Success -> current.copy(
                        supabase = current.supabase.copy(testing = false, report = result.data),
                        message = if (result.data.success) {
                            "Supabase is reachable and the key was accepted."
                        } else {
                            "Supabase answered, but a required check failed."
                        },
                    )

                    is AppResult.Failure -> current.copy(
                        supabase = current.supabase.copy(testing = false, fieldError = result.error.message),
                    )
                }
            }
        }
    }

    fun saveSupabase() {
        val card = _state.value.supabase
        if (!card.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(supabase = it.supabase.copy(saving = true, fieldError = null)) }
            when (val result = supabaseRepository.save(card.url, card.key)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        supabase = it.supabase.copy(
                            saving = false,
                            savedHost = result.data.host,
                            savedAt = result.data.savedAt,
                            url = result.data.projectUrl,
                            key = result.data.publishableKey,
                        ),
                        message = "Supabase configuration saved.",
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(
                        supabase = it.supabase.copy(saving = false, fieldError = result.error.message),
                    )
                }
            }
            refreshStatus()
        }
    }

    fun requestSupabaseDisconnect() {
        _state.update { it.copy(supabase = it.supabase.copy(confirmDisconnect = true)) }
    }

    fun cancelSupabaseDisconnect() {
        _state.update { it.copy(supabase = it.supabase.copy(confirmDisconnect = false)) }
    }

    fun disconnectSupabase() {
        viewModelScope.launch {
            supabaseRepository.clear()
            _state.update {
                it.copy(
                    supabase = SupabaseCard(),
                    message = "Supabase configuration removed from this device.",
                )
            }
            refreshStatus()
        }
    }

    // ---------------------------------------------------------- Cloudflare R2

    fun onWorkerUrlChange(value: String) {
        _state.update { it.copy(r2 = it.r2.copy(workerUrl = value, fieldError = null)) }
    }

    fun onProviderNameChange(value: String) {
        _state.update { it.copy(r2 = it.r2.copy(providerName = value, fieldError = null)) }
    }

    fun testR2() {
        val card = _state.value.r2
        if (card.testing) return
        viewModelScope.launch {
            _state.update { it.copy(r2 = it.r2.copy(testing = true, fieldError = null, report = null)) }
            val result = r2Repository.testConnection(
                workerUrl = card.workerUrl,
                providerName = card.providerName,
                onStep = { report -> _state.update { it.copy(r2 = it.r2.copy(report = report)) } },
            )
            _state.update { current ->
                when (result) {
                    is AppResult.Success -> current.copy(
                        r2 = current.r2.copy(testing = false, report = result.data),
                        message = if (result.data.success) {
                            "The Worker answered and Cloudflare R2 is reachable."
                        } else {
                            "The Worker answered, but a required check failed."
                        },
                    )

                    is AppResult.Failure -> current.copy(
                        r2 = current.r2.copy(testing = false, fieldError = result.error.message),
                    )
                }
            }
        }
    }

    fun saveR2() {
        val card = _state.value.r2
        if (!card.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(r2 = it.r2.copy(saving = true, fieldError = null)) }
            when (val result = r2Repository.save(card.workerUrl, card.providerName)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        r2 = it.r2.copy(
                            saving = false,
                            savedHost = result.data.host,
                            savedAt = result.data.savedAt,
                            workerUrl = result.data.workerUrl,
                            providerName = result.data.providerName,
                        ),
                        status = it.status.copy(
                            providerLabel = result.data.providerName,
                            configured = true,
                        ),
                        message = "Cloudflare R2 configuration saved. This endpoint is all the app stores.",
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(r2 = it.r2.copy(saving = false, fieldError = result.error.message))
                }
            }
            refreshStatus()
        }
    }

    fun requestR2Disconnect() {
        _state.update { it.copy(r2 = it.r2.copy(confirmDisconnect = true)) }
    }

    fun cancelR2Disconnect() {
        _state.update { it.copy(r2 = it.r2.copy(confirmDisconnect = false)) }
    }

    fun disconnectR2() {
        viewModelScope.launch {
            r2Repository.clear()
            _state.update {
                it.copy(
                    r2 = R2Card(),
                    status = StorageStatus(providerLabel = AppConstants.DEFAULT_STORAGE_PROVIDER),
                    message = "Cloudflare R2 endpoint removed from this device.",
                )
            }
        }
    }

    // ------------------------------------------------------------ status card

    fun refreshStatus() {
        viewModelScope.launch {
            val r2 = r2Repository.current()
            _state.update {
                it.copy(
                    status = it.status.copy(
                        configured = r2 != null,
                        providerLabel = r2?.providerName ?: AppConstants.DEFAULT_STORAGE_PROVIDER,
                        loading = true,
                        error = null,
                        requiresSignIn = false,
                    ),
                )
            }
            if (r2 == null) {
                _state.update { it.copy(status = it.status.copy(loading = false)) }
                return@launch
            }

            var reachable: Boolean? = null
            var bucketReady: Boolean? = null
            var maxUploadBytes: Long? = null
            when (val health = storage.health()) {
                is AppResult.Success -> {
                    reachable = health.data.ok
                    bucketReady = health.data.bucketReady
                    maxUploadBytes = health.data.maxUploadBytes
                }

                is AppResult.Failure -> {
                    _state.update {
                        it.copy(
                            status = it.status.copy(
                                loading = false,
                                workerReachable = false,
                                error = health.error.message,
                                checkedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                    return@launch
                }
            }

            var verified: ProviderUsage? = null
            var requiresSignIn = false
            when (val usage = storage.usage()) {
                is AppResult.Success -> verified = usage.data
                is AppResult.Failure -> {
                    requiresSignIn = usage.error.kind == FailureKind.AUTH
                }
            }

            val metadata = when (val usage = gallery.usage()) {
                is AppResult.Success -> usage.data
                is AppResult.Failure -> null
            }

            _state.update {
                it.copy(
                    status = it.status.copy(
                        loading = false,
                        workerReachable = reachable,
                        bucketReady = bucketReady,
                        maxUploadBytes = maxUploadBytes,
                        verified = verified,
                        metadata = metadata,
                        requiresSignIn = requiresSignIn,
                        // Cloudflare does not publish a per-bucket quota through the S3 API, so the
                        // card shows "not reported" instead of a made-up number.
                        freeQuotaBytes = null,
                        error = null,
                        checkedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
