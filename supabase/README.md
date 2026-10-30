# Supabase setup — SlimeX Cloud Camera

The APK ships **without any credentials**. After installing, you connect your own
Supabase project from inside the app (`Settings → Supabase configuration`).

## 1. Create the project

1. Sign in at <https://supabase.com> and create a new project.
2. Wait for the database to finish provisioning.

## 2. Run the SQL (once)

Open **SQL Editor → New query**, then run in this order:

| File | What it creates |
| --- | --- |
| [`schema.sql`](schema.sql) | `media_assets` table, indexes, Row Level Security policies, `media_usage` view |
| [`storage.sql`](storage.sql) | Private `slimex-media` bucket (100 MB limit) and its object policies |

Both scripts are idempotent — re-running them is safe.

## 3. Copy the credentials

In your project: **Project Settings → API**

- **Project URL** → `https://<project-ref>.supabase.co`
- **API Keys → Publishable key** (`sb_publishable_…`) or the legacy **anon** key

Paste both into the app's Supabase configuration screen and tap **Test connection**.
The check verifies, in order:

1. the project endpoint is reachable,
2. the publishable key is accepted by PostgREST,
3. the `slimex-media` bucket is reachable,
4. the `media_assets` table is readable.

## 4. Which key is safe?

| Key | Use in the app |
| --- | --- |
| `sb_publishable_…` | ✅ yes — designed for clients |
| legacy `anon` JWT | ✅ yes — public client key |
| `sb_secret_…` | ❌ never — server-side only |
| `service_role` JWT | ❌ never — bypasses Row Level Security |

The app rejects secret/service keys before they are saved, and credentials are stored
in Keystore-encrypted preferences, never in the APK and never in logs.

## 5. Security notes

- The bucket is **private**; the gallery uses signed URLs that expire after one hour.
- The provided policies grant the `anon` role access to your own bucket/table. That is
  appropriate for a single-owner personal project. For a shared project, enable Supabase
  Auth and switch the policies to `to authenticated` with `device_id = auth.uid()::text`.
- Revoking a key in the Supabase dashboard immediately cuts off any installed copy of the app.
