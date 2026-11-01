# Android Studio setup — SlimeX Cloud Camera

Everything below assumes you start from a clean machine and a fresh clone of this repository.
Total time is roughly 20–30 minutes, most of it downloads.

---

## 1. Install the toolchain

| Tool | Version | Notes |
| --- | --- | --- |
| Android Studio | Ladybug (2024.2.1) or newer | Koala / Hedgehog also work; Ladybug is what the project targets |
| JDK | **17** | Android Studio bundles JBR 17 — use it (Settings → Build → Gradle → *Gradle JDK: Embedded JDK*) |
| Android SDK Platform | **API 35** | `compileSdk` / `targetSdk` |
| Android SDK Platform | API 31 or lower | needed to run on the Realme C31 (Android 12) |
| Build-Tools | 35.0.0 | installed automatically by Gradle |
| Gradle | 8.9 | the wrapper (`gradlew`) downloads it — **do not** upgrade the wrapper |
| Android Gradle Plugin | 8.7.3 | declared in `gradle/libs.versions.toml` |
| Kotlin | 2.0.21 | same file |

SDK components, if you prefer the command line:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "platforms;android-31"
```

Gradle needs a local SDK too. Either open the project in Android Studio once (it writes
`local.properties`), or create it yourself:

```properties
# local.properties  (never committed)
sdk.dir=/path/to/Android/Sdk
```

---

## 2. Open the project

1. **Android Studio → File → Open** → select the `SlimeX-Cloud-Storage-Camera` folder (the one
   containing `settings.gradle.kts`). Do **not** open `app/` on its own.
2. Wait for "Gradle sync finished". The first sync downloads AGP, Kotlin 2.0.21, Compose BOM
   `2024.12.01`, Hilt, CameraX 1.4.1 and the rest — about 400 MB.
3. If sync complains about the JDK: **Settings → Build, Execution, Deployment → Build Tools →
   Gradle → Gradle JDK → 17 (Embedded JDK)**.
4. Check the module is recognised: `app` should show the blue "Android" icon in the Project view.

### Known sync hiccups

| Symptom | Fix |
| --- | --- |
| `Unsupported class file major version` | You are on JDK 21+ for Gradle; switch to the embedded JDK 17 |
| `SDK location not found` | Create `local.properties` as shown above |
| `Plugin [id: 'com.google.dagger.hilt.android'] was not found` | You are offline; connect once and re-sync |
| Kapt warnings about `correctErrorTypes` | Harmless; Hilt needs kapt because the project does not use KSP |

---

## 3. Run it on the phone

1. On the phone: **Settings → About phone → tap Build number 7×** to unlock Developer options.
2. **Developer options → USB debugging → On**.
3. Connect USB, accept the RSA prompt.
4. Confirm the device is visible:

   ```bash
   adb devices -l
   ```

5. In Android Studio pick the device in the run dropdown and press **Run ▶** (or):

   ```bash
   ./gradlew :app:installDebug
   adb shell am start -n com.slimex.cloudcamera/.MainActivity
   ```

The first launch shows the branded splash, the privacy explainer and the contextual permission
prompts. Nothing is requested before you reach the step that needs it.

---

## 4. Build the debug APK

```bash
./gradlew clean :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install it manually:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Installing the APK on a phone without ADB

1. Copy `app-debug.apk` to the phone (USB, Drive, anything).
2. Open it from **Files** → allow "Install unknown apps" for that file manager when prompted.
3. Realme/ColorOS extra step: **Settings → Security → Install unknown apps** → pick the file
   manager → *Allow*. Some ColorOS builds also hide the install prompt behind **Phone Manager →
   Security check**.
4. After install, open **SlimeX Cloud Camera** and follow *Configure it after installing* below.

> A debug APK is signed with the debug keystore, so it can only be updated by another build signed
> with the same key on the same machine. For distribution, use the signed release APK from step 5.

### Without a local toolchain

The repository includes `.github/workflows/build-apk.yml`. Push your fork to GitHub, open
**Actions → Build debug APK → Run workflow**, and download the `slimex-cloud-camera-debug-apk`
artifact when it finishes. The workflow also runs `./gradlew :app:testDebugUnitTest`.

---

## 5. Build a release APK

Create a keystore **once** and keep it out of Git:

```bash
keytool -genkeypair -v -keystore slimex-release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias slimex
```

