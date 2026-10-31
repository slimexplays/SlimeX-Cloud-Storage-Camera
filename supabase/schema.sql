-- ============================================================================
--  SlimeX Cloud Camera — Postgres schema (Part 2)
--  Run this once in the Supabase SQL editor of YOUR project, then run
--  storage.sql. Nothing here contains credentials.
--
--  Tables:
--    public.profiles      one row per auth user (mirrors auth.users)
--    public.media_files   one row per uploaded object (metadata + AES key material)
--    public.upload_jobs   upload queue bookkeeping (status + retry count)
--
--  Security model:
--    * RLS is enabled on every table and every policy is scoped to auth.uid().
--    * The bucket is private; media only ever reaches the device with the
--      owner's access token (see storage.sql).
--    * File bytes are AES-256-GCM ciphertext. The per-file key/nonce live in the
--      row so the owner can decrypt the file again after reinstalling the app —
--      they are readable only by that owner (RLS), never by the anon key alone.
-- ============================================================================

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- profiles ---
create table if not exists public.profiles (
    id            uuid primary key references auth.users (id) on delete cascade,
    email         text,
    device_id     text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint profiles_email_len check (email is null or char_length(email) <= 320)
);

comment on table public.profiles is
    'One row per Supabase auth user. Created automatically on signup.';

-- --------------------------------------------------------------- media_files --
create table if not exists public.media_files (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null references auth.users (id) on delete cascade,
    storage_path       text not null,
    file_name          text not null,
    mime_type          text,
    file_size          bigint not null default 0,
    media_type         text not null,
    encrypted          boolean not null default true,
    encryption_alg     text not null default 'AES-256-GCM',
    checksum           text,
    encryption_key     text,
    encryption_nonce   text,
    thumbnail_path     text,
    thumbnail_size     bigint,
    thumbnail_checksum text,
    thumbnail_key      text,
    thumbnail_nonce    text,
    width              integer,
    height             integer,
    duration_ms        bigint,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),

    constraint media_files_path_unique unique (user_id, storage_path),
    constraint media_files_type_valid check (media_type in ('photo', 'video')),
    constraint media_files_size_valid check (file_size >= 0),
    constraint media_files_name_len check (char_length(file_name) between 1 and 255),
    constraint media_files_checksum_len check (checksum is null or char_length(checksum) <= 128),
    -- Path layout: {user_id}/{year}/{month}/{unique_file_id}.enc
    constraint media_files_path_owner check (storage_path like user_id::text || '/%'),
    constraint media_files_path_layout check (
        storage_path ~ '^[0-9a-f-]{36}/[0-9]{4}/(0[1-9]|1[0-2])/[A-Za-z0-9._-]+$'
    ),
    -- A row may only claim to be encrypted if it carries usable key material.
    constraint media_files_keys_present check (
        encrypted = false
        or (encryption_key is not null and encryption_nonce is not null)
    )
);

comment on table public.media_files is
    'Metadata for every object in the private bucket. storage_path is {user_id}/{year}/{month}/{unique_file_id}.enc';

create index if not exists media_files_user_created_idx
    on public.media_files (user_id, created_at desc);
create index if not exists media_files_user_type_idx
    on public.media_files (user_id, media_type);
create index if not exists media_files_user_size_idx
    on public.media_files (user_id, file_size desc);

-- -------------------------------------------------------------- upload_jobs ---
create table if not exists public.upload_jobs (
    id           uuid primary key,
    user_id      uuid not null references auth.users (id) on delete cascade,
    -- The job is created before the media row exists (the row is only written after the
    -- upload is verified), so media_id is an index-only reference rather than a hard FK.
    media_id     uuid not null,
    status       text not null default 'queued',
    retry_count  integer not null default 0,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    constraint upload_jobs_status_valid check (
        status in ('queued', 'processing', 'uploading', 'verifying', 'completed', 'failed', 'cancelled')
    ),
    constraint upload_jobs_retry_non_negative check (retry_count >= 0),
    constraint upload_jobs_retry_cap check (retry_count <= 100)
);

comment on table public.upload_jobs is
    'Server-side mirror of the on-device upload queue while a job is in flight (status + retry_count). The app removes the row once the media row has been written, so this table stays small.';

