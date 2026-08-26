create table public.push_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id uuid not null,
  platform text not null check (platform in ('IOS', 'ANDROID')),
  token_ciphertext text not null check (char_length(token_ciphertext) between 1 and 5500),
  token_fingerprint bytea not null check (octet_length(token_fingerprint) = 32),
  permission_status text not null
    check (permission_status in ('GRANTED', 'DENIED', 'NOT_DETERMINED')),
  app_version text not null check (char_length(app_version) between 1 and 50),
  locale text not null check (
    char_length(locale) between 2 and 35
    and locale ~ '^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?(?:-[A-Za-z0-9]{5,8}|-[0-9][A-Za-z0-9]{3})*(?:-[0-9A-WYZa-wy-z](?:-[A-Za-z0-9]{2,8})+)*(?:-x(?:-[A-Za-z0-9]{1,8})+)?$'
  ),
  time_zone text not null check (char_length(time_zone) between 1 and 64),
  last_seen_at timestamptz not null,
  invalidated_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null,
  unique (user_id, device_id),
  check (updated_at >= created_at),
  check (last_seen_at >= created_at),
  check (invalidated_at is null or invalidated_at >= created_at)
);

create unique index push_devices_active_token_fingerprint_key
  on public.push_devices (token_fingerprint)
  where invalidated_at is null;

create index push_devices_user_active_idx
  on public.push_devices (user_id, updated_at desc, device_id)
  where invalidated_at is null and permission_status = 'GRANTED';

create table public.notification_preferences (
  user_id uuid primary key references auth.users(id) on delete cascade,
  next_destination_departure_enabled boolean not null default false,
  safety_buffer_minutes integer not null default 10
    check (safety_buffer_minutes between 0 and 120),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null,
  check (updated_at >= created_at)
);

alter table public.push_devices enable row level security;
alter table public.notification_preferences enable row level security;

create policy push_devices_owner_select
  on public.push_devices for select
  to authenticated
  using ( (select auth.uid()) = user_id );

create policy push_devices_owner_insert
  on public.push_devices for insert
  to authenticated
  with check ( (select auth.uid()) = user_id );

create policy push_devices_owner_update
  on public.push_devices for update
  to authenticated
  using ( (select auth.uid()) = user_id )
  with check ( (select auth.uid()) = user_id );

create policy notification_preferences_owner_select
  on public.notification_preferences for select
  to authenticated
  using ( (select auth.uid()) = user_id );

create policy notification_preferences_owner_insert
  on public.notification_preferences for insert
  to authenticated
  with check ( (select auth.uid()) = user_id );

create policy notification_preferences_owner_update
  on public.notification_preferences for update
  to authenticated
  using ( (select auth.uid()) = user_id )
  with check ( (select auth.uid()) = user_id );

revoke all on public.push_devices from public;
revoke all on public.push_devices from anon;
revoke all on public.push_devices from authenticated;
revoke all on public.notification_preferences from public;
revoke all on public.notification_preferences from anon;
revoke all on public.notification_preferences from authenticated;

grant select (
  id, user_id, device_id, platform, permission_status, app_version, locale, time_zone,
  last_seen_at, invalidated_at, created_at, updated_at
) on public.push_devices to authenticated;
grant select, insert, update on public.notification_preferences to authenticated;

grant select, insert, update, delete on public.push_devices to service_role;
grant select, insert, update, delete on public.notification_preferences to service_role;
revoke truncate on public.push_devices from service_role;
revoke truncate on public.notification_preferences from service_role;
