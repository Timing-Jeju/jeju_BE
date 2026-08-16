-- Issue #36: TAGO 노선·경유 정류장 importer checkpoint와 순번 계약.

-- TAGO route 한 run은 provider/service/scope를 고정한 채 공식 3개 operation을 함께 수집한다.
create or replace function public.validate_external_snapshot_import_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  perform import_run.id
  from public.data_import_runs import_run
  where import_run.id = new.import_run_id
    and import_run.source_provider = new.source_provider
    and import_run.source_service = new.source_service
    and import_run.scope_key = new.scope_key
    and (
      import_run.source_operation = new.source_operation
      or (
        import_run.source_provider = 'TAGO'
        and import_run.source_service = 'BusRouteInfoInqireService'
        and import_run.source_operation = 'getRouteNoList'
        and new.source_operation in (
          'getRouteNoList', 'getRouteInfoIem', 'getRouteAcctoThrghSttnList'
        )
      )
    )
  for key share;

  if not found then
    raise exception using
      errcode = '23514',
      message = 'external snapshot source scope must match its import run';
  end if;

  return new;
end;
$$;

do $$
declare
  duplicate_record record;
  invalid_sequence record;
begin
  select source_provider, source_service, city_code, external_route_id, count(*) duplicate_count
  into duplicate_record
  from public.bus_routes
  where source_provider = 'TAGO'
    and source_service = 'BusRouteInfoInqireService'
    and external_route_id is not null
  group by source_provider, source_service, city_code, external_route_id
  having count(*) > 1
  limit 1;

  if found then
    raise exception using errcode = '23505', message = pg_catalog.format(
      'legacy TAGO route natural key collision: provider=%s service=%s city=%s route=%s count=%s',
      duplicate_record.source_provider, duplicate_record.source_service,
      duplicate_record.city_code, duplicate_record.external_route_id,
      duplicate_record.duplicate_count);
  end if;

  select route_id, direction_key, min(stop_sequence) minimum_sequence,
         max(stop_sequence) maximum_sequence, count(*) sequence_count
  into invalid_sequence
  from public.route_stops
  group by route_id, direction_key
  having btrim(direction_key) = ''
     or min(stop_sequence) <> 1
     or max(stop_sequence) <> count(*)
  limit 1;

  if found then
    raise exception using errcode = '23514', message = pg_catalog.format(
      'legacy route stop sequence is not positive contiguous unique: route=%s direction=%s min=%s max=%s count=%s',
      invalid_sequence.route_id, invalid_sequence.direction_key,
      invalid_sequence.minimum_sequence, invalid_sequence.maximum_sequence,
      invalid_sequence.sequence_count);
  end if;
end $$;

insert into public.data_import_checkpoints (
  source_provider, source_service, source_operation, scope_key, checkpoint, source_watermark_at
) values (
  'TAGO', 'BusRouteInfoInqireService', 'getRouteNoList', 'jeju-routes',
  '{"routeCount":0,"routeStopCount":0}'::jsonb,
  '1970-01-01T00:00:00Z'::timestamptz
)
on conflict (source_provider, source_service, source_operation, scope_key) do nothing;

alter table public.route_stops
  add constraint ck_route_stops_direction_nonblank
  check (btrim(direction_key) <> '') not valid;
alter table public.route_stops validate constraint ck_route_stops_direction_nonblank;

create function public.validate_route_stop_sequence_contiguous()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op <> 'INSERT' and exists (
    select 1
    from public.route_stops
    where route_id = old.route_id and direction_key = old.direction_key
    group by route_id, direction_key
    having min(stop_sequence) <> 1 or max(stop_sequence) <> count(*)
  ) then
    raise exception using errcode = '23514', message = 'route stop sequence must be positive contiguous unique';
  end if;

  if tg_op <> 'DELETE' and exists (
    select 1
    from public.route_stops
    where route_id = new.route_id and direction_key = new.direction_key
    group by route_id, direction_key
    having min(stop_sequence) <> 1 or max(stop_sequence) <> count(*)
  ) then
    raise exception using errcode = '23514', message = 'route stop sequence must be positive contiguous unique';
  end if;
  return null;
end;
$$;

create constraint trigger trg_route_stops_sequence_contiguous
after insert or update or delete on public.route_stops
deferrable initially deferred
for each row execute function public.validate_route_stop_sequence_contiguous();

create index idx_bus_routes_tago_scope_freshness
  on public.bus_routes (
    source_provider, source_service, city_code, route_no, stale, last_seen_at desc, external_route_id
  );

create index idx_route_stops_scope_direction_sequence
  on public.route_stops (
    source_provider, city_code, route_id, direction_key, stop_sequence, stop_id
  );

alter table public.bus_routes enable row level security;
alter table public.route_stops enable row level security;
revoke all on public.bus_routes from anon, authenticated;
revoke all on public.route_stops from anon, authenticated;
