# SlimeX Cloud Camera

A single Android app for private, encrypted capture: a pure-black CameraX camera, AES-256-GCM
encryption on the device, encrypted media in **your own private Cloudflare R2 bucket** reached only
through **your own Cloudflare Worker**, metadata and authentication in **your own Supabase project**,
a private cloud gallery and delayed Sharesheet hand-off. No keys, tokens or secrets exist anywhere in
the APK — you configure your two endpoints after installing.

**Made By Slime Plays** · Package `com.slimex.cloudcamera` · Kotlin + Jetpack Compose

> **Parts 1 + 2 + 3 + 4 are complete and merged into this one project.** One app, one package, one
> navigation graph, one APK — there is no second module, no demo build and no mock screen.

---

## Status — Parts 1, 2, 3 and 4 complete

This repository is the **single, unified project** for the whole specification: one app, one
package, one navigation graph, one APK. Each part was added to the existing project — nothing was
re-created, and every Part 1 screen still works exactly as before.

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
| Device-bound key vault (Android Keystore) + optional per-media keys | ✅ **Part 3** |
| Recovery code export/restore (PBKDF2 + AES-256-GCM), reset with warning | ✅ **Part 3** |
| Encryption & Recovery screen wired into the single nav graph | ✅ **Part 3** |
| Secure configuration review, debug-only logging, cleartext traffic disabled | ✅ **Part 3** |
| Expanded failure handling (low storage, corrupt capture, missing key, reset vault) | ✅ **Part 3** |
| Unit tests for the recovery envelope and key ring format | ✅ **Part 3** |
| Setup guides, testing checklist, known limitations, build verification report | ✅ **Part 3** |
| Two-card **Cloud configuration** screen (Supabase + Cloudflare R2, per-step tests, no fake "Connected") | ✅ **Part 4** |
| Cloudflare R2 media backend behind a Cloudflare Worker (Supabase-JWT auth, presigned URLs, ownership checks) | ✅ **Part 4** |
| Worker source (`worker/`): 7 JSON endpoints, SigV4 on WebCrypto, size/rate limits, structured errors | ✅ **Part 4** |
| Supabase is metadata-only (no Storage bucket, no R2 credentials, no key columns) | ✅ **Part 4** |
| Storage status: verified provider usage vs recorded metadata, "not reported" instead of a fake quota | ✅ **Part 4** |
| Worker deployment guide + API reference + 13 offline Worker tests | ✅ **Part 4** |

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

### Cloud configuration (two services, two jobs — Part 4)

**Settings → Cloud configuration** holds two independent cards. Neither can show "Connected" until
its own test has actually passed.

**Supabase — authentication + PostgreSQL metadata**

1. Paste the Project URL and the *publishable/anon* key, run **Test connection**
   (endpoint → key → `media_files` table), then **Save**. Values go to
   `EncryptedSharedPreferences` (Android Keystore, AES-256). A `service_role` or `sb_secret_…`
   key is rejected before it is ever stored.
2. **Account** — sign in or create an account with email/password. Access and refresh tokens live
   in the same Keystore-encrypted store, refresh 60 s before expiry, and are cleared when the
   project is removed or changed.
3. Supabase stores **metadata only**: `public.media_files` (type, size, MIME, checksum, object key,
   timestamps) and `public.upload_jobs` (queue bookkeeping). Both tables are RLS-scoped to
   `auth.uid()`. There is no Supabase Storage bucket, no media byte and no key column.

**Cloudflare R2 — the private bucket for encrypted media**

1. Paste only the **Worker endpoint** (`https://slimex-media.<you>.workers.dev`) and keep the
   provider label "Cloudflare R2". `Test connection` runs four steps: Worker endpoint → Supabase
   session accepted → Cloudflare R2 reachable → storage access verified.
2. The Worker holds the R2 account id, access key, secret key, bucket name and S3 endpoint as
   encrypted secrets. **The APK contains none of them** — not in `AppConstants`, not in prefs, not
   in the database. Entering a Supabase project URL by mistake is refused by validation.
3. Uploads go `authorize → PUT presigned URL → stat verify`, so the ciphertext streams straight to
   R2 and the phone never holds a credential. Downloads use a 5-minute presigned GET.
4. Object keys are `{user_id}/{year}/{month}/{unique_file_id}.enc` (+ `.thumb.enc`). The Worker
   rejects any key that does not start with the *verified* Supabase user id, so cross-user access
   is impossible even with a modified client.
5. The R2 bucket has no public access and no public URL; the Worker never returns a bucket name, a
   credential or a URL for an object you do not own.

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

