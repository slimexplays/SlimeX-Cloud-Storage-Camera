# Build verification report

This document records **exactly** what was verified while the project was written, what could not
be verified, and how to close that gap. It exists so the project never claims a build it did not
produce.

---

## TL;DR

| Item | Status |
| --- | --- |
| Source code complete for Parts 1 + 2 + 3 in one module | ✅ verified by review |
| Gradle wrapper, version catalog, manifest, resources parse | ✅ verified (XML/TOML parsed, wrapper matches Gradle 8.9) |
| Kotlin structure audit (balanced braces/imports, no duplicate imports, no stray non-ASCII) | ✅ verified by script |
| `./gradlew clean assembleDebug` executed | ❌ **not executed** — the authoring sandbox has no JDK, no Android SDK and no access to Google's/Maven's repositories |
| `./gradlew test` executed | ❌ not executed for the same reason |
| APK / AAB produced | ❌ none produced (an APK cannot be built without the Android SDK; no download link is provided because none exists) |

Everything that follows is what *was* checked, then the one-command path to finish the job on a
machine that has the toolchain.

---

## 1. Why no APK was produced here

The authoring environment provides a shell with:

- **no JDK** (`java`, `javac` absent; `/usr/lib/jvm` does not exist),
- **no Android SDK** (`ANDROID_HOME` empty, no `sdkmanager`),
- **no outbound access** to `repo1.maven.org`, `dl.google.com` or `services.gradle.org`
  (only GitHub/npm/PyPI hosts are reachable).

Gradle needs all three (a JDK to run, the SDK to compile against `android-35`, the repositories to
resolve AGP, Kotlin, Compose, Hilt, CameraX, OkHttp…). Therefore:

- no `assembleDebug` run, no APK, no APK link — inventing one would be dishonest;
- the code was verified by reading and by scripted audits instead (below).

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

## 3. What a real build must confirm (expected residual risks)

These are the places where a compiler is the only judge. If any fails, the fix is local and small.

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

Or, with no local toolchain at all: push to GitHub and run **Actions → Build debug APK → Run
workflow** (`.github/workflows/build-apk.yml`). It runs on `ubuntu-latest` with JDK 17 and the
Android SDK preinstalled, executes `clean assembleDebug` + `testDebugUnitTest`, and uploads the APK
as an artifact.

---

## 5. Sign-off checklist for the first real build

- [ ] `./gradlew clean assembleDebug` finishes with `BUILD SUCCESSFUL`.
- [ ] `./gradlew test` → all unit tests green (formatters, queue DTO, storage paths, upload/cloud
      models, recovery envelope, key ring format).
- [ ] `app-debug.apk` installs on the Realme C31 and reaches the splash screen.
- [ ] `docs/TESTING_CHECKLIST.md` scenarios 1–20 executed once.
- [ ] `sql` scripts run clean on a fresh Supabase project; `media_files` has **no key columns**.
- [ ] Release build (`assembleRelease`) completes with R8 and installs.
