package com.slimex.cloudcamera.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.security.SecureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SupabaseConfigViewModel @Inject constructor(
    private val cloudConfigRepository: CloudConfigRepository,
    secureStore: SecureStore,
) : ViewModel() {

    data class State(
        val url: String = "",
        val key: String = "",
        val keyVisible: Boolean = false,
        val savedHost: String? = null,
        val savedAt: Long? = null,
        val testing: Boolean = false,
        val saving: Boolean = false,
        val report: ConnectionReport? = null,
        val fieldError: String? = null,
        val message: String? = null,
        val bucket: String = AppConstants.DEFAULT_BUCKET,
        val table: String = AppConstants.MEDIA_TABLE,
        val storageWarning: String? = secureStore.storageWarning,
        val showRemoveDialog: Boolean = false,
    ) {
        val isConfigured: Boolean get() = savedHost != null
        val canSubmit: Boolean get() = url.isNotBlank() && key.isNotBlank() && !saving && !testing
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = cloudConfigRepository.current()
            if (existing != null) {
                _state.update {
                    it.copy(
                        url = existing.projectUrl,
                        key = existing.publishableKey,
                        savedHost = existing.host,
                        savedAt = existing.savedAt.takeIf { time -> time > 0 },
                        bucket = existing.bucket,
                        table = existing.table,
                    )
                }
            }
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, fieldError = null) }

    fun onKeyChange(value: String) = _state.update { it.copy(key = value, fieldError = null) }

    fun toggleKeyVisibility() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    fun testConnection() {
        if (_state.value.testing) return
        val url = _state.value.url
        val key = _state.value.key
        viewModelScope.launch {
            _state.update { it.copy(testing = true, fieldError = null, report = null) }
            val result = runCatching {
                cloudConfigRepository.testConnection(
                    projectUrl = url,
                    publishableKey = key,
                    onStep = { report -> _state.update { current -> current.copy(report = report) } },
                )
            }.getOrNull()

            _state.update { current ->
                when (result) {
                    null -> current.copy(testing = false, message = "The connection test could not run.")
                    is AppResult.Success -> current.copy(
                        testing = false,
                        report = result.data,
                        message = if (result.data.success) {
                            "Connection verified — you can save now."
                        } else {
                            "Some checks need attention. Review the steps below."
                        },
                    )
                    is AppResult.Failure -> current.copy(
                        testing = false,
                        fieldError = result.error.message,
                    )
                }
            }
        }
    }

    fun save() {
        if (_state.value.saving) return
        val url = _state.value.url
        val key = _state.value.key
        viewModelScope.launch {
            _state.update { it.copy(saving = true, fieldError = null) }
            when (val result = cloudConfigRepository.save(url, key)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        saving = false,
                        savedHost = result.data.host,
                        savedAt = result.data.savedAt,
                        url = result.data.projectUrl,
                        key = result.data.publishableKey,
                        bucket = result.data.bucket,
                        table = result.data.table,
                        message = "Configuration saved and encrypted on this device.",
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(saving = false, fieldError = result.error.message)
                }
            }
        }
    }

    fun requestRemove() = _state.update { it.copy(showRemoveDialog = true) }

    fun dismissRemove() = _state.update { it.copy(showRemoveDialog = false) }

    fun removeConfiguration() {
        viewModelScope.launch {
            runCatching { cloudConfigRepository.clear() }
            _state.update {
                it.copy(
                    showRemoveDialog = false,
                    url = "",
                    key = "",
                    savedHost = null,
                    savedAt = null,
                    report = null,
                    fieldError = null,
                    message = "Configuration removed from this device.",
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
