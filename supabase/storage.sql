-- =============================================================================
--  SlimeX Cloud Camera — storage bucket + policies
--  Run after schema.sql in the Supabase SQL editor.
-- =============================================================================
--
--  The bucket is PRIVATE on purpose: no public URLs are ever created.
--  The app reads media through short-lived signed URLs (1 hour) and uploads
--  with the publishable / anon key.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Private bucket (100 MB per file, images + video only)
-- -----------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'slimex-media',
    'slimex-media',
    false,
    104857600,
    array['image/jpeg', 'image/jpg', 'image/png', 'video/mp4', 'video/quicktime']
)
on conflict (id) do update
set public             = false,
    file_size_limit    = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- -----------------------------------------------------------------------------
-- Storage object policies (anon role, scoped to this bucket)
-- -----------------------------------------------------------------------------
drop policy if exists "slimex_objects_read" on storage.objects;
create policy "slimex_objects_read"
    on storage.objects
    for select
    to anon
    using (bucket_id = 'slimex-media');

drop policy if exists "slimex_objects_insert" on storage.objects;
create policy "slimex_objects_insert"
    on storage.objects
    for insert
    to anon
    with check (bucket_id = 'slimex-media');

drop policy if exists "slimex_objects_update" on storage.objects;
create policy "slimex_objects_update"
    on storage.objects
    for update
    to anon
    using (bucket_id = 'slimex-media')
    with check (bucket_id = 'slimex-media');

drop policy if exists "slimex_objects_delete" on storage.objects;
create policy "slimex_objects_delete"
    on storage.objects
    for delete
    to anon
    using (bucket_id = 'slimex-media');

-- -----------------------------------------------------------------------------
-- Verification (run manually, expect one row)
-- -----------------------------------------------------------------------------
-- select id, public, file_size_limit, allowed_mime_types
-- from storage.buckets where id = 'slimex-media';