### Encryption & recovery (Part 3)

```
files/keys/keyring.v1        ← per-media keys, sealed with the vault master key
Android Keystore              ← non-exportable AES-256-GCM key that wraps the master key
Settings → Encryption & Recovery → one recovery code (SLIMEX-KEY-1:…)
```

- **No key ever reaches Supabase.** `media_files` stores only a path, size, MIME type and the
  plaintext SHA-256; `encryption_key`, `encryption_nonce`, `thumbnail_key` and `thumbnail_nonce`
  do not exist as columns any more (the SQL drops them if an older build created them).
- Every file gets a **fresh random 256-bit key and a fresh 96-bit nonce**; GCM authentication
  means a modified ciphertext fails to decrypt instead of yielding garbage.
- Per-media keys are kept in a small JSON key ring that is sealed with a random 256-bit *vault
  master key*; the master key is wrapped by a key that lives in the **Android Keystore** and
  cannot be exported from it.
- Nonces for the vault are produced from a persisted counter (`4 random bytes || 8-byte
  counter`), so a nonce is never reused under the same key, even across crashes.
- **Recovery code**: `SLIMEX-KEY-1:` + base64 of `magic | salt(16) | nonce(12) | AES-256-GCM`.
  The passphrase is stretched with PBKDF2-HMAC-SHA256 (210 000 rounds) and the payload holds the
  vault key plus every media key, so a reinstall or a new phone can open the existing library.
  The code is shown once, can be copied or shared as plain text, and is never uploaded.
- **Losing both the code and the passphrase makes encrypted cloud media permanently
  unreadable** — the screen says so before and after export, and the reset action repeats it.
- Importing a recovery code on a device that already has a key ring replaces the local key ring
  (that is the point: the code is the source of truth for that library).
- If the Keystore is unavailable, the app fails closed: uploads are blocked rather than writing
  keys somewhere weaker.

### Storage dashboard & cloud status

- **Verified usage** — bytes/objects the Worker listed from your own R2 prefix.
- **Recorded metadata** — what `media_files` in Supabase says you uploaded.
  The two are shown side by side and labelled, because they legitimately differ while an upload or
  delete is in flight.
- **Free quota: "Not reported by provider"** — R2 publishes no per-bucket quota over the S3 API, so
  the app says so instead of inventing a number.
