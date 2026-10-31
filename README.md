# SlimeX Cloud Camera

A single Android app for private, encrypted capture: a pure-black CameraX camera, encrypted
uploads to **your own Supabase project**, a private cloud gallery and delayed Sharesheet
hand-off. No keys, tokens or secrets exist anywhere in the APK — you configure your project
after installing.

**Made By Slime Plays** · Package `com.slimex.cloudcamera` · Kotlin + Jetpack Compose

---

## Status — Parts 1 + 2 complete, Part 3 pending

This repository is the **single, unified project** for the whole specification: one app, one
package, one navigation graph, one APK. Part 2 was added to the existing project — nothing was
re-created and all Part 1 screens still work.

| Area | State |
| --- | --- |
| Gradle / project skeleton (Kotlin DSL, version catalog) | ✅ complete |
| Material 3 black + purple theme, typography, reusable components | ✅ complete |
| Unified navigation (one NavHost, 5 tabs + settings stack + account) | ✅ complete |
| First-launch experience (branded splash, privacy, permissions, cloud setup) | ✅ complete |
| CameraX preview, JPEG photo capture, MP4 recording, front/rear, flash, zoom, tap-to-focus | ✅ complete |
| Photo/video quality preferences (balanced JPEG, 1080p preferred with automatic fallback) | ✅ complete |
| AES-256-GCM encryption of every capture, app-private staging only | ✅ **Part 2** |
| Supabase Auth (email/password, session refresh, encrypted token storage) | ✅ **Part 2** |
| Offline upload queue (WorkManager, backoff, dedupe, cancel, zero-local-storage mode) | ✅ **Part 2** |
| Private cloud gallery (thumbs, viewer, video playback, search, sort, delete, share) | ✅ **Part 2** |
| Storage dashboard (cloud usage, counts, pending/failed, cache clean-up) | ✅ **Part 2** |
| Direct WhatsApp/Sharesheet sharing via FileProvider + delayed clean-up | ✅ **Part 2** |
| Final packaging / release checklist / Part 3 features | ⏳ Part 3 |

---

## What the app actually does

### Capture

- Full-screen, **pure black** camera: live preview, minimal white controls, photo/video
  selector, flash (auto/on/off — the torch in video mode), zoom slider, tap-to-focus and
  tap-to-meter with a focus ring, front/rear switch, recording timer with a red indicator and
  a gallery shortcut.
- Photos are JPEGs from `ImageCapture`; video is MP4 from `Recorder`. There is **no claim of
  matching any OEM's stock camera processing** — Balanced uses a 92 % JPEG quality and
  `CAPTURE_MODE_MINIMIZE_LATENCY`, High uses 100 % and maximised quality.
- Video quality prefers **1080p**, falls back to 720p/SD automatically on devices that do not
  support it. Audio is recorded only when the microphone permission is granted (the take
  continues silently if you decline).
- Lifecycle-safe: the camera is bound to the lifecycle owner, unbound on dispose, and a
  recording is finalised rather than silently lost.

### Storage of captures (never public)

```
/data/data/com.slimex.cloudcamera/files/pending/photos/…   ← app-private, unencrypted source
/data/data/com.slimex.cloudcamera/files/pending/videos/…
/data/data/com.slimex.cloudcamera/cache/thumbs/…           ← evictable previews
/data/data/com.slimex.cloudcamera/cache/viewer/…           ← decrypted while open
/data/data/com.slimex.cloudcamera/cache/share/…            ← Sharesheet hand-off (TTL 15 min)
```

- Nothing is ever written to DCIM, Pictures, Movies, MediaStore or any public folder, so the
  app requests **no storage permission** and no other app can read a capture.
- Every file is encrypted with **AES-256-GCM** (fresh 256-bit key, fresh 96-bit nonce, streamed
  through `CipherOutputStream`) before it leaves the staging area; the ciphertext is what gets
  uploaded.
- Local files are deleted **only after** the uploaded object has been verified: the object is
  listed at the expected path, its size compared, and (for objects up to 64 MB) downloaded
  back and SHA-256-checked against the local ciphertext. Videos drop both the original and the
  encrypted copy after that check.

### Supabase (runtime configuration, private bucket)

1. **Settings → Supabase configuration** — paste the Project URL and the *publishable/anon*
   key, run **Test connection** (endpoint → key → bucket → table), then **Save**. Credentials
   are stored with `EncryptedSharedPreferences` (Android Keystore, AES-256).
2. **Account** — sign in or create an account with email/password. Access and refresh tokens
   are stored in the same Keystore-encrypted store, refreshed 60 s before expiry, and cleared
   when the project is removed or changed.
3. Objects are written to the **private** bucket `slimex-private-media` at
   `{user_id}/{year}/{month}/{unique_file_id}.enc`, with an encrypted sidecar preview at
   `…/{unique_file_id}.thumb.enc`.