create index if not exists upload_jobs_user_status_idx
    on public.upload_jobs (user_id, status);
create index if not exists upload_jobs_media_idx
    on public.upload_jobs (media_id);

-- ---------------------------------------------------------------- triggers ---
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists profiles_touch_updated_at on public.profiles;
create trigger profiles_touch_updated_at
    before update on public.profiles
    for each row execute function public.touch_updated_at();

drop trigger if exists media_files_touch_updated_at on public.media_files;
create trigger media_files_touch_updated_at
    before update on public.media_files
    for each row execute function public.touch_updated_at();

drop trigger if exists upload_jobs_touch_updated_at on public.upload_jobs;
create trigger upload_jobs_touch_updated_at
    before update on public.upload_jobs
    for each row execute function public.touch_updated_at();

-- Create the profile row as soon as a user signs up.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, email)
    values (new.id, new.email)
    on conflict (id) do update set email = excluded.email;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- --------------------------------------------------------------------- RLS ---
alter table public.profiles      enable row level security;
alter table public.media_files   enable row level security;
alter table public.upload_jobs   enable row level security;

alter table public.profiles      force row level security;
alter table public.media_files   force row level security;
alter table public.upload_jobs   force row level security;

-- profiles: a user may only read/update their own row.
drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own" on public.profiles
    for select to authenticated
    using (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
    for update to authenticated
    using (auth.uid() = id)
    with check (auth.uid() = id);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
    for insert to authenticated
    with check (auth.uid() = id);

-- media_files: full CRUD, but only for rows the caller owns.
drop policy if exists "media_files_select_own" on public.media_files;
create policy "media_files_select_own" on public.media_files
    for select to authenticated
    using (auth.uid() = user_id);

drop policy if exists "media_files_insert_own" on public.media_files;
create policy "media_files_insert_own" on public.media_files
    for insert to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "media_files_update_own" on public.media_files;
create policy "media_files_update_own" on public.media_files
    for update to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "media_files_delete_own" on public.media_files;
create policy "media_files_delete_own" on public.media_files
    for delete to authenticated
    using (auth.uid() = user_id);

-- upload_jobs: same rule, scoped to the owning user.
drop policy if exists "upload_jobs_select_own" on public.upload_jobs;
create policy "upload_jobs_select_own" on public.upload_jobs
    for select to authenticated
    using (auth.uid() = user_id);

drop policy if exists "upload_jobs_insert_own" on public.upload_jobs;
create policy "upload_jobs_insert_own" on public.upload_jobs
    for insert to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "upload_jobs_update_own" on public.upload_jobs;
create policy "upload_jobs_update_own" on public.upload_jobs
    for update to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "upload_jobs_delete_own" on public.upload_jobs;
create policy "upload_jobs_delete_own" on public.upload_jobs
    for delete to authenticated
    using (auth.uid() = user_id);

-- Grant table privileges; RLS still decides which rows are visible.
grant usage on schema public to authenticated;
grant select, insert, update, delete on public.profiles     to authenticated;
grant select, insert, update, delete on public.media_files  to authenticated;
grant select, insert, update, delete on public.upload_jobs  to authenticated;

revoke all on public.media_files  from anon;
revoke all on public.upload_jobs  from anon;
revoke all on public.profiles     from anon;

-- --------------------------------------------------------------- usage view ---
-- Handy for the dashboard in the Supabase UI; the app computes the same numbers
-- through PostgREST so no RPC is required.
create or replace view public.media_usage
with (security_invoker = true) as
select
    user_id,
    count(*)                                        as asset_count,
    count(*) filter (where media_type = 'photo')    as photo_count,
    count(*) filter (where media_type = 'video')    as video_count,
    coalesce(sum(file_size), 0)                     as total_bytes
from public.media_files
group by user_id;

grant select on public.media_usage to authenticated;

-- ============================================================================
--  Part 1 compatibility
--
--  Part 1 shipped `public.media_assets`. It is left in place (dropping it would
--  destroy existing rows) and is simply no longer written to. If you have no
--  rows to keep, remove it manually:
--      drop table if exists public.media_assets cascade;
-- ============================================================================
