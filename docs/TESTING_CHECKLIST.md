# Testing checklist — SlimeX Cloud Camera

Target device: **Realme C31, Android 12 (API 31)**, 3/4 GB RAM, 64 GB storage, 13 MP rear +
5 MP front camera, no Google Play Services dependency in this app.

Every scenario below is reproducible by hand. "Expected" describes observable behaviour, not
implementation detail — if a step fails, the app must show a message and keep your media safe.

Setup for the whole list: finish [`ANDROID_STUDIO_SETUP.md`](ANDROID_STUDIO_SETUP.md), run
`supabase/schema.sql`, deploy the Worker from [`CLOUDFLARE_R2_WORKER_SETUP.md`](CLOUDFLARE_R2_WORKER_SETUP.md),
and have the Supabase project URL + publishable key plus the Worker URL ready.

---

## 1. Fresh installation

1. `adb uninstall com.slimex.cloudcamera` (ignore "not installed").
2. `adb install -r app-debug.apk`, launch it.

**Expected:** branded splash → "Privacy first" explainer → permission step. No permission dialog
appears before you press a button. No crash, no blank screen, "Made By Slime Plays" visible.

## 2. First-launch permissions

1. Press **Continue** on the permission step.

**Expected:** the camera permission dialog appears. Deny it → you get an inline explanation and a
"Open settings" path, the app stays usable. Grant it on the second try → the camera preview
appears. Microphone is only requested when you record video with audio enabled; notifications only
from Settings.

## 3. Supabase configuration

1. **Settings → Cloud configuration → Supabase** → paste URL + publishable key.

**Expected:** validation errors appear for a malformed URL; a `service_role`/`sb_secret_` key is
rejected with an explicit message. Saving shows "Saved"; the values survive an app restart.

## 3b. Cloudflare R2 configuration

1. **Settings → Cloud configuration → Cloudflare R2** → paste the Worker URL
   (`https://slimex-media.<you>.workers.dev`), keep the provider name "Cloudflare R2".
2. Tap **Test connection**.

**Expected:** four steps — *Worker endpoint reachable* (required), *Supabase session accepted*
(skipped until you sign in), *Cloudflare R2 reachable* (required), *Storage access verified*.
A Supabase project URL pasted by mistake is refused with "This is the Supabase project URL…".
Saving an endpoint the Worker does not answer is refused: nothing is persisted.
**There is no field for an R2 account id, access key, secret key or bucket name — and there must
not be one.** The card explains that those live in the Worker.

## 4. Connection validation

1. Tap **Test connection** on the Supabase card (then on the R2 card).

**Expected:** per-check results with a pill per step. Wrong key → 401 shown as "key rejected";
airplane mode → "no connection"; Worker down → "The Worker did not answer…". Nothing is saved
until you press Save.

## 5. Login and logout

1. **Account → Create account** with a new email/password.
2. If confirm-email is on: confirm via the mail, then sign in.
3. Sign out.

**Expected:** signed-in state shows the email; sign-out returns to the form and the cloud gallery
shows its "sign in" empty state. Wrong password → readable error, no crash, no repeated dialogs.

## 6. Photo capture

1. Camera tab → shutter.

**Expected:** shutter sound/feedback, thumbnail shortcut updates, queue gains one photo. The file
appears only under app-private storage (`adb shell run-as com.slimex.cloudcamera ls files/pending
/photos`), **never** in DCIM/Pictures. Quality: balanced JPEG (~92 %), no purple UI on the camera
screen.

## 7. Video recording

1. Switch to video, tap record, wait ~10 s, tap stop.

**Expected:** red timer counts up, stopping finalises an MP4, queue gains one video. On the C31 the
recorder prefers 1080p and silently falls back to 720p if unsupported. If microphone permission is
denied, the clip still records (silently) and the UI says so.

## 8. Offline capture

1. Enable airplane mode (or turn Wi-Fi/data off).
2. Capture a photo.

**Expected:** the photo is saved privately and queued as `WAITING_FOR_NETWORK`; nothing is lost.
With **Settings → Zero local storage** ON, capture is blocked instead and the message explains why.

## 9. Automatic upload after internet returns

