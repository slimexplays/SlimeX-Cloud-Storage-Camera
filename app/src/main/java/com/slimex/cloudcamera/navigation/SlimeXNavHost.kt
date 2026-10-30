package com.slimex.cloudcamera.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slimex.cloudcamera.ui.components.SlimeXBottomBar
import com.slimex.cloudcamera.ui.screens.about.AboutScreen
import com.slimex.cloudcamera.ui.screens.camera.CameraScreen
import com.slimex.cloudcamera.ui.screens.gallery.CloudGalleryScreen
import com.slimex.cloudcamera.ui.screens.home.HomeScreen
import com.slimex.cloudcamera.ui.screens.onboarding.OnboardingScreen
import com.slimex.cloudcamera.ui.screens.settings.CameraPreferencesScreen
import com.slimex.cloudcamera.ui.screens.settings.SettingsScreen
import com.slimex.cloudcamera.ui.screens.settings.SupabaseConfigScreen
import com.slimex.cloudcamera.ui.screens.storage.StorageScreen
import com.slimex.cloudcamera.ui.screens.uploads.UploadQueueScreen

/**
 * The single navigation graph of the application. Every part of the spec feeds into
 * these destinations — screens never create their own navigators.
 */
@Composable
fun SlimeXNavHost(
    startWithOnboarding: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && currentRoute in bottomBarRoutes

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                SlimeXBottomBar(
                    items = bottomTabs,
                    currentRoute = currentRoute,
                    onSelect = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (startWithOnboarding) Destinations.ONBOARDING else Destinations.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                fadeIn(tween(220)) + slideInVertically(tween(240)) { full -> full / 26 }
            },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                fadeOut(tween(150)) + slideOutVertically(tween(200)) { full -> full / 26 }
            },
        ) {
            composable(Destinations.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Destinations.HOME) {
                            popUpTo(Destinations.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenCloudSetup = {
                        navController.navigate(Destinations.CLOUD_SETUP) { launchSingleTop = true }
                    },
                )
            }

            composable(Destinations.HOME) {
                HomeScreen(
                    contentPadding = innerPadding,
                    onOpenCamera = { navController.navigateToTab(Destinations.CAMERA) },
                    onOpenGallery = { navController.navigateToTab(Destinations.GALLERY) },
                    onOpenUploads = { navController.navigateToTab(Destinations.UPLOADS) },
                    onOpenStorage = { navController.navigateToTab(Destinations.STORAGE) },
                    onOpenSettings = { navController.navigate(Destinations.SETTINGS) { launchSingleTop = true } },
                    onOpenCloudSetup = { navController.navigate(Destinations.CLOUD_SETUP) { launchSingleTop = true } },
                )
            }

            composable(Destinations.CAMERA) {
                CameraScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Destinations.HOME) { launchSingleTop = true }
                        }
                    },
                    onOpenGallery = { navController.navigateToTab(Destinations.GALLERY) },
                )
            }

            composable(Destinations.GALLERY) {
                CloudGalleryScreen(
                    contentPadding = innerPadding,
                    onOpenCloudSetup = { navController.navigate(Destinations.CLOUD_SETUP) { launchSingleTop = true } },
                )
            }

            composable(Destinations.UPLOADS) {
                UploadQueueScreen(
                    contentPadding = innerPadding,
                    onOpenCloudSetup = { navController.navigate(Destinations.CLOUD_SETUP) { launchSingleTop = true } },
                )
            }

            composable(Destinations.STORAGE) {
                StorageScreen(
                    contentPadding = innerPadding,
                    onOpenCloudSetup = { navController.navigate(Destinations.CLOUD_SETUP) { launchSingleTop = true } },
                )
            }

            composable(Destinations.SETTINGS) {
                SettingsScreen(
                    contentPadding = innerPadding,
                    onOpenCloudSetup = { navController.navigate(Destinations.CLOUD_SETUP) { launchSingleTop = true } },
                    onOpenCameraPreferences = {
                        navController.navigate(Destinations.CAMERA_PREFERENCES) { launchSingleTop = true }
                    },
                    onOpenAbout = { navController.navigate(Destinations.ABOUT) { launchSingleTop = true } },
                    onOpenStorage = { navController.navigateToTab(Destinations.STORAGE) },
                )
            }

            composable(Destinations.CLOUD_SETUP) {
                SupabaseConfigScreen(
                    contentPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Destinations.CAMERA_PREFERENCES) {
                CameraPreferencesScreen(
                    contentPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Destinations.ABOUT) {
                AboutScreen(
                    contentPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Bottom-tab navigation that keeps a single copy of each screen and restores its state. */
private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Destinations.HOME) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