- Provider label, Worker reachability, bucket readiness, last-checked time.
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
├── cloud/                          cloud transport layer
│   ├── SupabaseAuthApi.kt          auth/v1 signup, token, refresh, logout
│   ├── SupabaseDataApi.kt          media_files + upload_jobs (PostgREST)
│   ├── CloudSessionProvider.kt     config + non-expired session in one step
│   ├── SupabaseRestClient.kt       diagnostics-only probing used by setup
│   ├── SupabaseConnectionTester.kt ordered Supabase checks for the setup card
│   ├── r2/                         Cloudflare R2 through the Worker (Part 4)
│   │   ├── R2WorkerClient.kt       the 7 Worker endpoints, error mapping
│   │   ├── WorkerMediaStorage.kt   MediaStorageGateway: authorize → PUT/GET, stat, delete
│   │   ├── R2ConnectionTester.kt   4-step media backend diagnostic
│   │   └── R2Validation.kt         Worker URL / provider-name validation
│   └── SupabaseValidation.kt / HttpOutcome.kt / CloudFailures.kt
├── core/
│   ├── constants/AppConstants.kt   non-sensitive constants (dirs, cache names, provider label)
│   ├── network/ConnectivityObserver.kt
│   ├── result/AppResult.kt         typed Success/Failure + FailureKind
│   └── util/                       dispatchers, formatters, StoragePaths, SecureLog
├── data/
│   ├── local/                      upload queue DTO + DataStore persistence
│   └── repository/                 settings, cloud config, gallery, upload, staging
├── domain/
│   ├── model/                      settings, auth, media, upload, cloud models
│   ├── repository/                 interfaces consumed by ViewModels
│   └── usecase/                    SaveSupabaseConfig, TestSupabaseConnection
├── media/ThumbnailFactory.kt       JPEG previews from photos and video frames
├── security/
│   ├── MediaCrypto.kt              streaming AES-256-GCM encrypt/decrypt + SHA-256
│   ├── VaultCrypto.kt              Android Keystore wrapped vault master key, counter nonces
│   ├── MediaKeyStore.kt            local key ring (media id → key/nonce), atomically sealed
│   ├── RecoveryEnvelope.kt         PBKDF2 + AES-GCM recovery code format (JVM testable)
│   ├── RecoveryManager.kt          export / restore / reset of the key ring
│   ├── SecureStore.kt              credential storage contract
│   └── EncryptedSecureStore.kt     EncryptedSharedPreferences implementation
├── sharing/MediaSharer.kt          FileProvider + Sharesheet + TTL clean-up
├── storage/StagingStore.kt         every private directory, listing, usage, safe clean-up
├── upload/                         MediaUploader pipeline + WorkManager scheduler
├── navigation/                     Destinations + the one NavHost
├── ui/
│   ├── theme/                      colour, type, shape, theme, spacing
│   ├── components/                 top bar, bottom bar, buttons, cards, dialogs, states
│   ├── screens/                    onboarding, home, camera, gallery, uploads, storage,
│   │                               settings (cloud + camera + security), account, about
│   ├── Permissions.kt              dependency-free runtime permission helper
│   └── SlimeXApp.kt                startup routing (splash → onboarding → main)
├── workers/                        UploadWorker, ShareCleanupWorker
└── di/                             AppModule + RepositoryModule
```

`supabase/schema.sql` is the one-time database setup (metadata only); see
[`supabase/README.md`](supabase/README.md). The media backend is the Cloudflare Worker in
[`worker/`](worker) — deploy it with [`docs/CLOUDFLARE_R2_WORKER_SETUP.md`](docs/CLOUDFLARE_R2_WORKER_SETUP.md).

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

No APK is committed to this repository (binaries do not belong in Git). If you have no local
Android toolchain, push the project to GitHub and run the included **Build debug APK** workflow
(`.github/workflows/build-apk.yml`, *Actions → Build debug APK → Run workflow*): it builds on a
clean runner and uploads `app-debug.apk` as an artifact you can download and install.

`docs/BUILD_VERIFICATION.md` records exactly what was and was not verified while the project was
authored, so nothing here overstates the build state.

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
2. **Settings → Cloud configuration → Supabase** → paste URL + publishable key →
   **Test connection** → **Save**. Run `supabase/schema.sql` once in your project.
   Then **Cloud configuration → Cloudflare R2** → paste the Worker URL → **Test connection** →
   **Save** (deploy the Worker first: [`docs/CLOUDFLARE_R2_WORKER_SETUP.md`](docs/CLOUDFLARE_R2_WORKER_SETUP.md)).
3. **Account** → create an account (confirm the email if your project requires it) → sign in.
4. **Settings → Encryption & Recovery** → create a recovery code and store it off-device. Do this
   before you accumulate media you cannot afford to lose: it is the only way back into your
   library after a reinstall.
5. **Camera** → capture. Each capture is encrypted, queued and uploaded when a connection is
   available; with zero-local-storage mode enabled, capture is blocked while offline instead.
6. **Cloud** → browse, search, sort, open, play, share, delete.
7. **Storage** → see cloud + device usage, pending/failed uploads, clear caches.

The app is usable without either service: capture, the queue (blocked), storage and settings all
work; nothing is uploaded and nothing is lost. Both cards must be green before an upload can start,
and *neither* card shows "Connected" until its own test has passed.

---

## Privacy & security model

- No analytics, no ad SDKs, no third-party servers; the only network peers are your own Supabase
  project and your own Cloudflare Worker.
- No hardcoded credentials: the Supabase URL + publishable key and the Worker URL are typed in and
  stored encrypted. Service-role/secret keys are rejected at validation, including a JWT
  `role: service_role` claim check.
- **No R2 credential exists in the app at all.** The account id, access key, secret key and bucket
  name live only as Cloudflare Worker secrets. The Worker verifies the Supabase JWT, derives the
  `{userId}/` prefix itself and returns a 5-minute presigned URL for one object.
- The R2 bucket is private: no public access, no public URL, no `r2.dev` development URL. Object
  ownership is enforced by the Worker before a URL is signed, and metadata ownership by RLS.
- File contents are AES-256-GCM ciphertext at rest in the bucket. The per-file keys live only on
  the device (Keystore-wrapped key ring) and are **never written to Supabase**, so the cloud holds
  nothing that can decrypt your media. Reading a file requires the device key *and* an
  authenticated session for the owner's rows.
- All traffic is HTTPS (`usesCleartextTraffic="false"`); the publishable key is sent only as the
  `apikey`/Bearer header, never logged. Tokens and the Supabase configuration sit in
  Keystore-backed encrypted preferences and never appear in logcat.
- Logging goes through `SecureLog`, which is a no-op in release builds: passwords, API keys,
  tokens, encryption keys and file contents are never printed. WorkManager's own logging is
  raised to `ERROR` in release.
- The `FileProvider` exposes exactly one directory (`cache/share/`) for read-only grants; pending
  captures, the key ring and decrypted viewer copies are unreachable from other apps.
- `allowBackup="false"` plus explicit backup/data-extraction rules keep media, tokens and keys
  out of cloud backups and device-to-device transfers.
- Media never enters shared storage: previews and decrypted copies live in the app cache and
  are evicted/cleaned. `allowBackup="false"` keeps credentials and media out of backups.
- Deleting the app deletes the Keystore keys used for the stored session and configuration;
  cloud objects stay in your project (that is what "cloud backup" means here).

### Known limits (stated honestly)

- Video playback uses the platform `VideoView`/`MediaPlayer`; exotic codecs that the device
  cannot decode will not play.
- Upload verification downloads the object back only up to 64 MB (larger files are verified by
  size + listing) so a 500 MB video does not double the traffic.
- The Worker authorises `application/octet-stream` only and caps objects at 500 MB
  (`MAX_UPLOAD_BYTES`); raise the var if you record longer clips (ceiling stays 500 MB).
- The Worker was **not** deployed from this repository's authoring environment — signature parity
  with `aws4fetch` and the route behaviour were verified offline, but the real Cloudflare endpoints
  must be confirmed on your account. See [`docs/KNOWN_LIMITATIONS.md`](docs/KNOWN_LIMITATIONS.md).
- Keys are device-bound by design. Opening a library on a second device requires exporting the
  recovery code on the first device and importing it on the second one.
- Losing both the recovery code and its passphrase is unrecoverable — there is no server-side
  copy of your keys, which is exactly why the cloud cannot leak them.
- Photos are re-encoded JPEGs (no RAW/DNG, no HDR pipeline); the app never claims OEM-level
  processing. Night/low-light performance matches what CameraX gives an entry-level sensor.

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
  client. The media side uses `MediaStorageGateway`, an interface with exactly one implementation
  (`WorkerMediaStorage`), so nothing above it knows the Worker protocol.
- **Presigned transfer, not proxying** — the Worker signs a URL and the phone streams ciphertext
  directly to R2. A 500 MB video therefore never passes through a Worker (which has CPU/memory
  limits), and the Worker stays a small, auditable authorisation service.

## Tests

```
./gradlew :app:testDebugUnitTest
```

Covers formatters, the persisted queue DTO, the object-key builder (`{user}/{year}/{month}/{id}`),
upload state semantics, queue summary maths, the gallery models, the **recovery envelope**
(round trip, wrong passphrase, tampered bytes, salt/nonce freshness, whitespace tolerance) and the
**key ring JSON format**.

The recovery tests are pure JVM tests — no emulator and no Robolectric needed.

The Cloudflare Worker has its own offline suite:

```bash
cd worker && npm install && npm test
```

13 tests: route behaviour with a mocked Supabase + R2 (auth, ownership, traversal rejection,
size/content-type limits, usage scoping, stat, delete, operation allowlist, structured errors) and
**SigV4 parity** — the
presigned PUT URL and the header-signed list request produce byte-identical signatures to
`aws4fetch` 1.0.20.

### Documentation

| Document | Contents |
| --- | --- |
| [`docs/ANDROID_STUDIO_SETUP.md`](docs/ANDROID_STUDIO_SETUP.md) | open the project, SDK setup, run on a device, build debug/release APKs |
| [`docs/SUPABASE_SETUP.md`](docs/SUPABASE_SETUP.md) | create the project, run the SQL, auth settings (metadata only) |
| [`docs/CLOUDFLARE_R2_WORKER_SETUP.md`](docs/CLOUDFLARE_R2_WORKER_SETUP.md) | create the private R2 bucket, scoped token, deploy + verify the Worker, API reference, limits, troubleshooting |
| [`docs/TESTING_CHECKLIST.md`](docs/TESTING_CHECKLIST.md) | the 20 required test scenarios plus 14 Part 4 cloud tests, with expected results (Realme C31 / API 31) |
| [`docs/KNOWN_LIMITATIONS.md`](docs/KNOWN_LIMITATIONS.md) | what this build genuinely cannot do, and why |
| [`docs/BUILD_VERIFICATION.md`](docs/BUILD_VERIFICATION.md) | what was statically verified, what still needs a real Gradle run |
| [`supabase/README.md`](supabase/README.md) | SQL script, RLS policies, metadata-only notes |
| [`worker/README.md`](worker/README.md) | Worker file map, quick start, guarantees |

---

Made By **Slime Plays**.
