-- Issue #38: timetable provenance와 TAGO route reference scope를 분리한다.
-- legacy 행은 자동 수정/삭제하지 않고 기존 source scope를 새 명시 컬럼에 그대로 복사한다.

alter table public.timetable_entries
  add column route_source_provider text,
  add column route_city_code text;

update public.timetable_entries
set route_source_provider = source_provider;

update public.timetable_entries
set route_city_code = city_code;

alter table public.timetable_entries
  add constraint ck_timetable_route_source_provider_nonblank
    check (route_source_provider is not null and btrim(route_source_provider) <> '') not valid,
  add constraint ck_timetable_route_city_code_nonblank
    check (route_city_code is not null and btrim(route_city_code) <> '') not valid,
  add constraint ck_timetable_route_scope_lengths
    check (
      octet_length(route_source_provider) <= 128
      and octet_length(route_city_code) <= 64
      and octet_length(route_source_provider) + octet_length(route_city_code) <= 512
    ) not valid;

create or replace function public.validate_timetable_source_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and (old.route_source_provider is null or old.route_city_code is null)
     and (new.route_source_provider is null or new.route_city_code is null) then
    if to_jsonb(old) is not distinct from to_jsonb(new) then
      return new;
    end if;
    raise exception using errcode = '23514', message = 'legacy timetable source identity is immutable';
  end if;

  if new.route_source_provider is null or btrim(new.route_source_provider) = ''
     or new.route_city_code is null or btrim(new.route_city_code) = '' then
    raise exception using
      errcode = '23502',
      message = 'new timetable row requires route reference scope';
  end if;

  perform route_stop.route_id
  from public.route_stops route_stop
  join public.bus_routes route on route.id = route_stop.route_id
  join public.bus_stops stop on stop.id = route_stop.stop_id
  where route_stop.route_id = new.route_id
    and route_stop.direction_key = new.direction_key
    and route_stop.stop_id = new.stop_id
    and route_stop.source_provider = new.route_source_provider
    and route_stop.city_code = new.route_city_code
    and route.source_provider = new.route_source_provider
    and route.city_code = new.route_city_code
    and stop.source_provider = new.route_source_provider
    and stop.city_code = new.route_city_code
  for key share of route_stop, route, stop;

  if not found then
    raise exception using
      errcode = '23514',
      message = 'timetable route reference scope must match a valid route stop';
  end if;
  return new;
end;
$$;

drop index if exists public.idx_timetable_route_stop_source_scope;
create index idx_timetable_route_stop_reference_scope
  on public.timetable_entries (
    route_id, direction_key, stop_id, route_source_provider, route_city_code
  )
  where route_source_provider is not null and route_city_code is not null;

-- JEJU_PROVINCE dataset PK 3043887 snapshot은 exact-byte SHA-256은 payload_hash에만 두고,
-- raw XLSX bytes must not be persisted: 정규화 manifest JSON만 raw_payload에 허용한다.
create or replace function public.jeju_timetable_manifest_is_safe(payload jsonb)
returns boolean
language plpgsql
immutable
strict
set search_path = pg_catalog, public
as $$
declare
  record_key jsonb;
begin
  if jsonb_typeof(payload) <> 'object'
     or (payload - array[
       'datasetId', 'effectiveDate', 'mappingVersion', 'omissionCount', 'omissionCode',
       'parserVersion', 'recordKeys'
     ]::text[]) <> '{}'::jsonb
     or payload->>'datasetId' <> '3043887'
     or payload->>'mappingVersion' <> 'operator-mapping-v1'
     or payload->>'parserVersion' <> 'jeju-timetable-xlsx-v1'
     or payload->>'omissionCode' <> 'UNRESOLVED_OFFICIAL_COLUMN_OMITTED'
     or jsonb_typeof(payload->'omissionCount') <> 'number'
     or jsonb_typeof(payload->'recordKeys') <> 'array'
     or jsonb_array_length(payload->'recordKeys') > 640000
     or octet_length(payload::text) > 4194304 then
    return false;
  end if;
  for record_key in select value from jsonb_array_elements(payload->'recordKeys') loop
    if jsonb_typeof(record_key) <> 'string'
       or (record_key #>> '{}') !~ '^3043887/40500(1|9)/[a-z0-9_-]{1,128}/(out|in)/[0-9]{1,5}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/daily/(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$' then
      return false;
    end if;
  end loop;
  return true;
exception
  when others then
    return false;
end;
$$;

alter table public.external_api_snapshots
  add constraint ck_jeju_timetable_snapshot_manifest_only
    check (
      source_provider <> 'JEJU_PROVINCE'
      or source_service <> 'jeju-bus-schedule-xlsx'
      or source_operation <> 'timetable-import'
      or (
        payload_format = 'JSON'
        and public.jeju_timetable_manifest_is_safe(raw_payload)
      )
    ) not valid;
