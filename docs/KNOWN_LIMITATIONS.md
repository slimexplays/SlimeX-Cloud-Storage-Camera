# Known limitations and compatibility notes

Honest list of what this build does **not** do, so nothing in the spec is over-claimed.

---

## Build / distribution

1. **No APK is committed to this repository.** Binaries do not belong in Git. Build it with
   `./gradlew :app:assembleDebug`, or download it from the GitHub Actions workflow
   (`.github/workflows/build-apk.yml`), which publishes `slimex-cloud-camera-debug-apk` and a
   signed `slimex-cloud-camera-release-signed` artifact.
2. **The build runs on GitHub Actions, not in the authoring sandbox** (no JDK/Android SDK there).
   It compiles, passes the unit tests, and produces a debug APK plus a signed release APK/AAB; the
   recorded run is in [`BUILD_VERIFICATION.md`](BUILD_VERIFICATION.md). That first real compile
   found and fixed ten defects (wrong parameter names, a missing `override`, an R8 missing-class
   error, a failing unit test …), which is exactly why no build was claimed before it ran.
3. **No keystore or password is committed (by design).** Release signing comes from the injected
   `android.injected.signing.*` properties; in CI they come from four repository secrets. When the
   secrets are absent the workflow signs with a throwaway key generated inside the runner — fine for
   proving the pipeline, not for distribution, and it cannot upgrade an install signed with your
   real key.

## Cryptography

4. **Keys are device-bound.** `media_files` intentionally has no key columns; the per-media keys
   live only in the local key ring, wrapped by an Android Keystore key. Consequences:
   - a second device can only read the library after importing a recovery code exported on the
     first device;
   - clearing app data (`pm clear`, uninstall, "clear storage") destroys the key ring;
   - the app cannot decrypt anything you uploaded without that key ring.
5. **Losing both the recovery code and its passphrase is unrecoverable.** This is the intended
   trade-off: there is no server-side key copy, so a compromised Supabase project cannot leak your
   media.
6. Recovery codes use PBKDF2-HMAC-SHA256 with 210 000 iterations. A very long, low-entropy
   passphrase is still guessable offline if the code itself leaks — use a passphrase with real
   entropy (a generated passphrase or diceware), not `password1`.
7. The Keystore on a few older OEM ROMs is unreliable. The app **fails closed** (uploads blocked
   with an explanation) instead of storing keys somewhere weaker. Realme C31 / Android 12 has a
   working Keystore.
8. Files are encrypted with AES-256-GCM but **not** with a per-file AAD binding the ciphertext to
   its media id. Integrity of the upload is instead verified by size, listing and a SHA-256 of the
   ciphertext (and of the plaintext after decrypt); this is sufficient to detect corruption or a
   swapped file, not to prevent an attacker with write access to your bucket from deleting objects.

### Migration note for early builds

26. Projects configured with a **Part 2 build** kept the per-file keys in `media_files`. Part 3
    removes those columns (that is a requirement of the spec), so media uploaded by that older
    build can no longer be decrypted after you run the updated `schema.sql` — the keys are gone
    from the cloud and were never in the local key ring. If you have such a project and care about
    that media, export/record the keys **before** running the migration, or keep that bucket
    read-only. Media uploaded by this build is unaffected.

## Camera

9. Photos are CameraX JPEGs (balanced ~92 % or high 100 %). No RAW/DNG, no computational HDR, no
   portrait mode, **no claim of matching Realme's stock processing**.
10. Video prefers 1080p and falls back to 720p/SD. Frame rate and bitrate are whatever CameraX
    negotiates on the device — there is no manual bitrate control.
11. Emulators without a working camera backend cannot capture; a physical device is required.
12. Zoom range, tap-to-focus and flash availability depend on the hardware; unsupported controls
    are hidden rather than shown as dead buttons.

## Media and queue

13. Upload verification re-downloads objects **up to 64 MB** to hash them; larger videos are
    verified by existence + size + listing only (so a 500 MB clip does not double your traffic).