Then either sign through Android Studio (**Build → Generate Signed Bundle/APK → APK**, choose the
keystore, `release` build type) or from the command line:

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk

"$ANDROID_HOME/build-tools/35.0.0/zipalign" -v -p 4 \
    app/build/outputs/apk/release/app-release-unsigned.apk slimex-aligned.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
    --ks slimex-release.jks --out SlimeX-Cloud-Camera-1.0.0.apk slimex-aligned.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs SlimeX-Cloud-Camera-1.0.0.apk
```

R8 runs on release builds with the rules in `app/proguard-rules.pro` (kotlinx.serialization,
Hilt, CameraX and WorkManager keep rules are already there).

> **Version bumping:** `versionCode` / `versionName` live in `app/build.gradle.kts`.

---

## 6. Configure it after installing

Nothing is baked into the APK. After install:

1. **Settings → Cloud configuration → Supabase** → project URL + publishable (anon) key →
   **Test connection** → **Save**.
2. **Settings → Cloud configuration → Cloudflare R2** → the URL of the Worker you deployed
   (`https://slimex-media.<you>.workers.dev`) → **Test connection** → **Save**.
   Nothing else goes in this field: the R2 account id, keys and bucket name live in the Worker.
3. **Account** → create an account / sign in.
4. **Settings → Encryption & Recovery** → create a recovery code **before** you accumulate
   media you care about, and store it off-device.

Server side: [`SUPABASE_SETUP.md`](SUPABASE_SETUP.md) and
[`CLOUDFLARE_R2_WORKER_SETUP.md`](CLOUDFLARE_R2_WORKER_SETUP.md).

> **What the app stores on the device:** the Supabase project URL, the publishable key and the
> Worker URL — all in Keystore-backed encrypted preferences. It stores **no** R2 credential, no
> service_role key and no signed URL. Media keys never leave the device except inside the sealed
> recovery envelope you choose to export.

---

## 7. Project layout cheat-sheet

```
SlimeX-Cloud-Storage-Camera/
├── app/
│   ├── build.gradle.kts           module config, R8, signing is NOT committed
│   ├── proguard-rules.pro         keep rules for serialization/Hilt/CameraX/WorkManager
│   └── src/
│       ├── main/java/com/slimex/cloudcamera/     the single app module
│       ├── main/res/                             theme, icons, FileProvider paths
│       ├── main/AndroidManifest.xml
│       └── test/java/com/slimex/cloudcamera/     JVM unit tests
├── gradle/libs.versions.toml      every dependency version in one place
├── supabase/                      schema.sql (metadata only), setup notes
├── worker/                        Cloudflare Worker: src/, test/, wrangler.toml
├── docs/                          setup, testing, limitations, build report
└── README.md
```

---

## 8. Troubleshooting

| Problem | Cause / fix |
| --- | --- |
| App opens but Camera screen says "Camera unavailable" | Another app holds the camera, or the device denies CameraX; close other camera apps and reopen. Emulators without a camera backend cannot capture. |
| "Secure key storage is unavailable" on the Encryption screen | The device ROM has a broken Android Keystore. The app fails closed (uploads blocked) instead of storing keys insecurely. Try a device with a working Keystore. |
| Uploads stay in `WAITING_FOR_CONFIG` | Supabase URL/key not saved. |
| Uploads stay in `WAITING_FOR_AUTH` | Session expired and refresh failed → open **Account** and sign in again. |
| `401` on upload | Session expired and refresh failed → open **Account**, sign in again. |
| `WAITING_FOR_CONFIG` after configuring Supabase | The Cloudflare R2 card has no Worker endpoint saved → add it (Settings → Cloud configuration). |
| "The Worker did not answer, so nothing was saved." | Worker URL wrong or not deployed → `curl https://<worker>/health`; enter only the Worker origin, not `/health`. |
| `403 forbidden` from the Worker | The object key does not start with your Supabase user id — usually rows created against another project. |
| Previews/downloads fail with `object_not_found` | The object was deleted from the bucket, or the upload never finished. Pending captures stay on the device. |
| Gallery tiles show a lock/placeholder | The file was uploaded from another device and its key is not in this key ring → restore the recovery code that device exported. |
| Build error about `kapt` and Kotlin 2.0 | Use the versions in `gradle/libs.versions.toml`; don't bump Kotlin alone. |
