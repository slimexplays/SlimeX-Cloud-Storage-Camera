plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------------------------
// Release signing
// ---------------------------------------------------------------------------------------------
// The keystore is NEVER committed and no password ever lives in this file or in gradle.properties.
// Whatever builds a release supplies the key at build time through the standard Android
// "injected signing" properties — the same mechanism Android Studio's
// *Build > Generate Signed App Bundle or APK* uses, and what .github/workflows/build-apk.yml
// passes from repository secrets:
//
//   -Pandroid.injected.signing.store.file=<absolute path to the .jks/.keystore>
//   -Pandroid.injected.signing.store.password=<store password>
//   -Pandroid.injected.signing.key.alias=<key alias>
//   -Pandroid.injected.signing.key.password=<key password>
//
// When all four are present a "release" signing config is created and applied to the `release`
// build type, so `assembleRelease` writes app-release.apk and `bundleRelease` writes
// app-release.aab, both signed. When they are absent (a debug build, or a release build on a
// machine without the key) no release signing config is created at all: the build produces
// app-release-unsigned.apk instead of silently signing with a placeholder.
val injectedStoreFile = providers.gradleProperty("android.injected.signing.store.file").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
val injectedStorePassword = providers.gradleProperty("android.injected.signing.store.password").orNull
    ?.takeIf { it.isNotEmpty() }
val injectedKeyAlias = providers.gradleProperty("android.injected.signing.key.alias").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
val injectedKeyPassword = providers.gradleProperty("android.injected.signing.key.password").orNull
    ?.takeIf { it.isNotEmpty() }

// The keystore must also exist: a mistyped path is a configuration error, not a silent skip.
val hasInjectedSigning = injectedStoreFile != null &&
    injectedStorePassword != null &&
    injectedKeyAlias != null &&
    injectedKeyPassword != null &&
    file(injectedStoreFile).isFile

android {
    namespace = "com.slimex.cloudcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slimex.cloudcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasInjectedSigning) {
            create("release") {
                storeFile = file(injectedStoreFile!!)
                storePassword = injectedStorePassword
                keyAlias = injectedKeyAlias
                keyPassword = injectedKeyPassword
                // AGP defaults are kept: v2 + v3 APK signatures (and the JAR signature that a
                // bundle needs), which is exactly what apksigner verifies in CI.
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No keystore is committed: the signature comes from the injected properties above,
            // which the GitHub Actions release job supplies from repository secrets.
            if (hasInjectedSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    // AndroidX core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose (BOM aligned)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Dependency injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.video)

    // Persistence / security
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Networking (Supabase REST + Storage)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Image loading / capture metadata
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
