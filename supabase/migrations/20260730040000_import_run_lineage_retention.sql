-- 원문 snapshot 보존기간이 끝나도 외부 정규화 행의 마지막 import run은
-- 감사 계보로 남아야 한다. 부모 DELETE 전에 16개 read model 참조를 확인해
-- ON DELETE SET NULL FK가 외부 계보를 지우는 경로만 차단한다.
do $$
declare
  missing_references text;
begin
  with required_references(table_name, run_column) as (
    values
      ('tour_places', 'import_run_id'),
      ('tour_place_sources', 'last_import_run_id'),
      ('place_details', 'import_run_id'),
      ('place_detail_items', 'import_run_id'),
      ('place_operating_hours', 'import_run_id'),
      ('place_aliases', 'import_run_id'),
      ('place_images', 'import_run_id'),
      ('external_reference_codes', 'import_run_id'),
      ('bus_stops', 'import_run_id'),
      ('bus_routes', 'import_run_id'),
      ('route_stops', 'import_run_id'),
      ('timetable_entries', 'import_run_id'),
      ('weather_observations', 'import_run_id'),
      ('weather_forecasts', 'import_run_id'),
      ('bus_arrival_snapshots', 'import_run_id'),
      ('mobility_route_snapshots', 'import_run_id')
  )
  select pg_catalog.string_agg(
      pg_catalog.format('%s/%s', required.table_name, required.run_column),
      ', ' order by required.table_name
    )
    into missing_references
  from required_references required
  where not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    join pg_catalog.pg_attribute column_row
      on column_row.attrelid = constraint_row.conrelid
     and column_row.attname = required.run_column
    where constraint_row.conrelid = pg_catalog.to_regclass(
            'public.' || required.table_name
          )
      and constraint_row.confrelid = 'public.data_import_runs'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.conkey =
          array[column_row.attnum]::smallint[]
  );

  if missing_references is not null then
    raise exception using
      errcode = '23514',
      message = 'normalized import-run reference audit failed',
      detail = pg_catalog.format(
        'missing foreign-key references: %s',
        missing_references
      );
  end if;
end;
$$;

create function public.protect_external_normalized_import_run()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  -- fixture/admin 계보는 삭제 후 optional/manual 행으로 계속 관리할 수 있다.
  -- 둘 중 하나라도 예외 marker가 아니면 기존 lineage 판정과 같이 external이다.
  if old.source_kind in ('fixture', 'admin_upload')
     and old.source_provider in ('fixture', 'admin_upload') then
    return old;
  end if;

  if exists (
       select 1
       from public.tour_places
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.tour_place_sources
       where last_import_run_id = old.id
     )
     or exists (
       select 1
       from public.place_details
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.place_detail_items
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.place_operating_hours
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.place_aliases
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.place_images
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.external_reference_codes
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.bus_stops
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.bus_routes
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.route_stops
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.timetable_entries
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.weather_observations
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.weather_forecasts
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.bus_arrival_snapshots
       where import_run_id = old.id
     )
     or exists (
       select 1
       from public.mobility_route_snapshots
       where import_run_id = old.id
     ) then
    raise exception using
      errcode = '23503',
      message = 'external import run is still referenced by normalized data',
      detail = pg_catalog.format('import_run_id=%s', old.id),
      hint = 'Retain the import run while normalized rows reference it.';
  end if;

  return old;
end;
$$;

create trigger trg_data_import_runs_protect_normalized_lineage
before delete on public.data_import_runs
for each row
execute function public.protect_external_normalized_import_run();
