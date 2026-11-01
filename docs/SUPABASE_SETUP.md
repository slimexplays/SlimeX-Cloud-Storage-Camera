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
| **Project URL** (`https://<ref>.supabase.co`) | every REST call | Settings → Supabase configuration |
| **Publishable key** (new) or **anon public** key (legacy) | `apikey` / bearer header | Settings → Supabase configuration |

> **Never use the `service_role` / secret key in this app.** It bypasses RLS. The app rejects
> `service_role` JWTs and `sb_secret_…` keys during validation, and refuses to save them.

---

## 2. Run the SQL scripts (in this order)

Open **SQL Editor → New query**, paste a whole file, press **Run**, then do the next one.

| Order | File | Creates |
| --- | --- | --- |
| 1 | [`../supabase/schema.sql`](../supabase/schema.sql) | `profiles`, `media_files`, `upload_jobs`, constraints, indexes, RLS policies, `media_usage` view, `handle_new_user` trigger, and the Part 3 migration that removes any key columns |
| 2 | [`../supabase/storage.sql`](../supabase/storage.sql) | the private `slimex-private-media` bucket (500 MB per object, `application/octet-stream`) and its four per-user object policies |

Both scripts are **idempotent** — running them twice is safe.

### What the schema enforces

- `storage_path` must match `{user_id}/{year}/{month}/{unique_file_id}.enc`
  (`media_files_path_owner`, `media_files_path_layout`).
- Only ciphertext may be recorded (`media_files_encrypted_only`).
- **No encryption key columns exist.** Part 3 removed `encryption_key`, `encryption_nonce`,
  `thumbnail_key`, `thumbnail_nonce`; the migration drops them if an older build added them, and a
  `DO` block raises an error if they are ever re-introduced.
- RLS is **enabled and forced** on `profiles`, `media_files`, `upload_jobs`; every policy compares
  `user_id` to `auth.uid()`.
- `upload_jobs.status` is a constrained enum mirroring the app's queue states, `retry_count` is
  bounded.

### What the storage policies enforce

Four policies on `storage.objects` (`select`, `insert`, `update`, `delete`) all require:

```sql
bucket_id = 'slimex-private-media'
and (storage.foldername(name))[1] = auth.uid()::text
```

So a signed-in user can only read or write objects under their own `{user_id}/` prefix. There is
no anonymous access and no public URL.

---

## 3. Verify the bucket

**Storage → Buckets** should show `slimex-private-media` with **Public: off**.

If you ever flip it public, the app still uploads only ciphertext — but do not: the whole design
assumes the objects are unreachable without an authenticated, owner-scoped session.

Per-object limit is 500 MB (`storage.sql`). Long 1080p clips on a Realme C31 stay far below that;
raise the limit in `storage.sql` if you need longer recordings.

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
2. **Settings → Supabase configuration**.
3. Paste the Project URL and the publishable/anon key.
4. Tap **Test connection** — it checks DNS/TLS reachability, `/auth/v1/health`, the bucket and the
   `media_files` table, and shows a per-step result.
5. Tap **Save**. Values are written to Keystore-backed encrypted preferences; nothing is logged.
6. **Account → Create account** (or sign in), then capture something and watch **Uploads**.

---

## 6. Verifying end to end

| Check | Where |
| --- | --- |
| Row appears per upload | **Table editor → media_files** (it should contain no key columns) |
| Object path looks like `{uid}/{yyyy}/{MM}/{uuid}.enc` | **Storage → slimex-private-media** |
| Object is unreadable as an image | download it — it is ciphertext |
| Cross-user isolation | sign in as a second user; the first user's rows/objects are invisible |
| Keys stay local | `select * from media_files` — no `encryption_key` column exists |

---

## 7. Operational notes

- **Free tier** pauses projects after a week of inactivity; the app will surface `5xx` failures as
  "Supabase unavailable" and retry later.
- **Rotating the publishable key** only requires re-entering it in the app; media keys are
  unaffected because they never leave the device.
- **Deleting a user** cascades to `profiles`, `media_files` and `upload_jobs`, but *not* to storage
  objects — remove those from the Storage tab if you delete an account.
- **Backups**: Supabase backs up the database, not your decrypted media. Your recovery code is the
  only way back into an old library.
