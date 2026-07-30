-- 외부 응답과 정규화 행의 계보를 일치시키고 기준정보의 시간 중복을 차단한다.
-- 기존 area/category 계열 컬럼은 legacy 호환을 위해 유지한다.

alter table public.data_import_runs
  add column source_provider text not null default 'legacy',
  add column source_service text not null default 'legacy',
  add constraint ck_data_import_runs_source_provider_nonblank
    check (btrim(source_provider) <> ''),
  add constraint ck_data_import_runs_source_service_nonblank
    check (btrim(source_service) <> ''),
  add constraint uq_data_import_runs_provider_service
    unique (id, source_provider, source_service),
  add constraint uq_data_import_runs_checkpoint_scope
    unique (
      id,
      source_provider,
      source_service,
      source_operation,
      scope_key
    );

do $$
begin
  if exists (
    select 1
    from public.external_api_snapshots snapshot
    group by snapshot.import_run_id
    having count(distinct (
      snapshot.source_provider,
      snapshot.source_service,
      snapshot.source_operation,
      snapshot.scope_key
    )) > 1
  ) then
    raise exception using
      errcode = '23514',
      message = 'existing import run spans multiple snapshot source scopes';
  end if;
end;
$$;

update public.data_import_runs import_run
set source_provider = snapshot_scope.source_provider,
    source_service = snapshot_scope.source_service,
    source_operation = snapshot_scope.source_operation,
    scope_key = snapshot_scope.scope_key
from (
  select
    snapshot.import_run_id,
    min(snapshot.source_provider) as source_provider,
    min(snapshot.source_service) as source_service,
    min(snapshot.source_operation) as source_operation,
    min(snapshot.scope_key) as scope_key
  from public.external_api_snapshots snapshot
  group by snapshot.import_run_id
) snapshot_scope
where import_run.id = snapshot_scope.import_run_id;

update public.data_import_runs
set source_operation = 'legacy'
where source_operation is null;

alter table public.data_import_runs
  alter column source_operation set default 'legacy',
  alter column source_operation set not null;

alter table public.external_api_snapshots
  add constraint fk_external_snapshots_import_source_scope
    foreign key (
      import_run_id,
      source_provider,
      source_service,
      source_operation,
      scope_key
    )
    references public.data_import_runs (
      id,
      source_provider,
      source_service,
      source_operation,
      scope_key
    )
    on delete cascade;

create index idx_external_snapshots_import_source_scope
  on public.external_api_snapshots (
    import_run_id,
    source_provider,
    source_service,
    source_operation,
    scope_key
  );

alter table public.data_import_checkpoints
  add constraint fk_data_import_checkpoints_succeeded_scope
    foreign key (
      last_succeeded_run_id,
      source_provider,
      source_service,
      source_operation,
      scope_key
    )
    references public.data_import_runs (
      id,
      source_provider,
      source_service,
      source_operation,
      scope_key
    )
    on delete set null (last_succeeded_run_id);

create index idx_data_import_checkpoints_succeeded_scope
  on public.data_import_checkpoints (
    last_succeeded_run_id,
    source_provider,
    source_service,
    source_operation,
    scope_key
  )
  where last_succeeded_run_id is not null;

create function public.validate_checkpoint_succeeded_run()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  run_status text;
begin
  if new.last_succeeded_run_id is null then
    return new;
  end if;

  select r.status
    into run_status
  from public.data_import_runs r
  where r.id = new.last_succeeded_run_id
    and r.source_provider = new.source_provider
    and r.source_service = new.source_service
    and r.source_operation = new.source_operation
    and r.scope_key = new.scope_key
  for share;

  if run_status is null then
    raise exception using
      errcode = '23514',
      message = 'checkpoint source scope must match its import run';
  end if;

  if run_status <> 'succeeded' then
    raise exception using
      errcode = '23514',
      message = 'checkpoint may reference only a succeeded import run';
  end if;

  return new;
end;
$$;

create constraint trigger trg_data_import_checkpoints_succeeded_run
after insert or update on public.data_import_checkpoints
deferrable initially immediate
for each row
execute function public.validate_checkpoint_succeeded_run();

create function public.protect_checkpoint_succeeded_run()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status <> 'succeeded'
     and exists (
       select 1
       from public.data_import_checkpoints checkpoint_row
       where checkpoint_row.last_succeeded_run_id = new.id
     ) then
    raise exception using
      errcode = '23514',
      message = 'a checkpoint-referenced run must remain succeeded';
  end if;

  return new;
end;
$$;

create constraint trigger trg_data_import_runs_protect_checkpoint
after update of status on public.data_import_runs
deferrable initially immediate
for each row
when (old.status is distinct from new.status)
execute function public.protect_checkpoint_succeeded_run();

