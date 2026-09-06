-- Issue #38: timetable provenance와 TAGO route reference scope를 분리한다.
-- legacy 행은 자동 수정/삭제하지 않고 기존 source scope를 새 명시 컬럼에 그대로 복사한다.

begin;

-- 기존 immutability trigger를 유지한 채 backfill 전용 함수로 잠시 좁혀 교체한다.
-- ALTER와 backfill 사이에 다른 writer가 끼지 못하도록 같은 transaction에서 선점한다.
lock table public.timetable_entries in access exclusive mode;

alter table public.timetable_entries
  add column route_source_provider text,
  add column route_city_code text;

create or replace function public.validate_timetable_source_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op <> 'UPDATE'
     or old.route_source_provider is not null
     or old.route_city_code is not null
     or not (new.route_source_provider is not distinct from old.source_provider)
     or not (new.route_city_code is not distinct from old.city_code)
     or (
       pg_catalog.to_jsonb(new) - array['route_source_provider', 'route_city_code']::text[]
     ) is distinct from (
       pg_catalog.to_jsonb(old) - array['route_source_provider', 'route_city_code']::text[]
     ) then
    raise exception using
      errcode = '23514',
      message = 'timetable route scope backfill violated audited old/new scope';
  end if;
  return new;
end;
$$;

revoke execute on function public.validate_timetable_source_scope() from public, anon, authenticated;

-- 기존 AFTER constraint trigger도 route scope 두 컬럼만 채우는 이 transaction의
-- migration-owner backfill만 허용하도록 잠시 교체한다. shared lineage 함수 자체는
-- 다른 정규화 테이블에서도 사용하므로 변경하지 않는다.
create function public.validate_timetable_route_scope_backfill_lineage()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op <> 'UPDATE'
     or old.route_source_provider is not null
     or old.route_city_code is not null
     or not (new.route_source_provider is not distinct from old.source_provider)
     or not (new.route_city_code is not distinct from old.city_code)
     or (
       pg_catalog.to_jsonb(new) - array['route_source_provider', 'route_city_code']::text[]
     ) is distinct from (
       pg_catalog.to_jsonb(old) - array['route_source_provider', 'route_city_code']::text[]
     ) then
    raise exception using
      errcode = '23514',
      message = 'timetable lineage backfill violated audited old/new scope';
  end if;
  return new;
end;
$$;

revoke execute on function public.validate_timetable_route_scope_backfill_lineage() from public, anon, authenticated;

drop trigger trg_timetable_source_lineage on public.timetable_entries;
create constraint trigger trg_timetable_source_lineage
after insert or update on public.timetable_entries
deferrable initially immediate
for each row execute function public.validate_timetable_route_scope_backfill_lineage();

update public.timetable_entries
set route_source_provider = source_provider,
    route_city_code = city_code
where route_source_provider is null
  and route_city_code is null;

do $$
begin
  if exists (
    select 1
    from public.timetable_entries
    where route_source_provider is distinct from source_provider
       or route_city_code is distinct from city_code
  ) then
    raise exception using
      errcode = '23514',
      message = 'timetable route scope backfill violated audited old/new scope';
  end if;
end;
$$;

-- ACCESS EXCLUSIVE lock을 유지한 채 원래 strict lineage trigger를 즉시 복원하고
-- backfill 전용 함수는 남기지 않는다.
drop trigger trg_timetable_source_lineage on public.timetable_entries;
create constraint trigger trg_timetable_source_lineage
after insert or update on public.timetable_entries
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();
drop function public.validate_timetable_route_scope_backfill_lineage();

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

  if new.city_code is null or btrim(new.city_code) = '' then
    raise exception using
      errcode = '23502',
      message = 'new timetable row requires provider city scope';
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

revoke execute on function public.validate_timetable_source_scope() from public, anon, authenticated;

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

drop index if exists public.idx_timetable_route_stop_source_scope;
create index idx_timetable_route_stop_reference_scope
  on public.timetable_entries (
    route_id, direction_key, stop_id, route_source_provider, route_city_code
  )
  where route_source_provider is not null and route_city_code is not null;

-- JEJU_PROVINCE dataset PK 3043887 snapshot은 exact-byte SHA-256은 payload_hash에만 두고,
-- raw XLSX bytes must not be persisted: 정규화 manifest JSON만 raw_payload에 허용한다.
create or replace function public.jeju_timetable_manifest_is_safe(
  payload jsonb,
  request_metadata jsonb
)
returns boolean
language plpgsql
immutable
strict
set search_path = pg_catalog, public
as $$
declare
  record_key jsonb;
  omission_detail jsonb;
  omission_text text;
  omission_row integer;
  omission_column integer;
