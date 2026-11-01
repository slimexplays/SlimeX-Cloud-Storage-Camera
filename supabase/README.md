# Supabase setup — SlimeX Cloud Camera

The APK ships **without any credentials**. After installing, you connect your own Supabase
project from inside the app (`Settings → Supabase configuration`) and sign in from
`Account`.

## 1. Create the project

1. Sign in at <https://supabase.com> and create a new project.
2. Wait for the database to finish provisioning.

> **Part 4:** Supabase stores authentication and **metadata only**. Photos and videos live in a
> private Cloudflare R2 bucket and are reached through your own Cloudflare Worker. There is no
> Supabase Storage bucket to create, and the `storage.sql` script from Part 2 is gone.

## 2. Run the SQL (once, in this order)

Open **SQL Editor → New query**, paste a file, run it, then continue with the next one:

| File | What it creates |
| --- | --- |
| [`schema.sql`](schema.sql) | `profiles`, `media_files`, `upload_jobs`, constraints, indexes, RLS policies, `media_usage` view, signup trigger |

The script is idempotent — re-running it is safe. If you ran the old `storage.sql` from Part 2, you
may delete the empty Supabase Storage bucket; the app never reads or writes it any more.

### What the schema guarantees

- `media_files.storage_path` must match `{user_id}/{year}/{month}/{unique_file_id}.enc`
  (`media_files_path_owner` and `media_files_path_layout` checks). It is the **R2 object key**, the
  same string the Worker validates before signing a URL — that is what keeps the database and the
  bucket in agreement about who owns which object.
- Every row is ciphertext (`media_files_encrypted_only`).
- **No key material is stored in the database.** Part 3 removed `encryption_key`,
  `encryption_nonce`, `thumbnail_key` and `thumbnail_nonce`; the migration at the end of
  `schema.sql` drops them from older projects and raises an error if anything tries to add them
  back. Keys live only in the app's Keystore-protected key ring on the device.
- Every table has RLS **enabled and forced**; every policy compares `user_id` to `auth.uid()`.
- `upload_jobs.status` is constrained to the queue's real states, `retry_count` is bounded.

## 3. Auth settings

In **Authentication → Providers → Email**:

- **Email + password** must be enabled (the app signs in with email/password).
- If **Confirm email** is on, the app tells you to confirm the address before signing in.
- Optional: turn off sign-ups once your account exists.

The `on_auth_user_created` trigger mirrors every new user into `public.profiles`.

## 4. Copy the credentials

In your project: **Project Settings → API**

- **Project URL** → `https://<project-ref>.supabase.co`
- **API Keys → Publishable key** (`sb_publishable_…`) or the legacy **anon** key

Paste both into the app's Supabase configuration screen and tap **Test connection**.
The check verifies, in order:

1. the project endpoint is reachable,
2. the publishable key is accepted by PostgREST,
3. the `slimex-private-media` bucket exists (a 401/403 here is expected: the bucket is
   private and needs a signed-in user),
4. the `media_files` table exists and is protected by RLS.

## 5. Which key is safe?

| Key | Use in the app |
| --- | --- |
| `sb_publishable_…` | ✅ yes — designed for clients |
| legacy `anon` JWT | ✅ yes — public client key |
| `sb_secret_…` | ❌ never — server-side only |
| `service_role` JWT | ❌ never — bypasses Row Level Security |

The app rejects secret/service keys before they are saved (including a JWT
`role: service_role` claim check), and credentials are stored in Keystore-encrypted
preferences — never in the APK, never in logs.

## 6. Security notes

- The bucket is **private**. There are no signed "anyone with the link" URLs; the app fetches
  objects with the signed-in user's access token and decrypts them locally.
- File bytes are AES-256-GCM ciphertext. The per-file key/nonce are columns of `media_files`,
  readable only by the owner through RLS — this is what makes a reinstall able to decrypt the
  library. Someone who only has your publishable key can read nothing at all.
- Storage policies use `(storage.foldername(name))[1] = auth.uid()::text`, so a user can only
  read, upload, overwrite or delete objects inside their own folder.
- Revoking the key (or deleting the user) in the Supabase dashboard immediately cuts off any
  installed copy of the app.
- Deleted app data removes the Keystore keys for the stored session/configuration. Cloud
  objects and their metadata stay in your project — delete them from the gallery first if
  that is not what you want.
