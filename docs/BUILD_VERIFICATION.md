# Build verification report

This document records **exactly** what was verified while the project was written, what could not
be verified, and how to close that gap. It exists so the project never claims a build it did not
produce.

---

## TL;DR

| Item | Status |
| --- | --- |
| Source code complete for Parts 1 + 2 + 3 + 4 in one module | ✅ verified by review **and by a real compile** |
| Cloudflare Worker source (no dependencies) | ✅ `node --check` on every file |
| Worker route behaviour | ✅ 11 offline tests with mocked Supabase + R2 (`cd worker && npm test`) |
| Worker SigV4 correctness | ✅ 2 parity tests — signatures byte-identical to `aws4fetch` 1.0.20 |
| Worker deployed to Cloudflare / live R2 | ❌ **not executed** — no Cloudflare account in the authoring sandbox |
| Gradle wrapper, version catalog, manifest, resources parse | ✅ verified (XML/TOML parsed, wrapper matches Gradle 8.9) |
| `./gradlew testDebugUnitTest` executed | ✅ **GitHub Actions**, run [41287478568](https://github.com/slimexplays/SlimeX-Cloud-Storage-Camera/actions/runs/41287478568) — all unit tests green |
| `./gradlew assembleDebug` executed | ✅ same run — `app-debug.apk` built and uploaded as an artifact |
| `./gradlew assembleRelease` executed | ✅ same run — **signed** `app-release.apk`, verified by `apksigner` |
| `./gradlew bundleRelease` executed | ✅ same run — **signed** `app-release.aab`, verified by `jarsigner` |
| Release signing configuration | ✅ injected `android.injected.signing.*` properties; APK signer certificate SHA-256 matches the injected keystore |
| Locally in the authoring sandbox (`./gradlew …`) | ❌ still impossible — no JDK/Android SDK/Maven access there, so the build is run on GitHub Actions instead of being claimed from the sandbox |

Everything that follows is what *was* checked. The Android build itself runs on GitHub Actions
(real JDK 17 + Android SDK 35), which is the authoritative result; the authoring sandbox still has
no toolchain, so nothing below is claimed from there.

---

## 1. Where the build actually runs

The authoring sandbox has **no JDK, no Android SDK and no access to Google's/Maven repositories**,
so Gradle cannot run there. The build therefore runs on **GitHub Actions** with a real JDK 17 and
Android SDK 35, and the workflow is the source of truth:

| Job | Command | Result |
| --- | --- | --- |
| `assembleDebug + unit tests` | `./gradlew testDebugUnitTest`, `./gradlew clean assembleDebug` | ✅ green, `app-debug.apk` uploaded |
| `assembleRelease + bundleRelease (signed and verified)` | `./gradlew assembleRelease bundleRelease -Pandroid.injected.signing.*` | ✅ green, signed APK + AAB uploaded and verified |

Run: <https://github.com/slimexplays/SlimeX-Cloud-Storage-Camera/actions/runs/41287478568>

Before the workflow could go green it surfaced seven **real** defects that no amount of reading had
caught — the value of an actual compiler:

| # | Defect found by the build | Fix |
| --- | --- | --- |
| 1 | `SupabaseValidation.normalizeUrl` built `Result(projectUrl = …, publishableKey = …)` with parameter names that do not exist | use `normalizedUrl` / `normalizedKey` |
| 2 | `CloudConfigRepositoryImpl` referenced `SupabaseValidation.Valid/Invalid` instead of the nested `SupabaseValidation.Validation.*` | corrected type names |
| 3 | `SupabaseDataApi` called the `JsonObject.toCloudMedia()` extension on `JsonElement` rows | cast to `JsonObject` first |
| 4 | `SettingsViewModel.State` (a nested class) read a ViewModel constructor parameter | moved the warning to a property passed into the initial state |
| 5 | `SettingsScreen` smart-cast a delegated property | `?.let { … }` |
| 6 | `CameraViewModel` used `result.isFailure`; `AppResult` only exposes `isSuccess` | `!result.isSuccess` |
| 7 | `VaultCrypto` called `SecretKeySpec(master)` — the class has no single-argument constructor | `SecretKeySpec(master, "AES")` |
| 8 | `UploadRepositoryImpl.drain()` hid the interface member without `override` | added `override` |
| 9 | R8 failed on missing `com.google.errorprone.annotations.**` (compile-time annotations referenced by Tink, pulled in by `androidx.security:security-crypto`) | `-dontwarn com.google.errorprone.annotations.**` |
| 10 | `UploadTaskTest."blocked states are not active work"` failed because `UploadStatus.isActive` included the `WAITING_FOR_*` states | `isActive` now means "work is progressing" (no production code read it) |

---

## 2. Static verification performed

### 2.1 Project structure

- 100 Kotlin source files under `app/src/main/java/com/slimex/cloudcamera`, 6 JVM test files under
  `app/src/test`.
- Single module (`:app`), single `applicationId` `com.slimex.cloudcamera`, single `MainActivity`,
  single `NavHost` (`navigation/SlimeXNavHost.kt`), single theme.
- No duplicate screens: every destination in `Destinations.kt` maps to exactly one composable and
  every composable is reachable from the graph.

### 2.2 Structural audits (scripted)

| Audit | Result |
| --- | --- |
| Braces/parentheses balanced per file | pass (all files) |
| Duplicate `import` lines | none |
| Imports that reference types not declared in the project and not in the dependency list | none found |
| Non-ASCII characters in source | only typographic characters in comments/strings (`…`, `·`, `→`, `—`, `’`) — no invisible/confusable code points |
| TODO/FIXME/placeholder markers | only documentation text, no code stubs |
| `@Composable` functions returning values | none (Compose requires `Unit`) |
| Suspending calls made from non-suspend contexts | reviewed call sites (`keyStore.get` inside repository `suspend` functions, recovery calls inside `viewModelScope.launch`) |
| Repository interface ↔ implementation members | every `override` matches a declared member (`UploadRepository`, `CloudGalleryRepository`, `LocalMediaRepository`, `AuthRepository`, `SettingsRepository`, `CloudConfigRepository`, plus the new `VaultCrypto`, `MediaKeyStore`, `RecoveryManager`) |
| Hilt bindings for every injected interface | `AppModule.BindingsModule` + `RepositoryModule` cover all interfaces; `MediaKeyStore`, `VaultCrypto`, `RecoveryManager`, `MediaCrypto` and `SecureStore` are bound as singletons |
| Key material absent from the cloud layer | `SupabaseDataApi` no longer serialises `encryption_key` / `encryption_nonce` / `thumbnail_key` / `thumbnail_nonce`; `CloudMedia` no longer carries them; `schema.sql` drops and rejects them |

### 2.3 Resources / configuration

| File | Check |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | parses as XML; permissions limited to INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS; `allowBackup="false"`, `usesCleartextTraffic="false"`, FileProvider not exported with path grants only |
| `app/src/main/res/xml/file_paths.xml` | parses; exposes `cache/share/` only |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | excluded from backup |
| `gradle/libs.versions.toml` | parses as TOML; every `libs.*` alias used in `app/build.gradle.kts` is declared |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.9 distribution, matching AGP 8.7.3 + Kotlin 2.0.21 |

### 2.4 Dependency compatibility (declared versions)

| Dependency | Version | Why it is safe |
| --- | --- | --- |
| AGP | 8.7.3 | requires Gradle 8.9+ and JDK 17 — both pinned |
| Kotlin | 2.0.21 | Kotlin 2.0 Compose Compiler is the `org.jetbrains.kotlin.plugin.compose` plugin (no `composeOptions`) |
| Compose BOM | 2024.12.01 | Material 3 1.3.x, Foundation 1.7.x — matches `minSdk 26` |
| CameraX | 1.4.1 | `camera-core/camera2/lifecycle/view/video` all same version |
| Hilt | 2.52 | supports Kotlin 2.0 (kapt) and `@HiltWorker` via `androidx.hilt:hilt-compiler` 1.2.0 |
| WorkManager | 2.9.1 | `CoroutineWorker`, constraints, exponential backoff, unique work |
| Navigation Compose | 2.8.5 | single `NavHost` with typed routes as strings |
| security-crypto | 1.1.0-alpha06 | needed for `EncryptedSharedPreferences` on API 26+; alpha because 1.0.0 has a Keystore bug fixed here |
| OkHttp 4.12.0 / kotlinx-serialization 1.7.3 / coroutines 1.9.0 / Coil 2.7.0 / exifinterface 1.3.7 | — | mutually compatible, all stable |

### 2.5 Security review (Part 3 scope)

- No hardcoded URLs, keys, tokens, passwords or encryption secrets anywhere in `app/src`
  (`grep` for `supabase.co`, `eyJ`, `service_role`, `sb_secret`, `https://` only matches validation
  help text and example placeholders in the UI).
- `service_role` / `sb_secret_` / JWT role checks reject privileged keys before they are saved.
- Logging funnels through `SecureLog` (no-op unless `BuildConfig.DEBUG`); `android.util.Log` is used
  nowhere else in app code; WorkManager logging is `ERROR` in release.
- Storage/RLS reasoning: every policy compares `user_id` to `auth.uid()`; the storage policies use
  the first path segment of the object name, which the app always sets to the owner's id.
- Key handling: AES-256-GCM with a fresh 256-bit key and 96-bit nonce per file; vault master key
  wrapped by a non-exportable Android Keystore key; vault nonces from a persisted counter; recovery
  codes PBKDF2-HMAC-SHA256 (210 000) + AES-256-GCM with authenticated payloads.

---

## 3. Points the compiler had to settle

These were the risky spots; the GitHub Actions build settled all of them (all green). If one of them
ever fails locally, the fix is local and small.

1. **Kapt + Hilt on JDK 17** — `kapt` must run before `compileDebugKotlin`; if the IDE reports
   "cannot find symbol DaggerSlimeXApplication", do *Build → Clean Project* then *Rebuild*.
2. **Compose compiler plugin** — `alias(libs.plugins.kotlin.compose)` must stay applied together
   with Kotlin 2.0.21; do not add `composeOptions.kotlinCompilerExtensionVersion`.
3. **Material icons** — this project uses `material-icons-extended`; a handful of icons
   (`Icons.Rounded.Restore`, `Key`, `ContentCopy`, `CloudOff`, `VideoSettings`) exist there. If one
   is renamed in a future BOM, the compiler error names it directly.
4. **`LocalClipboardManager`** is deprecated in newer Compose releases (still functional). A
   deprecation warning is expected, not an error.
5. **`security-crypto 1.1.0-alpha06`** may emit a warning about alpha usage; the alternative
   (1.0.0) has a known Keystore crash on some devices.
6. **Lint** — `abortOnError = false` for this build so lint cannot block `assembleDebug`.

---

## 4. Reproduce the build yourself (5 commands)

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35.0.0) required
git clone <your-fork-url>
cd SlimeX-Cloud-Storage-Camera
./gradlew clean assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                         # JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or, with no local toolchain at all: push to GitHub and run **Actions → Build APKs → Run workflow**
(`.github/workflows/build-apk.yml`). On `ubuntu-latest` with JDK 17 and the runner's Android SDK it
runs two jobs:

| Job | Produces |
| --- | --- |
| *assembleDebug + unit tests* | unit-test results + `slimex-cloud-camera-debug-apk` artifact |
| *assembleRelease + bundleRelease (signed and verified)* | `slimex-cloud-camera-release-signed` artifact: signed `app-release.apk`, signed `app-release.aab`, and the `apksigner` / `jarsigner` output |

### Signed release build — where the key comes from

Signing is **never** committed. `app/build.gradle.kts` reads the standard injected properties and
only creates the `release` signing config when all four are present:

```bash
./gradlew assembleRelease bundleRelease \
  -Pandroid.injected.signing.store.file=/abs/path/release.jks \
  -Pandroid.injected.signing.store.password=… \
  -Pandroid.injected.signing.key.alias=… \
  -Pandroid.injected.signing.key.password=…
```

In CI the same four values are supplied from repository secrets — `RELEASE_KEYSTORE_BASE64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` — and when those secrets
are absent the workflow generates a **throwaway** keystore inside the runner so the signing and
verification path is still exercised end to end. A build signed with that throwaway key cannot be
distributed or upgrade an install signed with the real key.

---

## 5. Sign-off checklist

- [x] `./gradlew clean assembleDebug` finishes with `BUILD SUCCESSFUL` (GitHub Actions).
- [x] `./gradlew testDebugUnitTest` → all unit tests green (formatters, queue DTO, storage paths,
      upload/cloud models, recovery envelope, key ring format).
