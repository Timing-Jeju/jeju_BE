-- Issue #39: TAGO 정류장 도착정보 snapshot·single-flight cache 저장 계약.
-- 기존 공유 migration 이후 복구된 unpublished 변경이므로 append-only 최신 timestamp를 사용한다.

alter table public.bus_arrival_snapshots
  add column source_service text,
  add column route_type text;

update public.bus_arrival_snapshots arrival
set source_service = snapshot.source_service
from public.external_api_snapshots snapshot
where snapshot.id = arrival.source_snapshot_id
  and arrival.source_service is null;

update public.bus_arrival_snapshots
set source_service = 'legacy'
where source_service is null;

alter table public.bus_arrival_snapshots
  alter column source_service set default 'ArvlInfoInqireService',
  alter column source_service set not null,
  add constraint ck_bus_arrivals_source_service_nonblank
    check (btrim(source_service) <> '') not valid,
  add constraint ck_bus_arrivals_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and (route_type is null or octet_length(route_type) <= 128)
    ) not valid,
  add constraint ck_bus_arrivals_arrival_seconds_bounds
    check (estimated_arrival_seconds between 0 and 86400) not valid,
  add constraint ck_bus_arrivals_remaining_stops_bounds
    check (remaining_stops is null or remaining_stops between 0 and 10000) not valid;

create index idx_bus_arrivals_source_stop_freshness
  on public.bus_arrival_snapshots (
    source_provider, source_service, stop_id, observed_at desc
  ) include (expires_at, source_snapshot_id, import_run_id)
  where octet_length(source_provider) <= 128
    and octet_length(source_service) <= 128;

create function public.validate_bus_arrival_observation_lineage()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      new.source_provider || '|' || new.source_service || '|' || new.stop_id::text
        || '|' || new.observed_at::text,
      0
    )
  );

  if exists (
    select 1
    from public.bus_arrival_snapshots existing
    where existing.id <> new.id
      and existing.source_provider = new.source_provider
      and existing.source_service = new.source_service
      and existing.stop_id = new.stop_id
      and existing.observed_at = new.observed_at
      and (
        existing.import_run_id is distinct from new.import_run_id
        or existing.source_snapshot_id is distinct from new.source_snapshot_id
        or existing.expires_at is distinct from new.expires_at
      )
  ) then
    raise exception using
      errcode = '23505',
      message = 'bus arrival observation has conflicting lineage';
  end if;

  return new;
end;
$$;

create trigger trg_bus_arrivals_observation_lineage
before insert or update of source_provider, source_service, stop_id, observed_at,
  expires_at, import_run_id, source_snapshot_id
on public.bus_arrival_snapshots
for each row execute function public.validate_bus_arrival_observation_lineage();

alter table public.bus_arrival_snapshots enable row level security;
revoke all on public.bus_arrival_snapshots from anon, authenticated;
