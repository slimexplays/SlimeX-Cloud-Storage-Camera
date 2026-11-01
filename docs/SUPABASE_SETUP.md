# Supabase setup — SlimeX Cloud Camera

The APK contains **no credentials**. You connect your own Supabase project from inside the app
after installing it. This guide creates that project correctly the first time.

---

## 1. Create the project

1. Go to <https://supabase.com> → **New project**.
2. Pick an organisation, a name (e.g. `slimex-cloud-camera`), a strong database password and the
   region closest to you (for India: `ap-south-1` / Mumbai).
3. Wait until provisioning finishes (2–3 minutes).

Copy these two values from **Project Settings → API**:

| Value | Used for | Where it goes |
| --- | --- | --- |
| **Project URL** (`https://<ref>.supabase.co`) | every REST call | Settings → Cloud configuration → Supabase |
| **Publishable key** (new) or **anon public** key (legacy) | `apikey` / bearer header | Settings → Cloud configuration → Supabase |
| **Worker endpoint** (`https://slimex-media.<you>.workers.dev`) | media upload/download | Settings → Cloud configuration → Cloudflare R2 |

> **Never use the `service_role` / secret key in this app.** It bypasses RLS. The app rejects
> `service_role` JWTs and `sb_secret_…` keys during validation, and refuses to save them.

---

## 2. Run the SQL script

Open **SQL Editor → New query**, paste [`../supabase/schema.sql`](../supabase/schema.sql), press
**Run**. The script is **idempotent** — running it twice is safe.

| File | Creates |
| --- | --- |
| [`../supabase/schema.sql`](../supabase/schema.sql) | `profiles`, `media_files`, `upload_jobs`, constraints, indexes, RLS policies, `media_usage` view, `handle_new_user` trigger, and the Part 3 migration that removes any key columns |

> **Part 4 — there is no Supabase Storage any more.** Photos and videos live in a private Cloudflare
> R2 bucket and reach the device only through your Cloudflare Worker (see
> [`CLOUDFLARE_R2_WORKER_SETUP.md`](CLOUDFLARE_R2_WORKER_SETUP.md)). The old `storage.sql` has been
> removed; if you ran it in Part 2 you can delete the empty `slimex-private-media` bucket from
> **Storage → Buckets**. Supabase keeps authentication, `profiles`, media **metadata**, upload status
> and the storage-provider reference — never media bytes and never any R2 credential.

### What the schema enforces

- `storage_path` is the **R2 object key**, and must match
  `{user_id}/{year}/{month}/{unique_file_id}.enc` (`media_files_path_owner`,
  `media_files_path_layout`). It is never a URL. The Worker applies the same rule before it signs a
  URL, so metadata and bucket ownership can never disagree.
- Only ciphertext may be recorded (`media_files_encrypted_only`).
- **No encryption key columns exist.** Part 3 removed `encryption_key`, `encryption_nonce`,
  `thumbnail_key`, `thumbnail_nonce`; the migration drops them if an older build added them, and a
  `DO` block raises an error if they are ever re-introduced.
- RLS is **enabled and forced** on `profiles`, `media_files`, `upload_jobs`; every policy compares
  `user_id` to `auth.uid()`.
- `upload_jobs.status` is a constrained enum mirroring the app's queue states, `retry_count` is
  bounded.

### What replaces the storage policies

Object ownership is enforced **outside** the database, in the Worker:

- every object key must start with the caller's `{userId}/` (verified against Supabase, not against
  anything the client sends), and
- the app never receives an R2 credential, so it cannot reach an object the Worker did not sign for.

Per-object upload limit is 500 MB, enforced by the Worker (`MAX_UPLOAD_BYTES`) and by R2. Long 1080p
clips on a Realme C31 stay far below that.

---

## 3. Verify the project

In **Table editor** you should see `profiles`, `media_files` and `upload_jobs` with RLS enabled —
`media_files` stays empty until the first upload succeeds, because a row is only written after the
Worker confirms the object exists in R2.

---

## 4. Auth settings

**Authentication → Providers → Email**:

- **Enable email provider** — the app signs in with email + password.
- If **Confirm email** is ON, the app tells you to confirm before signing in. For quick testing,
  turn it OFF (Authentication → Providers → Email → *Confirm email*), or create a user manually
  under **Authentication → Users**.
- **Authentication → URL configuration** does not need changes (the app uses no deep links).
- Optional: disable sign-ups once your own account exists (Authentication → Settings → *Allow new
  users*).

The `on_auth_user_created` trigger mirrors every new auth user into `public.profiles`
(`display_name` defaults to the email local part).

---

## 5. Configure the app

1. Launch SlimeX Cloud Camera.
2. **Settings → Cloud configuration** (the card titled **Supabase**).
3. Paste the Project URL and the publishable/anon key.
4. Tap **Test connection** — it checks DNS/TLS reachability, the project endpoint, the key and the
   `media_files` table, and shows a per-step result.
5. Tap **Save**. Both values are written to Keystore-backed encrypted preferences; nothing is logged.
6. Configure the media backend in the **Cloudflare R2** card — see
   [`CLOUDFLARE_R2_WORKER_SETUP.md`](CLOUDFLARE_R2_WORKER_SETUP.md).
7. **Account → Create account** (or sign in), then capture something and watch **Uploads**.

---

## 6. Verifying end to end

| Check | Where |
| --- | --- |
| Row appears per upload | **Table editor → media_files** (it should contain no key columns) |
| Object path looks like `{uid}/{yyyy}/{MM}/{uuid}.enc` | Cloudflare dashboard → R2 → your bucket |
| Object is unreadable as an image | download it from R2 — it is ciphertext |
| Cross-user isolation | sign in as a second user; the first user's rows/objects are invisible |
| Keys stay local | `select * from media_files` — no `encryption_key` column exists |
| Supabase holds no media | **Storage → Buckets** is empty / unused |

---

## 7. Operational notes

- **Free tier** pauses projects after a week of inactivity; the app will surface `5xx` failures as
  "Supabase unavailable" and retry later.
- **Rotating the publishable key** only requires re-entering it in the app; media keys are
  unaffected because they never leave the device.
- **Deleting a user** cascades to `profiles`, `media_files` and `upload_jobs`, but *not* to the R2
  objects — delete the `{userId}/` prefix from the bucket (Cloudflare dashboard or
  `wrangler r2 object delete`) if you remove an account.
- **Supabase is metadata only.** It never stores a byte of media and never stores an R2 credential.
- **Backups**: Supabase backs up the database, not your decrypted media. Your recovery code is the
  only way back into an old library.