- [x] `assembleRelease` + `bundleRelease` produce a signed APK and AAB that `apksigner` /
      `jarsigner` verify (see §6).
- [ ] `app-debug.apk` installs on the Realme C31 and reaches the splash screen.
- [ ] `docs/TESTING_CHECKLIST.md` scenarios 1–20 executed once.
- [ ] `sql` scripts run clean on a fresh Supabase project; `media_files` has **no key columns**.
- [ ] The signed release APK installs on the Realme C31 (needs your own key, or the CI artifact).

---

## 6. Part 4 verification (Supabase + Cloudflare R2)

### What was run here

| Check | Command | Result |
| --- | --- | --- |
| Worker syntax | `for f in worker/src/*.js; do node --check "$f"; done` | ✅ all files parse (Node 22) |
| Worker routes (mocked Supabase + R2) | `cd worker && npm test` | ✅ 11/11 |
| Presigned PUT parity | same suite | ✅ signature identical to `aws4fetch` 1.0.20 |
| Header-signed S3 request parity | same suite | ✅ signature identical to `aws4fetch` 1.0.20 |
| Kotlin: no reference to a removed API | `grep -rn "SupabaseStorageApi\|probeBucket\|DEFAULT_BUCKET" app/src` | ✅ no matches |
| Kotlin: R2 credentials never referenced in app code | grep for `R2_SECRET`, `R2_ACCESS_KEY`, `r2.cloudflarestorage.com`, `service_role` | ✅ no matches (only the Worker URL is stored) |
| Docs: no stale `storage.sql` references | `grep -rn "storage.sql"` | ✅ none |

