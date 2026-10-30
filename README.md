# SlimeX Cloud Camera

A production-oriented Android camera + private cloud storage app.
Pure black UI, purple accents, CameraX capture and a Supabase backend **that the user
configures after installing** — no keys, tokens or secrets exist anywhere in the APK.

**Made By Slime Plays** · Package `com.slimex.cloudcamera` · Kotlin + Jetpack Compose

---

## Status — Part 1 of 3

This repository is the **single, unified project** for the whole specification.
Part 1 establishes the foundation (architecture, design system, navigation, onboarding,
permissions, camera engine, Supabase configuration and the upload pipeline).
Parts 2 and 3 are integrated into *this* project — no second app, no second module.

| Area | State in Part 1 |
| --- | --- |
| Gradle / project skeleton (Kotlin DSL, version catalog) | ✅ complete |
| Material 3 black + purple theme, typography, reusable components | ✅ complete |
| Unified navigation (6 sections, one NavHost) | ✅ complete |
| First-launch experience (branded splash, privacy, permissions, cloud setup) | ✅ complete |
| CameraX preview, JPEG photo capture, MP4 video recording, audio toggle | ✅ complete |
| Photo / video quality preferences (auto 1080p with automatic fallback) | ✅ complete |
| Supabase configuration screen (test / save / remove, encrypted storage) | ✅ complete |
| Upload queue, background retry worker, cloud gallery, storage dashboard | ✅ complete |
| Sharing, advanced media playback, notifications UI | ⏳ Parts 2–3 |

---

## Features

- **Pure black camera** — full-screen CameraX preview with no decorative background,
  flash control (auto/on/off), front/back switching, photo & video modes.
- **Real captures only** — JPEG photos via `ImageCapture`, MP4 videos via `Recorder`;
  files are written to app-private internal storage (`filesDir/media/...`).
- **Settings-first credentials** — the Supabase URL and publishable key are entered at
  runtime and stored with `EncryptedSharedPreferences` (AES-256 via Android Keystore).
- **Connection diagnostics** — four ordered checks (endpoint, key, bucket, table) with
  per-step status, instead of a single opaque "test failed".
- **Upload queue** — persisted in DataStore, survives process death, live progress,
  retry / cancel, Wi-Fi-only mode, WorkManager safety-net worker.
- **Cloud gallery** — listings from `media_assets`, thumbnails through short-lived
  signed URLs on a **private** bucket, delete from cloud.
- **Storage dashboard** — local photo/video/thumbnail breakdown, device free space,
  remote bucket usage.
- **No fake functionality** — every button performs a real action; unsupported content
  (e.g. inline video playback in this build) is labelled honestly.

---

## Project structure

```
app/src/main/java/com/slimex/cloudcamera/
├── SlimeXApplication.kt            Hilt entry point + WorkManager configuration
├── MainActivity.kt                 Single activity, edge-to-edge, splash theme
├── core/
│   ├── constants/AppConstants.kt   non-sensitive constants (bucket, table, dirs)
│   ├── network/ConnectivityObserver.kt
│   ├── result/AppResult.kt         typed Success/Failure + FailureKind
│   └── util/                       dispatch providers, formatters
├── data/
│   ├── local/UploadTaskEntity.kt   persisted queue DTO + mappers
│   └── repository/                 DataStore, encrypted config, media, upload, cloud
├── domain/
│   ├── model/                      AppSettings, CloudConfig, UploadTask, CloudAsset…
│   ├── repository/                 interfaces used by ViewModels
│   └── usecase/                    SaveSupabaseConfig, TestSupabaseConnection
├── camera/CameraController.kt      CameraX binding, capture, recording state machine
├── cloud/                          Supabase REST client + validation + connection tester
├── security/                       EncryptedSecureStore (Keystore)
├── storage/LocalStorageManager.kt  app-private media tree + usage
├── upload/UploadScheduler.kt       WorkManager retry scheduling
├── gallery/                        (reserved for Part 2 media playback)
├── sharing/                        (reserved for Part 3 share targets)
├── navigation/                     Destinations + unified NavHost
├── ui/
│   ├── theme/                      Colour, Type, Shape, Theme, spacing
│   ├── components/                 TopBar, BottomBar, buttons, cards, dialogs, states
│   ├── screens/                    onboarding, home, camera, gallery, uploads,
│   │                               storage, settings, about
│   ├── Permissions.kt              dependency-free runtime permission helper
│   └── SlimeXApp.kt                startup routing (splash → onboarding → main)
├── workers/UploadRetryWorker.kt    Hilt worker that resumes pending uploads
└── di/                             AppModule + RepositoryModule
```