4. Metadata lives in `public.media_files`; the queue mirrors its state in
   `public.upload_jobs`. Both tables use RLS scoped to `auth.uid()`.
5. No service-role key, no secret key, no public bucket, no signed "anyone with the link" URLs.
   Every storage call carries the signed-in user's access token and the bucket policies limit
   each user to their own folder.

### Upload queue

- WorkManager drives a durable queue persisted as a single JSON document in DataStore, with a
  network constraint and exponential backoff; the in-app engine performs the transfer so the
  UI shows live progress.
- States: `QUEUED → ENCRYPTING → UPLOADING → VERIFYING → UPLOADED`, plus `WAITING_FOR_NETWORK`,
  `WAITING_FOR_AUTH`, `WAITING_FOR_CONFIG`, `FAILED` and `CANCELLED`.
- Duplicate jobs are prevented by file path; retries reuse the row rather than re-downloading;
  a crash mid-upload discards the half-written ciphertext (its key is gone) and starts again
  with a new key.
- **Zero local storage mode** (Settings): capture is blocked while offline, so no media is
  buffered on the device at all.
- Auto-resume: a reconnect, app start or the worker picks the queue back up.

### Cloud gallery

- Tabs All / Photos / Videos, search by file name, four sort orders, pull-to-refresh action.
- Grid thumbnails come from the encrypted sidecar, decrypted into the private cache; a tile
  never downloads a full photo or video.
- Full-screen viewer: photos via Coil, videos via a real `VideoView` player with system media
  controls; details show type, size and upload date.
- Only what you open is downloaded and decrypted, into `cache/viewer/`.
- Delete removes the bucket objects and the row (a missing object is still treated as deleted);
  cache files for that item are cleared too.

### Sharing

- Sharing decrypts the file into `cache/share/`, exposes it through **FileProvider** as a
  `content://` URI with a read grant and an accurate MIME type, and opens the Android
  Sharesheet (`ACTION_SEND`).
- The copy is **not** deleted immediately: `ShareCleanupWorker` removes it after 15 minutes and
  reschedules itself while fresh copies exist.
- There are no public links. Note that **the receiving app may keep its own copy** — WhatsApp,
  Telegram or any gallery app stores its own file once it has the bytes; that is outside this
  app's control.

### Storage dashboard

- Cloud usage, file counts and photo/video split (computed from your own rows).
- Pending and failed uploads, uploaded bytes this session.
- Device figures: waiting-to-upload, encrypted copies, thumbnails, viewer cache, share cache.
- **Clear caches** only touches evictable caches. Files that are pending or unverified are
  never removed from here.

---

## Project structure

```
app/src/main/java/com/slimex/cloudcamera/
├── SlimeXApplication.kt            Hilt entry point + WorkManager configuration
├── MainActivity.kt                 single activity, edge-to-edge, splash theme
├── auth/SupabaseAuthManager.kt     GoTrue session: sign in/up, refresh, sign out
├── camera/                         CameraController (CameraX) + capture models
├── cloud/                          Supabase REST layer
│   ├── SupabaseAuthApi.kt          auth/v1 signup, token, refresh, logout
│   ├── SupabaseStorageApi.kt       private bucket: upload, download, list, remove
│   ├── SupabaseDataApi.kt          media_files + upload_jobs (PostgREST)
│   ├── CloudSessionProvider.kt     config + non-expired session in one step
│   ├── SupabaseRestClient.kt       diagnostics-only probing used by setup
│   └── SupabaseValidation.kt / HttpOutcome.kt / CloudFailures.kt
├── core/
│   ├── constants/AppConstants.kt   non-sensitive constants (bucket, dirs, cache names)
│   ├── network/ConnectivityObserver.kt
│   ├── result/AppResult.kt         typed Success/Failure + FailureKind
│   └── util/                       dispatchers, formatters, StoragePaths (object layout)
├── data/
│   ├── local/                      upload queue DTO + DataStore persistence
│   └── repository/                 settings, cloud config, gallery, upload, staging
├── domain/
│   ├── model/                      settings, auth, media, upload, cloud models
│   ├── repository/                 interfaces consumed by ViewModels
│   └── usecase/                    SaveSupabaseConfig, TestSupabaseConnection
├── media/ThumbnailFactory.kt       JPEG previews from photos and video frames
├── security/                       Keystore stores + AES-256-GCM media crypto
├── sharing/MediaSharer.kt          FileProvider + Sharesheet + TTL clean-up
├── storage/StagingStore.kt         every private directory, listing, usage, safe clean-up
├── upload/                         MediaUploader pipeline + WorkManager scheduler
├── navigation/                     Destinations + the one NavHost
├── ui/
│   ├── theme/                      colour, type, shape, theme, spacing
│   ├── components/                 top bar, bottom bar, buttons, cards, dialogs, states
│   ├── screens/                    onboarding, home, camera, gallery, uploads, storage,
│   │                               settings (cloud + camera), account, about
│   ├── Permissions.kt              dependency-free runtime permission helper
│   └── SlimeXApp.kt                startup routing (splash → onboarding → main)
├── workers/                        UploadWorker, ShareCleanupWorker
└── di/                             AppModule + RepositoryModule
```

