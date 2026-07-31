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
      ('data_import_runs', 'idempotency_enforced'),
      ('data_import_runs', 'running_scope_enforced'),
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
      ('place_images', 'source_url_key'),
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

  with required_legacy_constraints(table_name, constraint_name) as (
    values
      ('route_stops', 'ck_route_stops_source_provider_nonblank'),
      ('mobility_route_snapshots', 'ck_mobility_request_hash_nonblank'),
      ('place_images', 'ck_place_images_image_url_nonblank'),
      ('bus_stops', 'ck_bus_stops_node_id_nonblank'),
      ('place_images', 'ck_place_images_source_key_lengths'),
      ('bus_stops', 'ck_bus_stops_source_key_lengths'),
      ('bus_routes', 'ck_bus_routes_source_key_lengths'),
      ('route_stops', 'ck_route_stops_source_key_lengths'),
      ('timetable_entries', 'ck_timetable_source_key_lengths'),
      ('mobility_route_snapshots', 'ck_mobility_source_key_lengths')
  )
  select string_agg(
      format('%s.%s', required.table_name, required.constraint_name),
      ', ' order by required.table_name, required.constraint_name
    )
    into missing_objects
  from required_legacy_constraints required
  left join pg_catalog.pg_constraint constraint_row
    on constraint_row.conrelid =
       ('public.' || required.table_name)::regclass
   and constraint_row.conname = required.constraint_name
   and constraint_row.contype = 'c'
   and not constraint_row.convalidated
  where constraint_row.oid is null;

  if missing_objects is not null then
    raise exception 'legacy-preserving NOT VALID constraints are missing: %', missing_objects;
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

  if exists (select 1 from pg_catalog.pg_roles where rolname = 'anon')
     and pg_catalog.has_function_privilege(
       'anon',
       to_regprocedure(
         'public.advance_data_import_checkpoint(text,text,text,text,bigint,jsonb,timestamp with time zone,uuid)'
       ),
       'EXECUTE'
     ) then
    raise exception 'anon must not execute the checkpoint compare-and-set function';
  end if;

  if exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated')
     and pg_catalog.has_function_privilege(
       'authenticated',
       to_regprocedure(
         'public.advance_data_import_checkpoint(text,text,text,text,bigint,jsonb,timestamp with time zone,uuid)'
       ),
       'EXECUTE'
     ) then
    raise exception 'authenticated must not execute the checkpoint compare-and-set function';
  end if;

  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role')
     and (
       not pg_catalog.has_function_privilege(
         'service_role',
         to_regprocedure(
           'public.advance_data_import_checkpoint(text,text,text,text,bigint,jsonb,timestamp with time zone,uuid)'
         ),
         'EXECUTE'
       )
       or pg_catalog.has_table_privilege(
         'service_role', 'public.data_import_checkpoints', 'UPDATE'
       )
       or pg_catalog.has_table_privilege(
         'service_role', 'public.data_import_checkpoints', 'DELETE'
       )
       or pg_catalog.has_table_privilege(
         'service_role', 'public.data_import_checkpoints', 'TRUNCATE'
       )
     ) then
    raise exception 'service_role checkpoint privileges must use only the compare-and-set function';
  end if;

  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role') then
    select pg_catalog.string_agg(
        pg_catalog.format('%I.%I', namespace_row.nspname, table_row.relname),
        ', ' order by table_row.relname
      )
      into missing_objects
    from pg_catalog.pg_class table_row
    join pg_catalog.pg_namespace namespace_row
      on namespace_row.oid = table_row.relnamespace
    where namespace_row.nspname = 'public'
      and table_row.relkind in ('r', 'p')
      and pg_catalog.has_table_privilege(
        'service_role',
        table_row.oid,
        'TRUNCATE'
      );

    if missing_objects is not null then
      raise exception
        'service_role must not have TRUNCATE on public application tables: %',
        missing_objects;
    end if;

    if not pg_catalog.has_table_privilege(
         'service_role', 'public.trip_days', 'SELECT'
       )
       or not pg_catalog.has_table_privilege(
         'service_role', 'public.trip_days', 'INSERT'
       )
       or not pg_catalog.has_table_privilege(
         'service_role', 'public.trip_days', 'UPDATE'
       )
       or not pg_catalog.has_table_privilege(
         'service_role', 'public.trip_days', 'DELETE'
       ) then
      raise exception 'service_role schedule DML privileges are incomplete';
    end if;
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
      and constraint_row.convalidated
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
  if function_definition not ilike '%lock_trip_plan_schedule_mutex%'
     or function_definition not ilike '%trip day cannot be added%' then
    raise exception 'sealed schedule day guard must serialize inserts and mutations';
  end if;

  if to_regprocedure('public.lock_trip_plan_schedule_mutex(uuid)') is null
     or to_regprocedure('public.protect_sealed_schedule_day()') is null
     or to_regprocedure('public.protect_sealed_trip_plan_dates()') is null
     or to_regprocedure('public.validate_schedule_version_base_lineage()') is null
     or to_regprocedure('public.require_new_day_scoped_result()') is null
     or to_regprocedure('public.protect_legacy_null_day_result_parent()') is null then
    raise exception 'sealed schedule day/date/base-lineage guards are missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.require_new_day_scoped_result()'))
    into function_definition;
  if function_definition not ilike '%legacy null-day weather lineage is immutable%'
     or function_definition not ilike '%legacy null-day recommendation lineage is immutable%' then
    raise exception 'legacy null-day child-lineage guard is incomplete';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.compute_runs'::regclass
      and trigger_row.tgname = 'trg_compute_runs_legacy_null_day_parent'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.trip_items'::regclass
      and trigger_row.tgname = 'trg_trip_items_legacy_null_day_parent'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.trip_legs'::regclass
      and trigger_row.tgname = 'trg_trip_legs_legacy_null_day_parent'
      and not trigger_row.tgisinternal
  ) then
    raise exception 'legacy null-day parent-lineage triggers are missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_trip_calendar_child()'))
    into function_definition;
  if function_definition not ilike '%lock_trip_plan_schedule_mutex%' then
    raise exception 'calendar child validation must use its trip-plan mutex';
  end if;

  select pg_get_functiondef(to_regprocedure('public.lock_trip_plan_schedule_mutex(uuid)'))
    into function_definition;
  if function_definition not ilike '%' || 'UP' || 'DATE public.trip_plans%'
     or function_definition not ilike '%SET updated_at = p.updated_at%' then
    raise exception 'trip-plan schedule mutex must use an MVCC write fence';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.convalidated
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(compute_run_id, trip_plan_id, schedule_version_id, trip_day_id\).*compute_runs'
  ) or not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.convalidated
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
      and constraint_row.convalidated
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(compute_run_id, trip_plan_id, schedule_version_id, trip_day_id\).*compute_runs'
  ) or not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.recommendation_candidates'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.convalidated
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(base_item_id, schedule_version_id, trip_plan_id, trip_day_id\).*trip_items'
  ) then
    raise exception 'recommendation compute/item day composite foreign keys are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.convalidated
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(compute_run_id, trip_plan_id, schedule_version_id\).*compute_runs'
  ) or not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.recommendation_candidates'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.convalidated
      and pg_get_constraintdef(constraint_row.oid) ~*
          'FOREIGN KEY \(compute_run_id, trip_plan_id, schedule_version_id\).*compute_runs'
  ) then
    raise exception 'legacy nullable-day parent foreign keys must remain validated';
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

  if not exists (
    select 1
    from pg_catalog.pg_indexes index_row
    where index_row.schemaname = 'public'
      and index_row.indexname = 'uq_data_import_runs_idempotency'
      and index_row.indexdef ilike '%source_provider%'
      and index_row.indexdef ilike '%source_service%'
      and index_row.indexdef ilike '%source_operation%'
      and index_row.indexdef ilike '%scope_key%'
      and index_row.indexdef ilike '%idempotency_enforced%'
      and index_row.indexdef not ilike '%source_name%'
  ) or not exists (
    select 1
    from pg_catalog.pg_indexes index_row
    where index_row.schemaname = 'public'
      and index_row.indexname = 'uq_data_import_runs_running_scope'
      and index_row.indexdef ilike '%source_provider%'
      and index_row.indexdef ilike '%source_service%'
      and index_row.indexdef ilike '%source_operation%'
      and index_row.indexdef ilike '%scope_key%'
      and index_row.indexdef ilike '%running_scope_enforced%'
      and index_row.indexdef not ilike '%source_name%'
  ) then
    raise exception 'import-run canonical provider/service scope indexes are missing';
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

  if to_regprocedure('public.validate_import_run_nonblank_fields()') is null
     or to_regprocedure('public.validate_import_run_json_objects()') is null
     or to_regprocedure('public.validate_import_run_source_key_lengths()') is null
     or to_regprocedure('public.protect_import_run_idempotency()') is null
     or to_regprocedure('public.protect_grandfathered_idempotency_arbiter()') is null
     or to_regprocedure('public.protect_import_run_running_scope()') is null
     or to_regprocedure('public.protect_import_run_source_scope()') is null
     or to_regprocedure('public.validate_external_snapshot_import_scope()') is null then
    raise exception 'exact import-run source-scope guards are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_nonblank_insert'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_protect_idempotency_arbiter'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_running_scope_guard'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_idempotency_guard'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_nonblank_update'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_json_insert'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_json_update'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_source_key_insert'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_source_key_update'
      and not trigger_row.tgisinternal
  ) then
    raise exception 'import-run source-key transition guards are missing';
  end if;

  if to_regprocedure('public.validate_checkpoint_succeeded_run()') is null
     or to_regprocedure('public.protect_checkpoint_progress()') is null
     or to_regprocedure(
       'public.advance_data_import_checkpoint(text,text,text,text,bigint,jsonb,timestamp with time zone,uuid)'
     ) is null
     or to_regprocedure('public.prevent_checkpoint_reset()') is null
     or to_regprocedure('public.protect_checkpoint_succeeded_run()') is null
     or to_regprocedure('public.normalized_lineage_is_optional(text,jsonb)') is null
     or to_regprocedure('public.validate_normalized_source_lineage()') is null
     or to_regprocedure('public.protect_external_snapshot_identity()') is null
     or to_regprocedure('public.prevent_duplicate_place_image_source()') is null
     or to_regprocedure('public.source_identity_digest(text[])') is null
     or to_regprocedure('public.validate_route_stop_source_scope()') is null
     or to_regprocedure('public.validate_timetable_source_scope()') is null
     or to_regprocedure('public.validate_place_hours_cross_day_overlap()') is null then
    raise exception 'ingestion lineage validation functions are missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_checkpoint_succeeded_run()'))
    into function_definition;
  if function_definition not ilike '%FOR SHARE%' then
    raise exception 'checkpoint run validation must lock the referenced run';
  end if;

  select pg_get_functiondef(to_regprocedure('public.protect_checkpoint_progress()'))
    into function_definition;
  if function_definition not ilike '%old.version + 1%'
     or function_definition not ilike '%older succeeded import run%' then
    raise exception 'checkpoint compare-and-set/latest-run guard is missing';
  end if;

  select pg_get_functiondef(to_regprocedure(
    'public.advance_data_import_checkpoint(text,text,text,text,bigint,jsonb,timestamp with time zone,uuid)'
  )) into function_definition;
  if function_definition not ilike '%WHERE%version = p_expected_version%'
     or function_definition not ilike '%version = checkpoint_row.version + 1%'
     or function_definition not ilike '%checkpoint compare-and-set expected version is stale%' then
    raise exception 'checkpoint atomic compare-and-set function is incomplete';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_checkpoints'::regclass
      and trigger_row.tgname = 'trg_data_import_checkpoints_no_delete'
      and not trigger_row.tgisinternal
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_checkpoints'::regclass
      and trigger_row.tgname = 'trg_data_import_checkpoints_no_truncate'
      and not trigger_row.tgisinternal
  ) then
    raise exception 'checkpoint delete/truncate reset guards are missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_protect_checkpoint'
      and not trigger_row.tgisinternal
  ) then
    raise exception 'checkpoint referenced-run status guard is missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_normalized_source_lineage()'))
    into function_definition;
  if function_definition not ilike '%parse_status%parsed%tombstoned%' then
    raise exception 'normalized lineage must require parsed or tombstoned snapshots';
  end if;
  if function_definition not ilike '%FOR SHARE%' then
    raise exception 'normalized lineage must lock its source snapshot';
  end if;
  if function_definition not ilike '%requires a source snapshot and import run%'
     or function_definition not ilike '%TG_OP = ''INSERT''%'
     or function_definition not ilike '%legacy lineage-free row content is immutable%'
     or function_definition not ilike '%old_lineage_optional AND lineage_optional%'
     or function_definition not ilike '%old_origin_is_external%'
     or function_definition not ilike '%normalized_lineage_is_optional%'
     or function_definition not ilike '%normalized_row - ARRAY[''updated_at''%'
     or function_definition not ilike '%snapshot purge may clear only the source pointer%'
     or function_definition ilike '%lineage_optional := tg_table_name = ''tour_places'' OR%' then
    raise exception 'new normalized external rows must require full source lineage';
  end if;

  select pg_get_functiondef(to_regprocedure('public.protect_external_snapshot_identity()'))
    into function_definition;
  if function_definition not ilike '%cannot return to an unparsed status%'
     or function_definition not ilike '%audit payload is immutable%' then
    raise exception 'snapshot parse regression/audit immutability guard is missing';
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

  select pg_get_functiondef(to_regprocedure('public.validate_route_stop_source_scope()'))
    into function_definition;
  if function_definition not ilike '%FOR KEY SHARE%'
     or function_definition not ilike '%bus_routes%'
     or function_definition not ilike '%bus_stops%' then
    raise exception 'route-stop exact provider/city guard is incomplete';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_timetable_source_scope()'))
    into function_definition;
  if function_definition not ilike '%FOR KEY SHARE%'
     or function_definition not ilike '%legacy timetable source identity is immutable%'
     or function_definition not ilike '%valid route stop%' then
    raise exception 'timetable exact source-scope guard is incomplete';
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

  select pg_get_functiondef(to_regprocedure('public.prevent_duplicate_place_image_source()'))
    into function_definition;
  if function_definition not ilike '%pg_advisory_xact_lock%'
     or function_definition not ilike '%image_url%'
     or function_definition not ilike '%source_identity_digest%'
     or function_definition not ilike '%digest collision%' then
    raise exception 'place-image provider URL idempotency guard is missing';
  end if;

  if exists (
    select 1
    from information_schema.routine_privileges privilege_row
    where privilege_row.specific_schema = 'public'
      and privilege_row.routine_name = 'source_identity_digest'
      and privilege_row.privilege_type = 'EXECUTE'
      and privilege_row.grantee = 'PUBLIC'
  ) or (
    exists (select 1 from pg_catalog.pg_roles where rolname = 'anon')
    and pg_catalog.has_function_privilege(
      'anon',
      'public.source_identity_digest(text[])',
      'EXECUTE'
    )
  ) or (
    exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated')
    and pg_catalog.has_function_privilege(
      'authenticated',
      'public.source_identity_digest(text[])',
      'EXECUTE'
    )
  ) or (
    exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role')
    and not pg_catalog.has_function_privilege(
      'service_role',
      'public.source_identity_digest(text[])',
      'EXECUTE'
    )
  ) then
    raise exception 'place-image digest helper permissions are unsafe';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.place_images'::regclass
      and constraint_row.contype = 'u'
      and constraint_row.conname = 'uq_place_images_source_url_key'
      and pg_get_constraintdef(constraint_row.oid) ilike '%source_url_key%'
  ) then
    raise exception 'place-image source URL ON CONFLICT key is missing';
  end if;

  select pg_get_functiondef(to_regprocedure('public.validate_place_hours_cross_day_overlap()'))
    into function_definition;
  if function_definition not ilike '%previous overnight service day%'
     or function_definition not ilike '%next service day%'
     or function_definition not ilike '%' || 'UP' || 'DATE public.tour_places%'
     or function_definition not ilike '%SET updated_at = updated_at%' then
    raise exception 'operating-hours cross-day overlap guard is missing';
  end if;
end;
$$;

select 'schema_contract' as check_name, 'PASS' as result;
