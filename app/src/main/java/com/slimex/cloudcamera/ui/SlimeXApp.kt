package com.slimex.cloudcamera.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.slimex.cloudcamera.navigation.SlimeXNavHost
import com.slimex.cloudcamera.ui.components.BrandedSplash

@Composable
fun SlimeXApp() {
    val viewModel: AppStartupViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Crossfade(
        targetState = state,
        animationSpec = tween(durationMillis = 350),
        label = "app-startup",
    ) { current ->
        when (current) {
            is AppStartupState.Loading -> BrandedSplash()
            is AppStartupState.Ready -> SlimeXNavHost(
                startWithOnboarding = !current.onboardingCompleted,
            )
        }
    }
}
