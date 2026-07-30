\set ON_ERROR_STOP on

set time zone 'Asia/Seoul';

do $$
declare
  required_tables text[] := array[
    'data_import_checkpoints',
    'external_api_snapshots',
    'tour_place_sources',
    'place_detail_items',
    'external_reference_codes'
  ];
  missing_objects text;
  invalid_count integer;
  function_definition text;
begin
  select string_agg(table_name, ', ' order by table_name)
    into missing_objects
  from unnest(required_tables) as table_name
  where to_regclass('public.' || table_name) is null;

  if missing_objects is not null then
    raise exception 'database hardening tables are missing: %', missing_objects;
  end if;

  with required_columns(table_name, column_name) as (
    values
      ('data_import_runs', 'parser_version'),
      ('data_import_runs', 'schema_version'),
      ('data_import_runs', 'sync_mode'),
      ('data_import_runs', 'scope_key'),
      ('data_import_runs', 'idempotency_key'),
      ('data_import_runs', 'parent_run_id'),
      ('data_import_runs', 'checkpoint_before'),
      ('data_import_runs', 'checkpoint_after'),
      ('data_import_runs', 'retry_count'),
      ('data_import_runs', 'fetched_count'),
      ('data_import_runs', 'inserted_count'),
      ('data_import_runs', 'updated_count'),
      ('data_import_runs', 'skipped_count'),
      ('data_import_runs', 'rejected_count'),
      ('data_import_runs', 'deleted_count'),
      ('data_import_runs', 'staled_count'),
      ('data_import_runs', 'source_provider'),
      ('data_import_runs', 'source_service'),
      ('data_import_checkpoints', 'source_provider'),
      ('data_import_checkpoints', 'source_service'),
      ('data_import_checkpoints', 'source_operation'),
      ('data_import_checkpoints', 'scope_key'),
      ('data_import_checkpoints', 'checkpoint'),
      ('data_import_checkpoints', 'last_succeeded_run_id'),
      ('external_api_snapshots', 'import_run_id'),
      ('external_api_snapshots', 'source_provider'),
      ('external_api_snapshots', 'source_service'),
      ('external_api_snapshots', 'source_operation'),
      ('external_api_snapshots', 'scope_key'),
      ('external_api_snapshots', 'external_record_id'),
      ('external_api_snapshots', 'request_hash'),
      ('external_api_snapshots', 'page_key'),
      ('external_api_snapshots', 'parser_version'),
      ('external_api_snapshots', 'payload_hash'),
      ('external_api_snapshots', 'request_metadata_redacted'),
      ('external_api_snapshots', 'raw_payload'),
      ('external_api_snapshots', 'parse_status'),
      ('external_api_snapshots', 'error_code'),
      ('external_api_snapshots', 'error_message'),
      ('external_api_snapshots', 'fetched_at'),
      ('external_api_snapshots', 'parsed_at'),
      ('external_api_snapshots', 'purge_after'),
      ('tour_place_sources', 'source_snapshot_id'),
      ('tour_place_sources', 'last_import_run_id'),
      ('tour_place_sources', 'l_dong_regn_cd'),
      ('tour_place_sources', 'l_dong_signgu_cd'),
      ('tour_place_sources', 'lcls_systm1'),
      ('tour_place_sources', 'lcls_systm2'),
      ('tour_place_sources', 'lcls_systm3'),
      ('place_detail_items', 'source_snapshot_id'),
      ('place_detail_items', 'import_run_id'),
      ('place_details', 'source_snapshot_id'),
      ('place_details', 'import_run_id'),
      ('place_operating_hours', 'interval_no'),
      ('place_operating_hours', 'valid_from'),
      ('place_operating_hours', 'valid_to'),
      ('place_operating_hours', 'spans_next_day'),
      ('place_operating_hours', 'source_snapshot_id'),
      ('place_operating_hours', 'import_run_id'),
      ('place_aliases', 'source_snapshot_id'),
      ('place_aliases', 'import_run_id'),
      ('place_images', 'source_image_id'),
      ('place_images', 'copyright_code'),
      ('place_images', 'copyright_owner'),
      ('place_images', 'source_snapshot_id'),
      ('place_images', 'import_run_id'),
      ('bus_stops', 'city_code'),
      ('bus_stops', 'source_snapshot_id'),
      ('bus_routes', 'city_code'),
      ('bus_routes', 'source_snapshot_id'),
      ('route_stops', 'source_snapshot_id'),
      ('route_stops', 'source_provider'),
      ('route_stops', 'city_code'),
      ('timetable_entries', 'source_record_key'),
      ('timetable_entries', 'source_snapshot_id'),
      ('timetable_entries', 'source_service'),
      ('timetable_entries', 'city_code'),
      ('weather_observations', 'source_snapshot_id'),
      ('weather_forecasts', 'source_snapshot_id'),
      ('bus_arrival_snapshots', 'source_snapshot_id'),
      ('mobility_route_snapshots', 'source_snapshot_id'),
      ('mobility_route_snapshots', 'import_run_id')
  )
  select string_agg(format('%s.%s', r.table_name, r.column_name), ', '
                    order by r.table_name, r.column_name)
    into missing_objects
  from required_columns r
  left join information_schema.columns c
    on c.table_schema = 'public'
   and c.table_name = r.table_name
   and c.column_name = r.column_name
  where c.column_name is null;

  if missing_objects is not null then
    raise exception 'database hardening columns are missing: %', missing_objects;
  end if;

  select string_agg(c.relname, ', ' order by c.relname)
    into missing_objects
  from pg_catalog.pg_class c
  join pg_catalog.pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public'
    and c.relname = any(required_tables)
    and not c.relrowsecurity;

  if missing_objects is not null then
    raise exception 'RLS is disabled for ingestion tables: %', missing_objects;
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_policies
  where schemaname = 'public'
    and tablename = any(required_tables);

  if invalid_count <> 0 then
    raise exception 'ingestion internals must not expose client RLS policies: %', invalid_count;
  end if;

  select count(*) into invalid_count
  from information_schema.role_table_grants
  where table_schema = 'public'
    and table_name = any(required_tables)
    and grantee in ('anon', 'authenticated');

  if invalid_count <> 0 then
    raise exception 'ingestion internals must not grant direct client access: %', invalid_count;
  end if;

  select string_agg(
      format('%s.%s', constraint_row.conrelid::regclass, constraint_row.conname),
      ', ' order by constraint_row.conrelid::regclass::text, constraint_row.conname
    )
    into missing_objects
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.contype = 'f'
    and constraint_row.connamespace = 'public'::regnamespace
    and not exists (
      select 1
      from pg_catalog.pg_index index_row
      where index_row.indrelid = constraint_row.conrelid
        and (index_row.indkey::smallint[])[0:cardinality(constraint_row.conkey)-1]
            = constraint_row.conkey
    );

  if missing_objects is not null then
    raise exception 'foreign keys without a leading index: %', missing_objects;
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.timetable_entries'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(route_id, direction_key, stop_id\).*route_stops'
  ) then
    raise exception 'timetable route/direction/stop relation foreign key is missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_items'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(trip_day_id, trip_plan_id\).*trip_days'
  ) then
    raise exception 'trip item cross-trip day ownership foreign key is missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(trip_day_id, trip_plan_id\).*trip_days'
  ) then
    raise exception 'trip weather impact cross-trip day ownership foreign key is missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.recommendation_candidates'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(trip_day_id, trip_plan_id\).*trip_days'
  ) then
    raise exception 'recommendation cross-trip day ownership foreign key is missing';
  end if;

  if to_regprocedure('public.assert_schedule_day_coverage(uuid,uuid)') is null then
    raise exception 'assert_schedule_day_coverage(uuid,uuid) is missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_schedule_version_sealing()'))
    into function_definition;
  if function_definition not ilike '%assert_schedule_version_sealable%'
     or function_definition not ilike '%assert_schedule_day_coverage%'
     or function_definition not ilike '%assert_schedule_day_item_windows%' then
    raise exception 'schedule sealing must validate content and complete day coverage';
  end if;

  select pg_get_functiondef(to_regprocedure('public.assert_schedule_day_item_windows(uuid,uuid)'))
    into function_definition;
  if function_definition not ilike '%planned_start_at%date%trip_date%'
     or function_definition not ilike '%planned_end_at%date%trip_date%' then
    raise exception 'schedule item window must keep both endpoints on its trip day';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_schedule_version_base_lineage()'))
    into function_definition;
  if function_definition not ilike '%version_no is distinct from old.version_no%'
     or function_definition not ilike '%version number is immutable%' then
    raise exception 'schedule version number immutability guard is missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.require_draft_schedule_version()'))
    into function_definition;
  if function_definition not ilike '%old_version_id%'
     or function_definition not ilike '%new_version_id%'
     or function_definition not ilike '%for share%' then
    raise exception 'schedule content move guard must lock and validate old and new versions';
  end if;

  select pg_get_functiondef(to_regprocedure('public.protect_sealed_schedule_day()'))
    into function_definition;
  if function_definition not ilike '%for share%'
     or function_definition not ilike '%trip day cannot be added%' then
    raise exception 'sealed schedule day guard must serialize inserts and mutations';
  end if;

  if to_regprocedure('public.protect_sealed_schedule_day()') is null
     or to_regprocedure('public.protect_sealed_trip_plan_dates()') is null
     or to_regprocedure('public.validate_schedule_version_base_lineage()') is null then
    raise exception 'sealed schedule day/date/base-lineage guards are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(compute_run_id, trip_plan_id, schedule_version_id, trip_day_id\).*compute_runs'
  ) or not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(trip_item_id, schedule_version_id, trip_plan_id, trip_day_id\).*trip_items'
  ) then
    raise exception 'weather impact compute/item day composite foreign keys are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.recommendation_candidates'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(compute_run_id, trip_plan_id, schedule_version_id, trip_day_id\).*compute_runs'
  ) or not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.recommendation_candidates'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(base_item_id, schedule_version_id, trip_plan_id, trip_day_id\).*trip_items'
  ) then
    raise exception 'recommendation compute/item day composite foreign keys are missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.data_import_runs'::regclass
    and constraint_row.contype = 'c'
    and pg_get_constraintdef(constraint_row.oid) ilike '%partial%'
    and pg_get_constraintdef(constraint_row.oid) ilike '%cancelled%';

  if invalid_count = 0 then
    raise exception 'data import terminal-state constraint is missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.external_api_snapshots'::regclass
    and constraint_row.contype = 'c'
    and pg_get_constraintdef(constraint_row.oid) ilike '%payload_hash%';

  if invalid_count = 0 then
    raise exception 'snapshot payload hash constraint is missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.external_api_snapshots'::regclass
    and constraint_row.contype = 'c'
    and pg_get_constraintdef(constraint_row.oid) ilike '%parse_status%';

  if invalid_count < 2 then
    raise exception 'snapshot parse-state constraints are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.external_api_snapshots'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(import_run_id, source_provider, source_service, source_operation, scope_key\).*data_import_runs'
  ) then
    raise exception 'snapshot provider/service import-run foreign key is missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.data_import_checkpoints'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(last_succeeded_run_id, source_provider, source_service, source_operation, scope_key\).*data_import_runs'
  ) then
    raise exception 'checkpoint import-run scope foreign key is missing';
  end if;

  if to_regprocedure('public.validate_checkpoint_succeeded_run()') is null
     or to_regprocedure('public.validate_normalized_source_lineage()') is null
     or to_regprocedure('public.protect_external_snapshot_identity()') is null then
    raise exception 'ingestion lineage validation functions are missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_checkpoint_succeeded_run()'))
    into function_definition;
  if function_definition not ilike '%FOR SHARE%' then
    raise exception 'checkpoint run validation must lock the referenced run';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_normalized_source_lineage()'))
    into function_definition;
  if function_definition not ilike '%parse_status%parsed%tombstoned%' then
    raise exception 'normalized lineage must require parsed or tombstoned snapshots';
  end if;
  if function_definition not ilike '%FOR SHARE%' then
    raise exception 'normalized lineage must lock its source snapshot';
  end if;

  select pg_get_functiondef(to_regprocedure('public.protect_external_snapshot_identity()'))
    into function_definition;
  if function_definition not ilike '%cannot return to an unparsed status%' then
    raise exception 'snapshot parse status regression guard is missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_trigger trigger_row
  where trigger_row.tgfoid =
        to_regprocedure('public.validate_normalized_source_lineage()')::oid
    and not trigger_row.tgisinternal;

  if invalid_count < 16 then
    raise exception 'normalized lineage triggers are missing: %', invalid_count;
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.external_reference_codes'::regclass
    and constraint_row.contype = 'x'
    and pg_get_constraintdef(constraint_row.oid) ilike '%daterange%';

  if invalid_count = 0 then
    raise exception 'reference-code validity overlap exclusion is missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.timetable_entries'::regclass
    and constraint_row.contype = 'x'
    and pg_get_constraintdef(constraint_row.oid) ilike '%source_service%'
    and pg_get_constraintdef(constraint_row.oid) ilike '%city_code%'
    and pg_get_constraintdef(constraint_row.oid) ilike '%daterange%';

  if invalid_count = 0 then
    raise exception 'timetable scoped validity overlap exclusion is missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.route_stops'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(route_id, source_provider, city_code\).*bus_routes'
  ) or not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.route_stops'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(stop_id, source_provider, city_code\).*bus_stops'
  ) then
    raise exception 'route-stop provider/city scope foreign keys are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.timetable_entries'::regclass
      and constraint_row.contype = 'f'
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(route_id, direction_key, stop_id, source_provider, city_code\).*route_stops'
  ) then
    raise exception 'timetable route-stop provider/city scope foreign key is missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.place_operating_hours'::regclass
    and constraint_row.contype = 'x'
    and pg_get_constraintdef(constraint_row.oid) ilike '%is_closed WITH <>%';

  if invalid_count = 0 then
    raise exception 'operating-hours open/closed exclusion is missing';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint constraint_row
  where constraint_row.conrelid = 'public.place_operating_hours'::regclass
    and constraint_row.contype = 'c'
    and pg_get_constraintdef(constraint_row.oid) ilike '%last_entry_time%'
    and pg_get_constraintdef(constraint_row.oid) ilike '%spans_next_day%';

  if invalid_count = 0 then
    raise exception 'operating-hours last-entry interval constraint is missing';
  end if;
end;
$$;

select 'schema_contract' as check_name, 'PASS' as result;