1. Keep the app in the background with a queued item.
2. Re-enable the network.

**Expected:** the item moves `WAITING_FOR_NETWORK → ENCRYPTING → UPLOADING → VERIFYING → UPLOADED`
within ~15 s (WorkManager wakes on the connectivity constraint). The local file is deleted **only
after** the UPLOADED state is reached.

## 10. Upload retry

1. Start an upload, then kill the connection mid-transfer.
2. Restore the connection.

**Expected:** the task shows `FAILED` with an error, attempts increment, and **Retry** (or the
automatic backoff/worker) completes it. No duplicate rows appear in `media_files`.

## 11. Cloud gallery

1. Open **Cloud**.

**Expected:** real rows from your bucket, tabs All/Photos/Videos, search by name, four sort orders,
refresh. Thumbnails are decrypted sidecars. Empty project → an empty state, never fake tiles.

## 12. Photo viewing

1. Tap a photo tile.

**Expected:** full-screen viewer with details (type, size, upload date). The file is downloaded and
decrypted into `cache/viewer/` only — check that no copy appears in shared storage.

## 13. Video playback

1. Open a video tile.

**Expected:** a real `VideoView` player with play/pause and its own controls. Unsupported codecs
show an error instead of a frozen screen.

## 14. Direct WhatsApp sharing

1. Open a photo or video → **Share** → choose WhatsApp.
2. Send it to a contact (or "Message yourself").

**Expected:** the receiving side gets a playable/viewable file with the correct MIME type. The
`content://` URI (`com.slimex.cloudcamera.fileprovider`) grants read access only. The temporary copy
in `cache/share/` still exists right after sharing and disappears after ~15 minutes
(`ShareCleanupWorker`). No public link is created anywhere.

## 15. Temporary cache cleanup

1. Share a file, wait 15+ minutes (or force-stop and reopen later).
2. **Storage → Clear caches**.

**Expected:** `cache/share`, `cache/viewer`, `cache/thumbs` shrink; **pending** files and anything
not yet verified are untouched (counts must not change).

## 16. Cloud deletion

1. Open a tile → **Delete** → confirm.

**Expected:** the bucket object and the row disappear, cached copies are removed, the gallery
refreshes. With the network off, deletion fails with a clear message and the row stays — nothing is
half-deleted.

## 17. Encryption recovery

1. **Settings → Encryption & Recovery** → note the "Keys stored on device" count.
2. Create a recovery code with an 8+ character passphrase; copy it into a text file on your PC.
3. Restore it immediately ("Restore keys") and open an older cloud photo.

**Expected:** restore reports the number of keys and the photo opens. A wrong passphrase gives
"Could not open the recovery code", a mangled code gives "damaged or incomplete". The warning about
permanent loss is visible on the screen.

4. Sign out, clear app data (`adb shell pm clear com.slimex.cloudcamera`), sign in again, restore
   the same code → the library is readable again with the same keys.

## 18. App restart

1. Queue an upload, force-stop the app (`adb shell am force-stop com.slimex.cloudcamera`), reopen.

**Expected:** the queue and its statuses survive (DataStore-backed), the upload resumes, no
duplicate tasks, no lost captures.

## 19. Device restart

1. Queue an upload while offline, reboot the phone, unlock, wait for connectivity.

**Expected:** WorkManager resumes the queue (persistent work with a network constraint) and the
upload completes without opening the app first.

## 20. Invalid credentials

1. Sign out, enter a wrong password; then rotate the publishable key in Supabase and try to upload.

**Expected:** a readable error ("Invalid email or password" / "key rejected"), the queue moves to
`WAITING_FOR_AUTH` and auto-resumes after a correct sign-in. Nothing is deleted, nothing loops
forever, no raw JSON or stack trace is shown.

---

## Part 4 — end-to-end cloud list

Each item is one manual test. "Verified usage" means the number came from R2 through the Worker;
"recorded" means it came from `media_files` in Supabase. The app must always label which is which.