`supabase/schema.sql` and `supabase/storage.sql` are the one-time database setup; see
[`supabase/README.md`](supabase/README.md).

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

Create a keystore (never commit it), then sign the release build:

```bash
keytool -genkeypair -v -keystore slimex-release.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias slimex

./gradlew :app:assembleRelease
# R8 minifies + shrinks; rules live in app/proguard-rules.pro

apksigner sign --ks slimex-release.jks \
        --out SlimeX-Cloud-Camera-release.apk \
        app/build/outputs/apk/release/app-release-unsigned.apk
```

> Primary target device: Realme C31 (Android 12 / API 31, entry-level SoC). Video asks for
> 1080p and steps down automatically, the preview uses `PreviewView.ImplementationMode.COMPATIBLE`,
> and photos use a balanced JPEG so shutter latency stays reasonable.

---

## First run

1. **Launch** → branded splash → privacy explanation → contextual permissions.
2. **Settings → Supabase configuration** → paste URL + publishable key → **Test connection** →
   **Save**. Run the SQL from `supabase/` once in your project.
3. **Account** → create an account (confirm the email if your project requires it) → sign in.
4. **Camera** → capture. Each capture is encrypted, queued and uploaded when a connection is
   available; with zero-local-storage mode enabled, capture is blocked while offline instead.
5. **Cloud** → browse, search, sort, open, play, share, delete.
6. **Storage** → see cloud + device usage, pending/failed uploads, clear caches.

The app is usable without Supabase: capture, the queue (blocked), storage and settings all
work; nothing is uploaded and nothing is lost.

---

## Privacy & security model

- No analytics, no ad SDKs, no third-party servers; the only network peer is your own project.
- No hardcoded credentials: the URL and publishable key are typed in and stored encrypted.
  Service-role/secret keys are rejected at validation, including a JWT `role: service_role`
  claim check.
- The bucket is private and RLS-protected; storage policies compare the first path segment to
  `auth.uid()`.
- File contents are AES-256-GCM ciphertext at rest in the bucket. The per-file key and nonce
  are stored in `media_files`, readable **only by the owner** through RLS — that is what makes
  decryption possible after a reinstall. Someone with your publishable key alone cannot read
  them: the key grants nothing without your signed-in session.
- Media never enters shared storage: previews and decrypted copies live in the app cache and
  are evicted/cleaned. `allowBackup="false"` keeps credentials and media out of backups.
- Deleting the app deletes the Keystore keys used for the stored session and configuration;
  cloud objects stay in your project (that is what "cloud backup" means here).

### Known limits (stated honestly)

- Video playback uses the platform `VideoView`/`MediaPlayer`; exotic codecs that the device
  cannot decode will not play.
- Upload verification downloads the object back only up to 64 MB (larger files are verified by
  size + listing) so a 500 MB video does not double the traffic.
- The bucket enforces `application/octet-stream` and a 500 MB per-object limit; raise the limit
  in `storage.sql` if you record longer clips.
- Part 3 will cover the remaining surface (notifications with progress, batch operations,
  release packaging). No screen in this build is a placeholder.

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

The camera screen is **theme-exempt by design**: only the preview surface plus translucent
overlay controls, no purple decoration.

---

## Architecture notes

- **MVVM + Repository** — Compose screens observe `StateFlow` from `@HiltViewModel`s;
  ViewModels depend on domain interfaces only.
- **Dependency injection** — Hilt (`SingletonComponent`), `@HiltWorker` for WorkManager;
  `Provider<AuthRepository>` breaks the config ↔ auth cycle.
- **Error handling** — repositories return `AppResult<T>` with a `FailureKind`
  (`NETWORK`, `AUTH`, `NOT_CONFIGURED`, `STORAGE`, …) so the queue can decide between
  waiting, retrying or asking you to sign in. No screen throws on a missing configuration.
- **Persistence** — DataStore for settings and the upload queue, Keystore-encrypted
  preferences for credentials and the auth session, the filesystem for media.
- **Concurrency** — one upload engine guarded by a `Mutex`, serialised token refresh, and a
  conflated persist channel so progress updates never hammer disk.
- **REST, not the SDK** — the Supabase Kotlin SDK binds credentials at build time; this app
  needs runtime configuration, so `cloud/` is a small, explicit OkHttp + kotlinx.serialization
  client.

## Tests

```
./gradlew :app:testDebugUnitTest
```

Covers formatters, the persisted queue DTO, the bucket path builder (`{user}/{year}/{month}/{id}`),
upload state semantics, queue summary maths and the gallery models.

---

Made By **Slime Plays**.