Resources: `drawable/` (vector brand mark + adaptive icon layers), `mipmap-anydpi-v26/`,
`values/` (black theme + splash theme), `values-v31/`, `xml/` (backup rules).

---

## Build

### Requirements

| Tool | Version |
| --- | --- |
| Android Studio | Ladybug (2024.2) or newer |
| JDK | 17 |
| Gradle | 8.9 (wrapper included) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |

### Debug APK

```bash
git clone <this-repo>
cd SlimeX-Cloud-Storage-Camera
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release APK

Create a keystore (never commit it), then add a signing config or build an unsigned
release and sign it with `apksigner`:

```bash
keytool -genkeypair -v -keystore slimex-release.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias slimex

./gradlew :app:assembleRelease
# R8 minifies + shrinks; rules live in app/proguard-rules.pro

apksigner sign --ks slimex-release.jks \
        --out SlimeX-Cloud-Camera-release.apk \
        app/build/outputs/apk/release/app-release-unsigned.apk
```

> The primary target device is a Realme C31 (Android 12 / API 31, entry-level SoC).
> Capture modes use `CAPTURE_MODE_MINIMIZE_LATENCY`, video asks for 1080p but falls back
> automatically, and the preview uses `PreviewView.ImplementationMode.COMPATIBLE`.

---

## Using the app

1. **Launch** → branded splash → privacy explanation → contextual permissions.
2. **Permissions** — camera is requested only when you open the camera; microphone only
   when you record video with sound; notifications only on Android 13+ and only if enabled.
3. **Settings → Supabase configuration** — paste your project URL and publishable key,
   tap **Test connection**, then **Save**. See [`supabase/README.md`](supabase/README.md)
   for the one-time SQL setup.
4. **Camera** — capture; with a configuration in place every capture is queued and
   uploaded to your bucket automatically.
5. **Gallery** — switch between *Cloud* and *On device* to inspect both sides.

The app is fully usable without Supabase: capture, gallery (device), storage management
and settings all work; uploads simply wait in the queue.

---

## Design system

| Token | Value |
| --- | --- |
| Background | `#000000` (pure black) |
| Card surface | `#111114` → `#18181D` gradient |
| Primary | `#A855F7` (`#7C3AED` deep, `#C084FC` light) |
| Body text | `#F5F5F7` / secondary `#A1A1AA` / tertiary `#6C6C78` |
| Success / warning / error | `#34D399` / `#FBBF24` / `#F87171` |
| Corner radius | 8 / 12 / 18 / 24 / 30 dp |
| Spacing scale | 4 → 32 dp (4 dp baseline) |

The camera screen is **theme-exempt by design**: only the preview surface plus
translucent overlay controls, no purple decoration.

---

## Architecture notes

- **MVVM + Repository** — Compose screens observe `StateFlow` from `@HiltViewModel`s;
  ViewModels depend on domain repository interfaces only.
- **Dependency injection** — Hilt (SingletonComponent), `@HiltWorker` for WorkManager.
- **Error handling** — repository methods return `AppResult<T>` with a `FailureKind`,
  so the UI can distinguish "not configured", "network", "auth" and "storage" failures.
  No screen crashes when credentials or permissions are missing.
- **Persistence** — DataStore (preferences) for settings and the upload queue,
  Keystore-encrypted preferences for credentials, filesystem for media.
- **Concurrency** — structured coroutines, an application-scoped upload engine and a
  `SupervisorJob`; blocking HTTP runs on `Dispatchers.IO`.

## Privacy

- No analytics, no ad SDKs, no third-party servers.
- Captures live in app-private storage; nothing is uploaded until *you* configure a project.
- Credentials never leave the device except as the `apikey` header to your own project.
- `allowBackup="false"` — credentials and media are excluded from cloud backups.

## Roadmap

- **Part 2** — richer capture controls, in-app media player, batch selection, per-item
  upload controls, notification progress.
- **Part 3** — share targets (WhatsApp / Instagram / system share), FileProvider export,
  final APK packaging and release checklist.

---

Made By **Slime Plays**.
