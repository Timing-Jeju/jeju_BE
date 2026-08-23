-- Issue #37: 관광지-정류장 PostGIS 후보 link lifecycle, freshness, complete scope watermark.

alter table public.place_stop_links
  add column enabled boolean not null default true,
  add column source_provider text,
  add column observed_at timestamptz,
  add column expires_at timestamptz,
  add column tombstoned_at timestamptz;

update public.place_stop_links
set source_provider = 'legacy',
    observed_at = created_at,
    expires_at = created_at + interval '24 hours'
where source_provider is null or observed_at is null or expires_at is null;

alter table public.place_stop_links
  alter column source_provider set not null,
  alter column observed_at set not null,
  alter column expires_at set not null,
  add constraint ck_place_stop_links_lifecycle check (
    btrim(source_provider) <> ''
    and expires_at > observed_at
    and not (tombstoned_at is not null and enabled)
  );

drop index public.idx_place_stop_links_place_distance;

create index idx_place_stop_links_eligible
  on public.place_stop_links (place_id, expires_at, distance_meters, stop_id)
  where enabled and tombstoned_at is null;

create table public.place_stop_link_scope_states (
  place_id uuid not null references public.tour_places(id) on delete cascade,
  source_provider text not null check (btrim(source_provider) <> ''),
  observed_at timestamptz not null,
  manifest_fingerprint text not null check (manifest_fingerprint ~ '^[0-9a-f]{64}$'),
  updated_at timestamptz not null default now(),
  primary key (place_id, source_provider)
);

alter table public.place_stop_links enable row level security;
alter table public.place_stop_link_scope_states enable row level security;
revoke all on public.place_stop_links from anon, authenticated;
revoke all on public.place_stop_link_scope_states from anon, authenticated;

do $$
begin
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role') then
    grant select, insert, update, delete on public.place_stop_links to service_role;
    grant select, insert, update, delete on public.place_stop_link_scope_states to service_role;
  end if;
end $$;