create function public.protect_external_snapshot_identity()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.import_run_id is distinct from new.import_run_id
     or old.source_provider is distinct from new.source_provider
     or old.source_service is distinct from new.source_service
     or old.source_operation is distinct from new.source_operation
     or old.scope_key is distinct from new.scope_key then
    raise exception using
      errcode = '23514',
      message = 'external snapshot source identity is immutable';
  end if;

  if old.parse_status in ('parsed', 'tombstoned')
     and new.parse_status not in ('parsed', 'tombstoned') then
    raise exception using
      errcode = '23514',
      message = 'a normalized-capable snapshot cannot return to an unparsed status';
  end if;

  return new;
end;
$$;

create trigger trg_external_snapshots_immutable_identity
before update of
  import_run_id,
  source_provider,
  source_service,
  source_operation,
  scope_key,
  parse_status
on public.external_api_snapshots
for each row
execute function public.protect_external_snapshot_identity();

alter table public.tour_place_sources
  add column l_dong_regn_cd text,
  add column l_dong_signgu_cd text,
  add column lcls_systm1 text,
  add column lcls_systm2 text,
  add column lcls_systm3 text,
  add constraint ck_tour_place_sources_latest_codes_nonblank
    check (
      (l_dong_regn_cd is null or btrim(l_dong_regn_cd) <> '')
      and (l_dong_signgu_cd is null or btrim(l_dong_signgu_cd) <> '')
      and (lcls_systm1 is null or btrim(lcls_systm1) <> '')
      and (lcls_systm2 is null or btrim(lcls_systm2) <> '')
      and (lcls_systm3 is null or btrim(lcls_systm3) <> '')
    );

comment on column public.tour_place_sources.l_dong_regn_cd
  is 'KorService2 lDongRegnCd 원문 코드';
comment on column public.tour_place_sources.l_dong_signgu_cd
  is 'KorService2 lDongSignguCd 원문 코드';
comment on column public.tour_place_sources.lcls_systm1
  is 'KorService2 lclsSystm1 원문 분류 코드';
comment on column public.tour_place_sources.lcls_systm2
  is 'KorService2 lclsSystm2 원문 분류 코드';
comment on column public.tour_place_sources.lcls_systm3
  is 'KorService2 lclsSystm3 원문 분류 코드';

create function public.validate_normalized_source_lineage()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  normalized_row jsonb := to_jsonb(new);
  normalized_run_id uuid;
  snapshot_row public.external_api_snapshots%rowtype;
begin
  if normalized_row ->> 'source_snapshot_id' is null then
    return new;
  end if;

  select snapshot.*
    into snapshot_row
  from public.external_api_snapshots snapshot
  where snapshot.id = (normalized_row ->> 'source_snapshot_id')::uuid
  for share;

  if not found then
    raise exception using
      errcode = '23503',
      message = 'normalized source snapshot does not exist';
  end if;

  if snapshot_row.parse_status not in ('parsed', 'tombstoned') then
    raise exception using
      errcode = '23514',
      message = 'normalized source row requires a parsed or tombstoned snapshot';
  end if;

  normalized_run_id := coalesce(
    (normalized_row ->> 'import_run_id')::uuid,
    (normalized_row ->> 'last_import_run_id')::uuid
  );

  if normalized_run_id is null
     or normalized_run_id <> snapshot_row.import_run_id then
    raise exception using
      errcode = '23514',
      message = 'normalized source row must use the snapshot import run';
  end if;

  if normalized_row ? 'source_provider'
     and normalized_row ->> 'source_provider'
         is distinct from snapshot_row.source_provider then
    raise exception using
      errcode = '23514',
      message = 'normalized source row provider must match its snapshot';
  end if;

  if normalized_row ? 'source_service'
     and normalized_row ->> 'source_service'
         is distinct from snapshot_row.source_service then
    raise exception using
      errcode = '23514',
      message = 'normalized source row service must match its snapshot';
  end if;

  if normalized_row ? 'source_operation'
     and normalized_row ->> 'source_operation'
         is distinct from snapshot_row.source_operation then
    raise exception using
      errcode = '23514',
      message = 'normalized source row operation must match its snapshot';
  end if;

  return new;
end;
$$;

