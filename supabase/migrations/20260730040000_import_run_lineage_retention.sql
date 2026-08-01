-- data_import_runs는 외부·fixture·관리자 적재를 모두 기록하는 provenance
-- ledger다. 부모 DELETE 전에 16개 read model 참조를 확인해 origin이나 FK의
-- 삭제 동작과 무관하게 정규화 계보가 사라지는 경로를 차단한다.
do $$
declare
  missing_references text;
  unexpected_references text;
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
  ),
  actual_references(table_name, run_column) as (
    select
      child_table.relname,
      child_column.attname
    from pg_catalog.pg_constraint constraint_row
    join pg_catalog.pg_class child_table
      on child_table.oid = constraint_row.conrelid
    join pg_catalog.pg_namespace child_schema
      on child_schema.oid = child_table.relnamespace
    join pg_catalog.pg_attribute child_column
      on child_column.attrelid = constraint_row.conrelid
     and constraint_row.conkey =
         array[child_column.attnum]::smallint[]
    where constraint_row.confrelid = 'public.data_import_runs'::regclass
      and constraint_row.contype = 'f'
      and child_schema.nspname = 'public'
      and child_table.relname in (
        select required.table_name
        from required_references required
      )
  )
  select
    (
      select pg_catalog.string_agg(
          pg_catalog.format(
            '%s/%s',
            required.table_name,
            required.run_column
          ),
          ', ' order by required.table_name, required.run_column
        )
      from required_references required
      where not exists (
        select 1
        from actual_references actual
        where actual.table_name = required.table_name
          and actual.run_column = required.run_column
      )
    ),
    (
      select pg_catalog.string_agg(
          pg_catalog.format('%s/%s', actual.table_name, actual.run_column),
          ', ' order by actual.table_name, actual.run_column
        )
      from actual_references actual
      where not exists (
        select 1
        from required_references required
        where required.table_name = actual.table_name
          and required.run_column = actual.run_column
      )
    )
    into missing_references, unexpected_references;

  if missing_references is not null
     or unexpected_references is not null then
    raise exception using
      errcode = '23514',
      message = 'normalized import-run foreign-key mapping audit failed',
      detail = pg_catalog.format(
        'missing foreign-key references: %s; unexpected foreign-key references: %s',
        coalesce(missing_references, 'none'),
        coalesce(unexpected_references, 'none')
      );
  end if;
end;
$$;

create function public.protect_normalized_import_run()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  -- BEFORE DELETE는 FK referential action보다 먼저 실행된다. 따라서 기존
  -- 8개 NO ACTION과 8개 SET NULL의 confdeltype 차이에 의존하지 않고,
  -- 삭제 전의 live reference를 하나의 ledger 정책으로 검사할 수 있다.
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
      message = 'import run is still referenced by normalized data',
      detail = pg_catalog.format('import_run_id=%s', old.id),
      hint = 'Retain every provenance run while normalized rows reference it.';
  end if;

  return old;
end;
$$;

create trigger trg_data_import_runs_protect_normalized_lineage
before delete on public.data_import_runs
for each row
execute function public.protect_normalized_import_run();
