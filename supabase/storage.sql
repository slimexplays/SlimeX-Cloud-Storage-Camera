-- ============================================================================
--  SlimeX Cloud Camera — Storage setup (Part 2)
--  Run AFTER schema.sql. Creates the PRIVATE bucket and per-user object policies.
--
--  Bucket  : slimex-private-media       (public = false, never flip this)
--  Layout  : {user_id}/{year}/{month}/{unique_file_id}.enc
--  Sidecar : {user_id}/{year}/{month}/{unique_file_id}.thumb.enc
--  MIME    : application/octet-stream (the app encrypts before uploading)
-- ============================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'slimex-private-media',
    'slimex-private-media',
    false,                                  -- private: no public URL can read it
    524288000,                              -- 500 MB per object (5 min of 4K video)
    array['application/octet-stream']::text[]
)
on conflict (id) do update
set public             = false,
    file_size_limit    = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- ---------------------------------------------------------------------------
--  The bucket must be private. If an older setup left it public, this line
--  makes it private again.
-- ---------------------------------------------------------------------------
update storage.buckets set public = false where id = 'slimex-private-media';

-- ---------------------------------------------------------------------------
--  Object policies.
--
--  `storage.foldername(name)[1]` is the first path segment, which is the
--  user's uuid — exactly the layout the app writes. A user therefore can only
--  touch objects under their own folder.
-- ---------------------------------------------------------------------------

-- Read (download / list) — owner only.
drop policy if exists "slimex_media_select_own" on storage.objects;
create policy "slimex_media_select_own" on storage.objects
    for select to authenticated
    using (
        bucket_id = 'slimex-private-media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- Upload — owner only, and the object must sit inside their folder.
drop policy if exists "slimex_media_insert_own" on storage.objects;
create policy "slimex_media_insert_own" on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'slimex-private-media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- Overwrite (upsert of a retried upload) — owner only.
drop policy if exists "slimex_media_update_own" on storage.objects;
create policy "slimex_media_update_own" on storage.objects
    for update to authenticated
    using (
        bucket_id = 'slimex-private-media'
        and (storage.foldername(name))[1] = auth.uid()::text
    )
    with check (
        bucket_id = 'slimex-private-media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- Delete — owner only.
drop policy if exists "slimex_media_delete_own" on storage.objects;
create policy "slimex_media_delete_own" on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'slimex-private-media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- The anon role gets nothing: the app always acts as a signed-in user.
drop policy if exists "slimex_media_anon_none" on storage.objects;

-- ---------------------------------------------------------------------------
--  Optional: keep the bucket tidy when a media row is deleted.
--  (The app deletes the objects itself; this is a safety net if you ever delete
--  rows from the SQL editor.)
-- ---------------------------------------------------------------------------
-- select storage.delete_object('slimex-private-media', storage_path)
--   from public.media_files where ...

-- ---------------------------------------------------------------------------
--  Verification queries (run in the Supabase SQL editor):
--
--    select id, public, file_size_limit from storage.buckets
--     where id = 'slimex-private-media';          -- public must be false
--
--    select name from pg_policies
--     where schemaname = 'storage' and tablename = 'objects'
--       and policyname like 'slimex_media_%';     -- four policies
--
--  Part 1 shipped the `slimex-media` bucket. It is not used by Part 2 or later;
--  if it holds nothing you want to keep, delete it in the dashboard.
-- ---------------------------------------------------------------------------
