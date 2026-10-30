-- =============================================================================
--  SlimeX Cloud Camera — database schema
--  Run this once in your Supabase project: SQL Editor → New query → Run.
-- =============================================================================
--
--  SECURITY MODEL
--  --------------
--  SlimeX is a single-owner personal app: the phone writes to a project that
--  belongs to you. The app authenticates with the *publishable / anon* key, so
--  the policies below grant the `anon` role access to the media table only.
--
--  Recommended hardening for shared or public projects:
--    1. Enable Supabase Auth (email or anonymous sign-in).
--    2. Replace `to anon` with `to authenticated`.
--    3. Scope every policy with `device_id = auth.uid()::text`.
--
--  Never use a service_role key in a client app.
-- =============================================================================

create extension if not exists "pgcrypto";

-- -----------------------------------------------------------------------------
-- Media metadata
-- -----------------------------------------------------------------------------
create table if not exists public.media_assets (
    id            uuid primary key default gen_random_uuid(),
    device_id     text        not null,
    kind          text        not null check (kind in ('photo', 'video')),
    bucket        text        not null default 'slimex-media',
    storage_path  text        not null,
    file_name     text        not null,
    mime_type     text,
    size_bytes    bigint      not null default 0,
    width         integer,
    height        integer,
    duration_ms   bigint,
    captured_at   timestamptz not null default now(),
    created_at    timestamptz not null default now(),
    deleted_at    timestamptz
);

comment on table public.media_assets is
    'One row per uploaded photo/video stored in the SlimeX private bucket.';

-- A storage object must never be referenced twice.
create unique index if not exists media_assets_object_key
    on public.media_assets (bucket, storage_path);

create index if not exists media_assets_captured_at_idx
    on public.media_assets (captured_at desc);

create index if not exists media_assets_device_idx
    on public.media_assets (device_id);

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------
alter table public.media_assets enable row level security;

drop policy if exists "slimex_select_media" on public.media_assets;
create policy "slimex_select_media"
    on public.media_assets
    for select
    to anon
    using (deleted_at is null);

drop policy if exists "slimex_insert_media" on public.media_assets;
create policy "slimex_insert_media"
    on public.media_assets
    for insert
    to anon
    with check (true);

drop policy if exists "slimex_update_media" on public.media_assets;
create policy "slimex_update_media"
    on public.media_assets
    for update
    to anon
    using (true)
    with check (true);

drop policy if exists "slimex_delete_media" on public.media_assets;
create policy "slimex_delete_media"
    on public.media_assets
    for delete
    to anon
    using (true);

-- -----------------------------------------------------------------------------
-- Helper view: per-device usage (optional, used by the Storage screen today
-- through the table itself; handy for SQL dashboards).
-- -----------------------------------------------------------------------------
create or replace view public.media_usage as
select
    device_id,
    kind,
    count(*)              as asset_count,
    coalesce(sum(size_bytes), 0) as total_bytes
from public.media_assets
where deleted_at is null
group by device_id, kind;
