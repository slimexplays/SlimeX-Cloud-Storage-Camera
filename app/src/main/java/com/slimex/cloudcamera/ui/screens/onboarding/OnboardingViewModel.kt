package com.slimex.cloudcamera.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    private val _finishing = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val completed: SharedFlow<Unit> = _completed.asSharedFlow()

    fun next() = _step.update { (it + 1).coerceAtMost(TOTAL_STEPS - 1) }

    fun back() = _step.update { (it - 1).coerceAtLeast(0) }

    fun goTo(step: Int) {
        _step.value = step.coerceIn(0, TOTAL_STEPS - 1)
    }

    /**
     * Marks onboarding complete even if persistence fails — the user must never be
     * trapped in the intro flow because of a storage error.
     */
    fun finish() {
        if (_finishing.value) return
        viewModelScope.launch {
            _finishing.value = true
            runCatching { settingsRepository.setOnboardingCompleted(true) }
            _finishing.value = false
            _completed.emit(Unit)
        }
    }

    companion object {
        const val TOTAL_STEPS = 4
        const val STEP_WELCOME = 0
        const val STEP_PRIVACY = 1
        const val STEP_PERMISSIONS = 2
        const val STEP_CLOUD = 3
    }
}