14. The R2 bucket/Worker caps a single object at 500 MB (`MAX_UPLOAD_BYTES`, hard ceiling in
    `worker/src/validation.js`). Raise the var if you record longer clips.
15. Cache clean-up never deletes pending or unverified files; only the upload pipeline deletes a
    local capture, and only after `UPLOADED`.
16. WorkManager can be delayed by Doze/App Standby on aggressive OEM ROMs; the queue resumes on
    the next app open or network change even when the background job was postponed.
17. Sharing hands bytes to the Sharesheet. **The receiving app keeps its own copy** (WhatsApp,
    Telegram, Files, …). The 15-minute cleanup only removes *this* app's temporary copy.

## Cloud / Supabase

18. The bucket name is **not** in the app at all. It is the Worker secret `R2_BUCKET`, so it can be
    renamed by updating the Worker only.
19. Free-tier Supabase projects pause after inactivity; the app reports that as "Supabase
    unavailable" and retries.
20. Deleting a user cascades rows but not R2 objects — delete the `{userId}/` prefix manually.
21. The app uses the REST endpoints directly (no Supabase SDK) because credentials are entered at
    runtime. Endpoint paths follow the `auth/v1` and `rest/v1` APIs as of 2024/2025; a future
    breaking API change would need a code update.
22. There is no batch/multi-select delete, no albums, no favourites — none were requested.

## Cloudflare R2 / Worker (Part 4)

27. **The Worker was not deployed from this environment and no live R2 bucket was contacted.** What
    was verified locally with `cd worker && npm test` (Node 22): route behaviour against a mocked
    Supabase + R2 (auth, ownership, size/content-type limits, usage scoping, stat, delete, structured
    errors) and SigV4 parity — the presigned PUT URL and the header-signed list request produce
    **byte-identical** signatures to `aws4fetch` 1.0.20. Anything involving real Cloudflare
    endpoints must be confirmed on your account.
28. **Presigned URLs contain the bucket name and the R2 access key *id*** — that is inherent to S3
    presigning and is not a secret leak (the secret key is never included). The app never displays,
    logs or persists these URLs; they expire in 5 minutes.
29. **Rate limiting is per-isolate** (in-memory). It blunts a single abusive client but is not a
    global guarantee; add a Cloudflare WAF rate-limiting rule or a Durable Object for a hard limit.
30. **Usage listing is capped at 10 000 objects per call** (10 pages × 1000). Beyond that the card
    reports `truncated: true` and shows the subtotal rather than an invented number.
31. **R2 publishes no per-bucket quota over the S3 API**, so the app shows "Not reported by
    provider" for free space instead of estimating it.
32. **Uploads are single-PUT**, so a 500 MB cap per object; there is no multipart resumable upload.
    An interrupted upload restarts from zero (the encrypted temp file is kept, so no re-capture is
    needed).
33. The Worker uses **Supabase `/auth/v1/user`** for JWT verification (one round trip, cached ~60 s
    per isolate) rather than local JWKS validation. That is the most tamper-proof option for a small
    Worker, at the cost of a little latency on the first call.

## UI / UX

23. The camera screen is deliberately pure black with no purple decoration (per spec); the rest of
    the app uses the black + purple Material 3 theme.
24. Localisation is English only.
25. Dark theme only — the app is designed black; a light theme is not provided.

## Device support

| Tested target | Notes |
| --- | --- |
| Realme C31, Android 12 (API 31) | Primary target; CameraX 1.4.1 + `COMPATIBLE` preview mode keeps the preview fluid on the entry-level SoC |
| Android 8.0 (API 26) minimum | Keystore, EncryptedSharedPreferences, WorkManager and CameraX features used here all exist at API 26 |
| Android 13+ | Adds the notification permission, requested contextually from Settings |
| Android 14/15 | Cleartext disabled, no storage permissions requested, `targetSdk 35` |
| Tablets / foldables | Layout works but is not specifically tuned |