### What the Android build verified (GitHub Actions, run 41287478568)

| Check | Result |
| --- | --- |
| `testDebugUnitTest` | ✅ all unit tests green |
| `assembleDebug` | ✅ `app-debug.apk` (21.8 MB) |
| `assembleRelease` | ✅ signed `app-release.apk` (no `app-release-unsigned.apk` produced) |
| `bundleRelease` | ✅ signed `app-release.aab` |
| `apksigner verify --print-certs` | ✅ v2/v3 signature, signer SHA-256 `aae2d654…7fda81` |
| `zipalign -c -v 4` | ✅ 4-byte aligned |
| `jarsigner -verify` on the AAB | ✅ `jar verified` |
| APK signer == injected keystore certificate | ✅ SHA-256 compared in the job; mismatch would fail it |

### What was **not** run anywhere (and cannot be)

- `wrangler deploy` and any request to a real R2 bucket — no Cloudflare account or credentials are
  available here. The Worker was exercised offline with a stubbed network only.
- Any on-device test on the Realme C31 — the debug APK exists as a CI artifact, but no device was
  attached to any of this work.
- A release build signed with **your** production key: that needs your keystore, either locally or
  as the four repository secrets above.

### Exact commands to close the gap

```bash
# 1. Android build (needs JDK 17 + Android SDK 35)
./gradlew :app:assembleDebug          # expect app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest

# 2. Signed release (needs your keystore)
./gradlew assembleRelease bundleRelease \
  -Pandroid.injected.signing.store.file=/abs/path/release.jks \
  -Pandroid.injected.signing.store.password=… -Pandroid.injected.signing.key.alias=… \
  -Pandroid.injected.signing.key.password=…
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
jarsigner -verify app/build/outputs/bundle/release/app-release.aab

# 3. Worker tests (needs Node 18+)
cd worker && npm install && npm test  # expect 13 passing

# 3. Worker deploy (needs a Cloudflare account)
npx wrangler login
npx wrangler secret put R2_ACCOUNT_ID   # …and the other five secrets
npx wrangler deploy
curl -s https://<your-worker>.workers.dev/health | jq
```

If step 1 fails, the failure will be a compile error in the Part 4 additions rather than a design
problem: those files (`cloud/r2/*`, `ui/screens/settings/CloudConfiguration*`,
`data/repository/R2ConfigRepositoryImpl.kt`, `di/` bindings) are the only ones whose signatures
could not be checked by a compiler here.