create constraint trigger trg_tour_places_source_lineage
after insert or update on public.tour_places
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_tour_place_sources_source_lineage
after insert or update on public.tour_place_sources
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_details_source_lineage
after insert or update on public.place_details
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_detail_items_source_lineage
after insert or update on public.place_detail_items
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_hours_source_lineage
after insert or update on public.place_operating_hours
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_aliases_source_lineage
after insert or update on public.place_aliases
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_images_source_lineage
after insert or update on public.place_images
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_reference_codes_source_lineage
after insert or update on public.external_reference_codes
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_bus_stops_source_lineage
after insert or update on public.bus_stops
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_bus_routes_source_lineage
after insert or update on public.bus_routes
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_route_stops_source_lineage
after insert or update on public.route_stops
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_timetable_source_lineage
after insert or update on public.timetable_entries
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_weather_observations_source_lineage
after insert or update on public.weather_observations
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_weather_forecasts_source_lineage
after insert or update on public.weather_forecasts
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_bus_arrivals_source_lineage
after insert or update on public.bus_arrival_snapshots
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_mobility_routes_source_lineage
after insert or update on public.mobility_route_snapshots
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

alter table public.external_reference_codes
  add constraint ex_external_reference_codes_no_validity_overlap
    exclude using gist (
      source_provider with =,
      source_service with =,
      code_type with =,
      external_code with =,
      daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&
    );

alter table public.bus_routes
  add constraint uq_bus_routes_id_provider_city
    unique (id, source_provider, city_code);

alter table public.bus_stops
  add constraint uq_bus_stops_id_provider_city
    unique (id, source_provider, city_code);

alter table public.route_stops
  add column source_provider text,
  add column city_code text;

update public.route_stops route_stop
set source_provider = route.source_provider,
    city_code = route.city_code
from public.bus_routes route
where route.id = route_stop.route_id;

alter table public.route_stops
  alter column source_provider set not null,
  alter column city_code set not null,
  add constraint ck_route_stops_source_provider_nonblank
    check (btrim(source_provider) <> ''),
  add constraint ck_route_stops_city_code_nonblank
    check (btrim(city_code) <> ''),
  add constraint fk_route_stops_route_provider_city
    foreign key (route_id, source_provider, city_code)
    references public.bus_routes (id, source_provider, city_code)
    on delete cascade,
  add constraint fk_route_stops_stop_provider_city
    foreign key (stop_id, source_provider, city_code)
    references public.bus_stops (id, source_provider, city_code)
    on delete cascade,
  add constraint uq_route_stops_timetable_source_scope
    unique (
      route_id,
      direction_key,
      stop_id,
      source_provider,
      city_code
    );

create index idx_route_stops_route_provider_city
  on public.route_stops (route_id, source_provider, city_code);
create index idx_route_stops_stop_provider_city
  on public.route_stops (stop_id, source_provider, city_code);

alter table public.timetable_entries
  add column source_service text not null default 'legacy',
  add column city_code text,
  add constraint ck_timetable_source_service_nonblank
    check (btrim(source_service) <> '');

update public.timetable_entries timetable
set source_provider = route_stop.source_provider,
    city_code = route_stop.city_code
from public.route_stops route_stop
where route_stop.route_id = timetable.route_id
  and route_stop.direction_key = timetable.direction_key
  and route_stop.stop_id = timetable.stop_id;

alter table public.timetable_entries
  alter column city_code set not null,
  add constraint ck_timetable_city_code_nonblank
    check (btrim(city_code) <> ''),
  add constraint fk_timetable_route_stop_source_scope
    foreign key (
      route_id,
      direction_key,
      stop_id,
      source_provider,
      city_code
    )
    references public.route_stops (
      route_id,
      direction_key,
      stop_id,
      source_provider,
      city_code
    )
    on delete cascade;

drop index public.uq_timetable_provider_source_record_validity;

create unique index uq_timetable_source_scope_record_validity
  on public.timetable_entries (
    source_provider,
    source_service,
    city_code,
    source_record_key,
    valid_from
  );

create index idx_timetable_route_stop_source_scope
  on public.timetable_entries (
    route_id,
    direction_key,
    stop_id,
    source_provider,
    city_code
  );

alter table public.timetable_entries
  add constraint ex_timetable_source_scope_no_validity_overlap
    exclude using gist (
      source_provider with =,
      source_service with =,
      city_code with =,
      source_record_key with =,
      daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&
    );

alter table public.place_operating_hours
  add constraint ck_place_hours_last_entry_within_interval
    check (
      is_closed
      or last_entry_time is null
      or (
        not spans_next_day
        and last_entry_time >= open_time
        and last_entry_time <= close_time
      )
      or (
        spans_next_day
        and (last_entry_time >= open_time or last_entry_time <= close_time)
      )
    ),
  add constraint ex_place_hours_no_open_closed_conflict
    exclude using gist (
      place_id with =,
      day_of_week with =,
      daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&,
      is_closed with <>
    );