begin
  if jsonb_typeof(payload) <> 'object'
     or not (payload ?& array[
       'datasetId', 'scheduleId', 'effectiveDate', 'mappingVersion', 'omissionCount',
       'omissionCode', 'parserVersion', 'recordKeys', 'omissions'
     ]::text[])
     or (payload - array[
       'datasetId', 'scheduleId', 'effectiveDate', 'mappingVersion', 'omissionCount',
       'omissionCode', 'parserVersion', 'recordKeys', 'omissions'
     ]::text[]) <> '{}'::jsonb
     or payload->>'datasetId' <> '3043887'
     or jsonb_typeof(payload->'scheduleId') <> 'string'
     or payload->>'scheduleId' !~ '^40500(1|9)$'
     or payload->>'mappingVersion' <> 'operator-mapping-v1'
     or payload->>'parserVersion' <> 'jeju-timetable-xlsx-v1'
     or payload->>'omissionCode' <> 'UNRESOLVED_OFFICIAL_COLUMN_OMITTED'
     or jsonb_typeof(payload->'effectiveDate') <> 'string'
     or (payload->>'effectiveDate') !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'
     or to_char((payload->>'effectiveDate')::date, 'YYYY-MM-DD') <> payload->>'effectiveDate'
     or jsonb_typeof(payload->'omissionCount') <> 'number'
     or (payload->>'omissionCount') !~ '^(0|[1-9][0-9]{0,5})$'
     or jsonb_typeof(request_metadata) <> 'object'
     or not (request_metadata ?& array['effectiveDate', 'omissionCount']::text[])
     or (request_metadata - array['effectiveDate', 'omissionCount']::text[]) <> '{}'::jsonb
     or jsonb_typeof(request_metadata->'effectiveDate') <> 'string'
     or jsonb_typeof(request_metadata->'omissionCount') <> 'number'
     or payload->>'effectiveDate' <> request_metadata->>'effectiveDate'
     or payload->'omissionCount' is distinct from request_metadata->'omissionCount'
     or jsonb_typeof(payload->'recordKeys') <> 'array'
     or jsonb_array_length(payload->'recordKeys') > 640000
     or jsonb_typeof(payload->'omissions') <> 'array'
     or jsonb_array_length(payload->'omissions') > 126
     or (payload->>'omissionCount')::integer <> jsonb_array_length(payload->'omissions')
     or octet_length(payload::text) > 2097152 then
    return false;
  end if;
  for record_key in select value from jsonb_array_elements(payload->'recordKeys') loop
    if jsonb_typeof(record_key) <> 'string'
       or (record_key #>> '{}') !~ '^3043887/40500(1|9)/[a-z0-9_-]{1,128}/(out|in)/[0-9]{1,5}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/daily/(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$' then
      return false;
    end if;
    if not starts_with(
      record_key #>> '{}',
      '3043887/' || (payload->>'scheduleId') || '/'
    ) then
      return false;
    end if;
  end loop;
  for omission_detail in select value from jsonb_array_elements(payload->'omissions') loop
    if jsonb_typeof(omission_detail) <> 'string' then
      return false;
    end if;
    omission_text := omission_detail #>> '{}';
    if omission_text !~ '^(101 남원-성산-김녕-조천-공항|101 공항-조천-김녕-성산-남원|201 서귀포터미널-남원-성산-세화-조천-제주터미널|201 제주터미널-조천-세화-성산-남원-서귀포터미널)/row=[1-9][0-9]{0,4}/column=[1-9][0-9]{0,2}/UNRESOLVED_OFFICIAL_COLUMN_OMITTED$' then
      return false;
    end if;
    omission_row := split_part(split_part(omission_detail #>> '{}', '/row=', 2), '/column=', 1)::integer;
    omission_column := split_part(split_part(omission_detail #>> '{}', '/column=', 2), '/', 1)::integer;
    if omission_row > 10000
       or omission_column > 256
       or (payload->>'scheduleId' = '405001' and omission_text !~ '^101 ')
       or (payload->>'scheduleId' = '405009' and omission_text !~ '^201 ') then
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
        and public.jeju_timetable_manifest_is_safe(raw_payload, request_metadata_redacted)
      )
    ) not valid;

commit;
