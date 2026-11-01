package com.slimex.cloudcamera.ui.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.security.RecoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Minimum length for the recovery passphrase (also enforced by the recovery manager). */
private const val MIN_PASSPHRASE = 8

/**
 * Drives the Encryption & Recovery screen: shows the key vault state, builds recovery codes and
 * restores them. Every key operation happens locally; nothing is sent to Supabase.
 */
@HiltViewModel
class EncryptionRecoveryViewModel @Inject constructor(
    private val recovery: RecoveryManager,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val vaultAvailable: Boolean = true,
        val hasMasterKey: Boolean = false,
        val keyCount: Int = 0,
        val lastExportMillis: Long? = null,
        val storeError: String? = null,
        val passphrase: String = "",
        val confirmPassphrase: String = "",
        val codeInput: String = "",
        val restorePassphrase: String = "",
        val busy: Boolean = false,
        val exportedCode: String? = null,
        val message: String? = null,
        val error: String? = null,
        val showResetDialog: Boolean = false,
    ) {
        val canExport: Boolean
            get() = !busy &&
                passphrase.length >= MIN_PASSPHRASE &&
                passphrase == confirmPassphrase &&
                vaultAvailable

        val canRestore: Boolean
            get() = !busy && codeInput.isNotBlank() && restorePassphrase.isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val status = recovery.status()
            _state.update { current ->
                current.copy(
                    loading = false,
                    vaultAvailable = status.vaultAvailable,
                    hasMasterKey = status.hasMasterKey,
                    keyCount = status.keyCount,
                    lastExportMillis = status.lastExportMillis,
                    storeError = status.storeError,
                )
            }
        }
    }

    fun onPassphraseChange(value: String) {
        _state.update { it.copy(passphrase = value, error = null) }
    }

    fun onConfirmPassphraseChange(value: String) {
        _state.update { it.copy(confirmPassphrase = value, error = null) }
    }

    fun onCodeInputChange(value: String) {
        _state.update { it.copy(codeInput = value, error = null) }
    }

    fun onRestorePassphraseChange(value: String) {
        _state.update { it.copy(restorePassphrase = value, error = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun discardExportedCode() {
        _state.update { it.copy(exportedCode = null) }
    }

    fun createRecoveryCode() {
        val current = _state.value
        if (current.passphrase.length < MIN_PASSPHRASE) {
            _state.update { it.copy(error = "Use at least $MIN_PASSPHRASE characters.") }
            return
        }
        if (current.passphrase != current.confirmPassphrase) {
            _state.update { it.copy(error = "The two passphrases do not match.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val passphrase = current.passphrase.toCharArray()
            when (val result = recovery.export(passphrase)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            exportedCode = result.data.code,
                            lastExportMillis = result.data.createdAtMillis,
                            keyCount = result.data.keyCount,
                            passphrase = "",
                            confirmPassphrase = "",
                            message = "Recovery code created for ${result.data.keyCount} key(s). " +
                                "Store it somewhere safe before you leave this screen.",
                        )
                    }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
            passphrase.fill('\u0000')
        }
    }

    fun restoreKeys() {
        val current = _state.value
        if (current.codeInput.isBlank()) {
            _state.update { it.copy(error = "Paste the recovery code first.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val passphrase = current.restorePassphrase.toCharArray()
            when (val result = recovery.restore(current.codeInput, passphrase)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            codeInput = "",
                            restorePassphrase = "",
                            keyCount = result.data,
                            hasMasterKey = true,
                            message = "Restored ${result.data} key(s). Cloud media should open again.",
                        )
                    }
                    refresh()
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
            passphrase.fill('\u0000')
        }
    }

    fun requestReset() {
        _state.update { it.copy(showResetDialog = true) }
    }

    fun cancelReset() {
        _state.update { it.copy(showResetDialog = false) }
    }

    fun confirmReset() {
        _state.update { it.copy(showResetDialog = false, busy = true, error = null) }
        viewModelScope.launch {
            when (val result = recovery.resetLocalKeys()) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            keyCount = 0,
                            hasMasterKey = false,
                            lastExportMillis = null,
                            exportedCode = null,
                            message = "Local keys cleared. Existing cloud files can only be opened " +
                                "again with a recovery code created before this reset.",
                        )
                    }
                    refresh()
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
        }
    }
}
