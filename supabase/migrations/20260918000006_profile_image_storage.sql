-- Issue #78: immutable profile-image Storage generation and cleanup contract.
-- Object bytes are public by product decision. Public delivery is independent from database
-- SELECT; database access remains owner-scoped, insert-only, and upload upsert=false.
-- Storage object upload/delete is performed only through the Storage API. This migration only
-- configures storage.buckets and supported RLS policies on storage.objects.

alter table public.user_profiles
  add column if not exists profile_image_object_key text,
  add column if not exists profile_image_source text not null default 'none',
  add column if not exists profile_image_storage_etag text,
  add column if not exists profile_image_version bigint not null default 0;

update public.user_profiles
set profile_image_source = case
  when profile_image_url is not null then 'provider'
  else 'none'
end
where profile_image_object_key is null
  and profile_image_storage_etag is null;

alter table public.user_profiles
  drop constraint if exists ck_user_profiles_profile_image_object_key,
  drop constraint if exists ck_user_profiles_profile_image_source,
  drop constraint if exists ck_user_profiles_profile_image_state,
  drop constraint if exists ck_user_profiles_profile_image_storage_etag,
  drop constraint if exists ck_user_profiles_profile_image_version;

alter table public.user_profiles
  add constraint ck_user_profiles_profile_image_object_key
    check (
      profile_image_object_key is null
      or profile_image_object_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
  add constraint ck_user_profiles_profile_image_source
    check (profile_image_source in ('provider', 'storage', 'none')),
  add constraint ck_user_profiles_profile_image_state
    check (
      (
        profile_image_source = 'storage'
        and profile_image_object_key is not null
        and profile_image_storage_etag is not null
        and split_part(profile_image_object_key, '/', 1) = id::text
      )
      or (
        profile_image_source in ('provider', 'none')
        and profile_image_object_key is null
        and profile_image_storage_etag is null
      )
    ),
  add constraint ck_user_profiles_profile_image_storage_etag
    check (
      profile_image_storage_etag is null
      or profile_image_storage_etag ~ '^"[^"[:cntrl:]]+"$'
    ),
  add constraint ck_user_profiles_profile_image_version
    check (profile_image_version >= 0);

create or replace function public.sync_provider_profile_image_source()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.profile_image_object_key is null and new.profile_image_storage_etag is null then
    new.profile_image_source := case
      when new.profile_image_url is not null then 'provider'
      else 'none'
    end;
  end if;
  return new;
end;
$$;

drop trigger if exists sync_provider_profile_image_source on public.user_profiles;
create trigger sync_provider_profile_image_source
before insert or update of profile_image_url on public.user_profiles
for each row execute function public.sync_provider_profile_image_source();

revoke all on function public.sync_provider_profile_image_source() from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on function public.sync_provider_profile_image_source() from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on function public.sync_provider_profile_image_source() from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on function public.sync_provider_profile_image_source() from service_role';
  end if;
end $$;

create table if not exists public.profile_image_cleanup_outbox (
  id uuid primary key default gen_random_uuid(),
  owner_user_id uuid not null,
  object_key text not null,
  storage_etag text not null,
  source_profile_version bigint not null,
  reason text not null,
  status text not null default 'pending',
  claim_token uuid,
  claimed_at timestamptz,
  attempt_count integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (object_key, storage_etag),
  constraint ck_profile_image_cleanup_object_key
    check (
      object_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
  constraint ck_profile_image_cleanup_owner_key
    check (split_part(object_key, '/', 1) = owner_user_id::text),
  constraint ck_profile_image_cleanup_storage_etag
    check (storage_etag ~ '^"[^"[:cntrl:]]+"$'),
  constraint ck_cleanup_outbox_source_profile_version
    check (source_profile_version >= 0),
  constraint ck_cleanup_outbox_reason
    check (reason in ('replacement', 'clear', 'orphan', 'account_deletion')),
  constraint ck_cleanup_outbox_status
    check (status in ('pending', 'claimed', 'succeeded', 'retry')),
  constraint ck_cleanup_outbox_attempt_count
    check (attempt_count >= 0 and (status <> 'retry' or attempt_count > 0)),
  constraint ck_cleanup_outbox_claim_state
    check (
      (status = 'claimed' and claim_token is not null and claimed_at is not null)
      or (status <> 'claimed' and claim_token is null and claimed_at is null)
    ),
  constraint ck_cleanup_outbox_completion_state
    check (
      (status = 'succeeded' and completed_at is not null)
      or (status <> 'succeeded' and completed_at is null)
    )
);

create index if not exists idx_profile_image_cleanup_claim
  on public.profile_image_cleanup_outbox (status, next_attempt_at, created_at);

create table if not exists public.profile_image_orphan_scan_cursor (
  scanner_name text primary key,
  owner_offset integer not null default 0,
  object_offset integer not null default 0,
  revision bigint not null default 0,
  updated_at timestamptz not null default now(),
  constraint ck_profile_image_orphan_owner_offset check (owner_offset >= 0),
  constraint ck_profile_image_orphan_object_offset check (object_offset >= 0),
  constraint ck_profile_image_orphan_revision check (revision >= 0)
);

insert into public.profile_image_orphan_scan_cursor (
  scanner_name, owner_offset, object_offset, revision
) values ('profile-images-orphan-scan', 0, 0, 0)
on conflict (scanner_name) do nothing;

alter table public.profile_image_orphan_scan_cursor enable row level security;
revoke all on table public.profile_image_orphan_scan_cursor from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on table public.profile_image_orphan_scan_cursor from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on table public.profile_image_orphan_scan_cursor from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on table public.profile_image_orphan_scan_cursor from service_role';
    execute 'grant select, insert, update on table public.profile_image_orphan_scan_cursor to service_role';
  end if;
end $$;

alter table public.profile_image_cleanup_outbox enable row level security;
revoke all on table public.profile_image_cleanup_outbox from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on table public.profile_image_cleanup_outbox from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on table public.profile_image_cleanup_outbox from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on table public.profile_image_cleanup_outbox from service_role';
    execute 'grant select, insert, update on table public.profile_image_cleanup_outbox to service_role';
  end if;
end $$;

-- A plain PostgreSQL compatibility database has no Storage schema. Supabase projects execute this block.
do $$
begin
  if to_regclass('storage.buckets') is not null
     and to_regclass('storage.objects') is not null then
    execute $storage$
      insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
      values (
        'profile-images',
        'profile-images',
        true,
        5242880,
        array['image/jpeg', 'image/png', 'image/webp']::text[]
      )
      on conflict (id) do update
      set name = excluded.name,
          public = excluded.public,
          file_size_limit = excluded.file_size_limit,
          allowed_mime_types = excluded.allowed_mime_types
    $storage$;

    execute 'drop policy if exists profile_images_owner_insert on storage.objects';
    execute 'drop policy if exists profile_images_owner_select on storage.objects';
    execute 'drop policy if exists profile_images_anon_insert_guard on storage.objects';
    execute 'drop policy if exists profile_images_anon_select_guard on storage.objects';
    execute 'drop policy if exists profile_images_bucket_insert_guard on storage.objects';
    execute 'drop policy if exists profile_images_bucket_select_guard on storage.objects';
    execute 'drop policy if exists profile_images_bucket_update_guard on storage.objects';
    execute 'drop policy if exists profile_images_bucket_delete_guard on storage.objects';

    execute 'create policy profile_images_owner_insert on storage.objects
      as permissive for insert to authenticated
      with check (
        bucket_id = ''profile-images''
        and owner_id = (select auth.uid()::text)
        and name ~ (''^'' || (select auth.uid()::text) || ''/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'')
      )';
    execute 'create policy profile_images_owner_select on storage.objects
      as permissive for select to authenticated
      using (
        bucket_id = ''profile-images''
        and owner_id = (select auth.uid()::text)
        and name ~ (''^'' || (select auth.uid()::text) || ''/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'')
      )';

    execute 'create policy profile_images_bucket_insert_guard on storage.objects
      as restrictive for insert to authenticated
      with check (case when bucket_id <> ''profile-images'' then true else (
        owner_id = (select auth.uid()::text)
        and name ~ (''^'' || (select auth.uid()::text) || ''/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'')
      ) end)';
    execute 'create policy profile_images_bucket_select_guard on storage.objects
      as restrictive for select to authenticated
      using (case when bucket_id <> ''profile-images'' then true else (
        owner_id = (select auth.uid()::text)
        and name ~ (''^'' || (select auth.uid()::text) || ''/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'')
      ) end)';
    execute 'create policy profile_images_bucket_update_guard on storage.objects
      as restrictive for update to authenticated
      using (case when bucket_id <> ''profile-images'' then true else (false) end)
      with check (case when bucket_id <> ''profile-images'' then true else (false) end)';
    execute 'create policy profile_images_bucket_delete_guard on storage.objects
      as restrictive for delete to authenticated
      using (case when bucket_id <> ''profile-images'' then true else (false) end)';

    execute 'create policy profile_images_anon_insert_guard on storage.objects
      as restrictive for insert to anon
      with check (case when bucket_id <> ''profile-images'' then true else (false) end)';
    execute 'create policy profile_images_anon_select_guard on storage.objects
      as restrictive for select to anon
      using (case when bucket_id <> ''profile-images'' then true else (false) end)';
  end if;
end $$;
