package com.slimex.cloudcamera.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.SignUpOutcome
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val cloudConfigRepository: CloudConfigRepository,
) : ViewModel() {

    data class State(
        val email: String = "",
        val password: String = "",
        val createAccount: Boolean = false,
        val busy: Boolean = false,
        val configured: Boolean = false,
        val host: String? = null,
        val status: AuthStatus = AuthStatus.Loading,
        val error: String? = null,
        val message: String? = null,
    ) {
        val canSubmit: Boolean
            get() = !busy && email.trim().contains('@') && password.length >= 6
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.restore()
            authRepository.status.collect { status -> _state.update { it.copy(status = status) } }
        }
        viewModelScope.launch {
            cloudConfigRepository.config.collect { config ->
                _state.update { it.copy(configured = config != null, host = config?.host) }
            }
        }
    }

    fun setEmail(value: String) = _state.update { it.copy(email = value, error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun toggleMode() = _state.update {
        it.copy(createAccount = !it.createAccount, error = null)
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            if (current.createAccount) {
                when (val result = authRepository.signUp(current.email, current.password)) {
                    is AppResult.Success -> when (val outcome = result.data) {
                        is SignUpOutcome.Authenticated -> _state.update {
                            it.copy(
                                busy = false,
                                password = "",
                                message = "Signed in as ${outcome.session.email}",
                            )
                        }

                        is SignUpOutcome.ConfirmationRequired -> _state.update {
                            it.copy(
                                busy = false,
                                createAccount = false,
                                message = "Check ${outcome.email} to confirm the account, then sign in.",
                            )
                        }
                    }

                    is AppResult.Failure -> _state.update {
                        it.copy(busy = false, error = result.error.message)
                    }
                }
            } else {
                when (val result = authRepository.signIn(current.email, current.password)) {
                    is AppResult.Success -> _state.update {
                        it.copy(busy = false, password = "", message = "Signed in as ${result.data.email}")
                    }

                    is AppResult.Failure -> _state.update {
                        it.copy(busy = false, error = result.error.message)
                    }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _state.update { it.copy(password = "", message = "Signed out") }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