| # | Test | Steps | Expected |
| --- | --- | --- | --- |
| P4-1 | Supabase connection | Save URL + publishable key, Test connection | all required steps pass; host shown; survives restart |
| P4-2 | Worker auth | Sign in, R2 card → Test connection | "Supabase session accepted" passes; signed out → skipped with a hint |
| P4-3 | R2 connection | `/health` via the card (or `curl`) | "Cloudflare R2 reachable" passes; break the bucket name in the Worker → step fails with a clear message |
| P4-4 | Photo upload | Capture a photo with Wi-Fi on | queue: encrypting → uploading → verifying → uploaded; row in `media_files`; object in R2 with `.enc` suffix |
| P4-5 | Video upload | Record ~15 s, wait for upload | progress advances monotonically; object size equals the ciphertext size; no plaintext in R2 |
| P4-6 | Encrypted download | Gallery → View a photo uploaded from another install of the app (same account, recovery code restored) | file decrypts from app-private cache; nothing is written to Pictures/DCIM |
| P4-7 | Gallery sync | Pull to refresh the cloud gallery | rows come from Supabase; thumbnails load through the Worker; no public URLs |
| P4-8 | WhatsApp share | Tap Share on a cloud item → WhatsApp | Sharesheet opens with a `content://…/cache/share/…` URI; WhatsApp receives a playable file; the shared copy is cleaned later, not immediately |
| P4-9 | Upload retry | Enable airplane mode mid-upload, then disable | task keeps its encrypted temp file, retries with WorkManager, resumes without re-capturing |
| P4-10 | Delete | Delete a cloud item | object gone from R2 (`object_not_found` afterwards), row gone from Supabase, local cache cleaned, pending uploads untouched |
| P4-11 | Invalid credentials | Rotate `SUPABASE_ANON_KEY` in the Worker only | Worker returns 401 → app says "session expired", queue parks in `WAITING_FOR_AUTH`, nothing deleted |
| P4-12 | Network loss | Turn off Wi-Fi/data for 2 minutes | status card shows "Unreachable"; uploads pause; no crash, no data loss |
| P4-13 | Unauthorized access | `curl` the Worker without a token, with a forged token, and with another user's key | 401 / 401 / 403 — never a signed URL, never another user's bytes |
| P4-14 | No fabricated quota | Open Settings → Cloud configuration → status card | "Free quota: Not reported by provider"; verified usage shown only when the Worker answered |

## Additional security checks

| # | Check | Expected |
| --- | --- | --- |
| S1 | `adb shell run-as com.slimex.cloudcamera ls files/keys` | `keyring.v1` exists and is not plaintext JSON |
| S2 | Download an object from the Cloudflare R2 dashboard | It is ciphertext (not a JPEG/MP4) |
| S2b | `adb shell run-as com.slimex.cloudcamera cat shared_prefs/*.xml` | No R2 access key, secret key, account id or bucket name anywhere |
| S2c | grep the APK (`unzip -l`, `strings app-debug.apk`) for `r2.cloudflarestorage.com`, `R2_SECRET`, `service_role` | No matches — the app only knows the Worker URL |
| S3 | `select * from media_files limit 1` | No key/nonce columns exist |
| S4 | `adb logcat | grep -i -E "password|apikey|bearer|eyJ"` while using the app (debug build) | No secrets printed; release builds print nothing at all |
| S5 | Try to open `content://com.slimex.cloudcamera.fileprovider/files/pending/...` from another app | Denied — only `cache/share` is exposed |
| S6 | Sign in as a second account | First account's rows and objects are invisible; the Worker answers `403 forbidden` for the first user's keys |
| S6b | `curl -X GET "<worker>/v1/usage" -H "authorization: Bearer <token of user B>"` | Only user B's objects are counted |
| S7 | Android Settings → Apps → SlimeX → Storage | No media in shared storage; app data only |
| S8 | Toggle the "Reset keys" action after exporting a code | Media becomes unreadable until the code is restored |

---

## What "pass" means

- No crash, no ANR, no infinite spinner anywhere.
- Every failure path shows a human message and leaves your media recoverable.
- Photos and videos are only ever deleted after a verified cloud copy exists.
- The camera screen is pure black; the rest of the app is black + purple; "Made By Slime Plays"
  appears on the splash, About and settings footer.
