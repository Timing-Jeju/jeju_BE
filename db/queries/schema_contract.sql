\set ON_ERROR_STOP on

set time zone 'Asia/Seoul';

do $$
declare
  required_tables text[] := array[
    'data_import_checkpoints',
    'external_api_snapshots',
    'tour_place_sources',
    'place_detail_items',
    'external_reference_codes',
    'api_idempotency_records',
    'tour_api_operations',
    'tour_api_operation_provenance',
    'tour_api_place_image_sweeps',
    'tour_api_place_image_sweep_pages'
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
      ('data_import_runs', 'owner_token'),
      ('data_import_runs', 'fencing_token'),
      ('api_idempotency_records', 'owner_sub'),
      ('api_idempotency_records', 'http_method'),
      ('api_idempotency_records', 'normalized_path'),
      ('api_idempotency_records', 'idempotency_key'),
      ('api_idempotency_records', 'request_hash'),
      ('api_idempotency_records', 'attempt_token'),
      ('api_idempotency_records', 'state'),
      ('api_idempotency_records', 'response_status'),
      ('api_idempotency_records', 'response_headers'),
      ('api_idempotency_records', 'response_body'),
      ('api_idempotency_records', 'lease_expires_at'),
      ('api_idempotency_records', 'expires_at'),
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
      ('external_api_snapshots', 'payload_size_bytes'),
      ('external_api_snapshots', 'redaction_version'),
      ('external_api_snapshots', 'payload_format'),
      ('external_api_snapshots', 'initial_parse_status'),
      ('external_api_snapshots', 'initial_error_code'),
      ('external_api_snapshots', 'purged_at'),
      ('tour_api_operations', 'operation_key'),
      ('tour_api_operations', 'source_provider'),
      ('tour_api_operations', 'source_service'),
      ('tour_api_operation_provenance', 'normalized_entity_type'),
      ('tour_api_operation_provenance', 'normalized_row_id'),
      ('tour_api_operation_provenance', 'operation_key'),
      ('tour_api_operation_provenance', 'content_type_id'),
      ('tour_api_operation_provenance', 'request_fingerprint'),
      ('tour_api_operation_provenance', 'source_snapshot_id'),
      ('tour_api_operation_provenance', 'import_run_id'),
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
      ('place_images', 'source_sweep_id'),
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
      and not exists (
        select 1
        from pg_catalog.pg_depend dependency_row
        where dependency_row.classid =
              'pg_catalog.pg_class'::pg_catalog.regclass
          and dependency_row.objid = table_row.oid
          and dependency_row.deptype = 'e'
      )
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
     or to_regprocedure('public.protect_import_run_write_lease()') is null
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
  ) or not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname = 'trg_data_import_runs_write_lease_immutable'
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

  if to_regprocedure(
       'public.protect_normalized_import_run()'
     ) is null then
    raise exception 'normalized import-run ledger removal guard is missing';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_trigger trigger_row
    where trigger_row.tgrelid = 'public.data_import_runs'::regclass
      and trigger_row.tgname =
          'trg_data_import_runs_protect_normalized_lineage'
      and not trigger_row.tgisinternal
      and trigger_row.tgtype = 11
  ) then
    raise exception 'normalized import-run ledger parent guard is missing';
  end if;

  select pg_catalog.regexp_replace(
      pg_catalog.lower(
        pg_catalog.pg_get_functiondef(
          to_regprocedure('public.protect_normalized_import_run()')
        )
      ),
      '\s+',
      ' ',
      'g'
    )
    into function_definition;

  with required_run_references(reference_name, function_fragment) as (
    values
      (
        'tour_places/import_run_id',
        'from public.tour_places where import_run_id = old.id'
      ),
      (
        'tour_place_sources/last_import_run_id',
        'from public.tour_place_sources where last_import_run_id = old.id'
      ),
      (
        'place_details/import_run_id',
        'from public.place_details where import_run_id = old.id'
      ),
      (
        'place_detail_items/import_run_id',
        'from public.place_detail_items where import_run_id = old.id'
      ),
      (
        'place_operating_hours/import_run_id',
        'from public.place_operating_hours where import_run_id = old.id'
      ),
      (
        'place_aliases/import_run_id',
        'from public.place_aliases where import_run_id = old.id'
      ),
      (
        'place_images/import_run_id',
        'from public.place_images where import_run_id = old.id'
      ),
      (
        'external_reference_codes/import_run_id',
        'from public.external_reference_codes where import_run_id = old.id'
      ),
      (
        'bus_stops/import_run_id',
        'from public.bus_stops where import_run_id = old.id'
      ),
      (
        'bus_routes/import_run_id',
        'from public.bus_routes where import_run_id = old.id'
      ),
      (
        'route_stops/import_run_id',
        'from public.route_stops where import_run_id = old.id'
      ),
      (
        'timetable_entries/import_run_id',
        'from public.timetable_entries where import_run_id = old.id'
      ),
      (
        'weather_observations/import_run_id',
        'from public.weather_observations where import_run_id = old.id'
      ),
      (
        'weather_forecasts/import_run_id',
        'from public.weather_forecasts where import_run_id = old.id'
      ),
      (
        'bus_arrival_snapshots/import_run_id',
        'from public.bus_arrival_snapshots where import_run_id = old.id'
      ),
      (
        'mobility_route_snapshots/import_run_id',
        'from public.mobility_route_snapshots where import_run_id = old.id'
      )
  )
  select pg_catalog.string_agg(
      required.reference_name,
      ', ' order by required.reference_name
    )
    into missing_objects
  from required_run_references required
  where pg_catalog.strpos(
          function_definition,
          required.function_fragment
        ) = 0;

  if missing_objects is not null
     or function_definition not like '%errcode = ''23503''%'
     or function_definition not like
        '%import run is still referenced by normalized data%'
     or function_definition like '%old.source_kind%'
     or function_definition like '%old.source_provider%' then
    raise exception
      'normalized import-run ledger guard is incomplete: %',
      coalesce(missing_objects, 'origin bypass or SQLSTATE');
  end if;

  with required_run_references(table_name, column_name) as (
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
  actual_run_references(table_name, column_name) as (
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
    where constraint_row.contype = 'f'
      and constraint_row.confrelid = 'public.data_import_runs'::regclass
      and child_schema.nspname = 'public'
      and child_table.relname in (
        select required.table_name
        from required_run_references required
      )
  ),
  mapping_differences(reference_name) as (
    select pg_catalog.format(
      'missing:%s/%s',
      required.table_name,
      required.column_name
    )
    from required_run_references required
    where not exists (
      select 1
      from actual_run_references actual
      where actual.table_name = required.table_name
        and actual.column_name = required.column_name
    )
    union all
    select pg_catalog.format(
      'unexpected:%s/%s',
      actual.table_name,
      actual.column_name
    )
    from actual_run_references actual
    where not exists (
      select 1
      from required_run_references required
      where required.table_name = actual.table_name
        and required.column_name = actual.column_name
    )
  )
  select pg_catalog.string_agg(
      difference.reference_name,
      ', ' order by difference.reference_name
    )
    into missing_objects
  from mapping_differences difference;

  if missing_objects is not null then
    raise exception
      'normalized import-run foreign-key mapping is not exact: %',
      missing_objects;
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

  select count(*) into invalid_count
  from information_schema.columns
  where table_schema = 'public'
    and table_name = 'compute_runs'
    and column_name in (
      'attempt_count', 'fencing_token', 'lease_owner', 'lease_expires_at',
      'heartbeat_at', 'next_attempt_at', 'result_source'
    );

  if invalid_count <> 7 then
    raise exception 'async run lease/fencing columns are incomplete';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.compute_runs'::regclass
      and constraint_row.conname = 'chk_compute_runs_execution_phase'
      and pg_get_constraintdef(constraint_row.oid) ilike '%facts_snapshot_at%'
      and pg_get_constraintdef(constraint_row.oid) ilike '%source_data_version%'
  ) then
    raise exception 'async run execution phase constraint is missing';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'compute_runs'
      and indexname = 'idx_compute_runs_worker_claim'
  ) or not exists (
    select 1 from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'compute_runs'
      and indexname = 'idx_compute_runs_worker_recovery'
  ) then
    raise exception 'async run claim/recovery indexes are missing';
  end if;

  select count(*) into invalid_count
  from public.tour_api_operations
  where operation_key in (
    'areaCode2', 'categoryCode2', 'areaBasedList2', 'locationBasedList2',
    'searchKeyword2', 'searchStay2', 'detailCommon2', 'detailIntro2', 'detailInfo2',
    'detailImage2', 'areaBasedSyncList2'
  ) and source_provider = 'tour-api' and source_service = 'KorService2' and active;

  if invalid_count <> 11 then
    raise exception 'TourAPI operation registry is incomplete';
  end if;

  if not exists (
    select 1 from public.data_import_checkpoints
    where source_provider='tour-api' and source_service='KorService2'
      and source_operation='areaBasedSyncList2' and scope_key='jeju'
      and checkpoint = '{"modifiedTime":"1970-01-01T00:00:00Z"}'::jsonb
      and version=0
  ) then
    raise exception 'areaBasedSyncList2 canonical checkpoint is missing';
  end if;

  if to_regclass('public.tour_api_place_image_sweeps') is null
     or to_regclass('public.tour_api_place_image_sweep_pages') is null
     or not exists (
       select 1 from pg_catalog.pg_constraint
       where conrelid = 'public.place_images'::regclass
         and conname = 'fk_place_images_sweep_page'
         and contype = 'f' and condeferrable
     ) then
    raise exception 'detailImage2 complete sweep page membership is missing';
  end if;

  if not (select relrowsecurity from pg_class where oid='public.tour_api_place_image_sweeps'::regclass)
     or not (select relrowsecurity from pg_class where oid='public.tour_api_place_image_sweep_pages'::regclass) then
    raise exception 'detailImage2 sweep RLS is disabled';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_trigger
    where tgrelid = 'public.tour_api_operation_provenance'::regclass
      and tgname = 'trg_tour_api_operation_provenance_validate'
      and not tgisinternal
  ) then
    raise exception 'TourAPI operation provenance lineage trigger is missing';
  end if;

  select pg_catalog.lower(pg_get_functiondef('public.validate_tour_api_operation_provenance()'::regprocedure))
    into function_definition;

  select string_agg(required_fragment, ', ' order by required_fragment)
    into missing_objects
  from unnest(array[
    'from public.external_reference_codes target',
    'from public.tour_places target',
    'from public.tour_place_sources target',
    'from public.place_aliases target',
    'from public.place_details target',
    'where target.place_id = new.normalized_row_id',
    'from public.place_detail_items target',
    'from public.place_images target',
    'tourapi operation provenance target does not exist',
    'for key share'
  ]) as required_fragment
  where strpos(function_definition, required_fragment) = 0;

  if missing_objects is not null then
    raise exception 'TourAPI operation provenance target guard is incomplete: %', missing_objects;
  end if;

  if (select count(*) from pg_catalog.pg_trigger
      where tgname like 'trg_%_provenance_delete' and not tgisinternal) <> 7
     or (select count(*) from pg_catalog.pg_trigger
      where tgname like 'trg_%_provenance_identifier_update' and not tgisinternal) <> 7
     or strpos(pg_catalog.lower(pg_get_functiondef(
          'public.protect_tour_api_provenance_target_delete()'::regprocedure
        )), 'next_target_id is not distinct from target_id') = 0
     or strpos(pg_catalog.lower(pg_get_functiondef(
          'public.protect_tour_api_provenance_target_delete()'::regprocedure
        )), 'tourapi operation provenance target is still referenced') = 0 then
    raise exception 'TourAPI provenance target identifier mutation guards are incomplete';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'public.tour_api_operation_provenance'::regclass
      and contype = 'u'
      and pg_get_constraintdef(oid) ilike '%normalized_entity_type%normalized_row_id%operation_key%source_snapshot_id%'
  ) then
    raise exception 'TourAPI operation provenance deduplication constraint is missing';
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'public.place_stop_links'::regclass
      and conname = 'ck_place_stop_links_lifecycle'
  ) then
    raise exception 'place_stop_links lifecycle check is missing';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'place_stop_links'
      and indexname = 'idx_place_stop_links_eligible'
      and indexdef ilike '%where%enabled%tombstoned_at%is null%'
  ) then
    raise exception 'place_stop_links eligible partial index is missing';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_class
    where oid = 'public.place_stop_links'::regclass and relrowsecurity
  ) or not exists (
    select 1 from pg_catalog.pg_class
    where oid = 'public.place_stop_link_scope_states'::regclass and relrowsecurity
  ) then
    raise exception 'place-stop link tables must enable RLS';
  end if;
end;
$$;

do $$
begin
  if not (to_regclass('public.idx_external_api_snapshots_retention_due') is not null)
     or not (to_regclass('public.idx_external_api_snapshots_purge') is null) then
    raise exception 'snapshot retention replacement index contract is invalid';
  end if;
end;
$$;

do $$
declare
  actual_columns text[];
  expected_columns constant text[] := array[
    'algorithm_version', 'attempt_count', 'base_schedule_version_id', 'completed_at',
    'contract_version', 'created_at', 'failure_code', 'fencing_token', 'heartbeat_at',
    'id', 'idempotency_key', 'lease_expires_at', 'lease_owner', 'next_attempt_at',
    'owner_user_id', 'request_hash', 'started_at', 'status', 'target_trip_day_id',
    'trip_plan_id', 'updated_at'
  ];
  invalid_count integer;
begin
  if to_regclass('public.schedule_revision_runs') is null then
    raise exception 'schedule_revision_runs foundation table is missing';
  end if;

  select array_agg(column_name::text order by column_name)
    into actual_columns
  from information_schema.columns
  where table_schema = 'public' and table_name = 'schedule_revision_runs';

  if actual_columns is distinct from expected_columns then
    raise exception 'schedule_revision_runs exact columns differ: %', actual_columns;
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_constraint
  where conrelid = 'public.schedule_revision_runs'::regclass
    and contype = 'f'
    and array_length(conkey, 1) = 2;
  if invalid_count <> 3 then
    raise exception 'schedule revision run composite lineage FK count differs: %', invalid_count;
  end if;

  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'public.schedule_revision_runs'::regclass
      and conname = 'uq_schedule_revision_runs_idempotency'
      and contype = 'u'
  ) or not exists (
    select 1 from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'schedule_revision_runs'
      and indexname = 'uq_schedule_revision_runs_active_scope'
      and indexdef ilike '%where%status%queued%running%'
  ) then
    raise exception 'schedule revision run idempotency or active-scope arbiter is missing';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_trigger
    where tgrelid = 'public.schedule_revision_runs'::regclass
      and tgname = 'trg_schedule_revision_runs_lifecycle'
      and not tgisinternal
  ) then
    raise exception 'schedule revision run lifecycle trigger is missing';
  end if;

  if not (select relrowsecurity from pg_class
          where oid = 'public.schedule_revision_runs'::regclass)
     or exists (
       select 1 from pg_catalog.pg_policies
       where schemaname = 'public' and tablename = 'schedule_revision_runs'
     ) then
    raise exception 'schedule revision run RLS/client policy boundary is invalid';
  end if;

  if exists (select 1 from pg_roles where rolname = 'anon')
     and has_table_privilege('anon', 'public.schedule_revision_runs', 'INSERT') then
    raise exception 'anon can write schedule revision runs';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated')
     and has_table_privilege('authenticated', 'public.schedule_revision_runs', 'INSERT') then
    raise exception 'authenticated can write schedule revision runs directly';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role')
     and (
       not has_table_privilege('service_role', 'public.schedule_revision_runs', 'SELECT')
       or not has_table_privilege('service_role', 'public.schedule_revision_runs', 'INSERT')
       or not has_table_privilege('service_role', 'public.schedule_revision_runs', 'UPDATE')
       or not has_table_privilege('service_role', 'public.schedule_revision_runs', 'DELETE')
       or has_table_privilege('service_role', 'public.schedule_revision_runs', 'TRUNCATE')
     ) then
    raise exception 'schedule revision run service boundary privileges are invalid';
  end if;
end;
$$;

do $$
declare
  parent_fk_count integer;
  parent_unique_count integer;
begin
  if to_regclass('public.compute_run_inputs') is null then
    raise exception 'compute_run_inputs table is missing';
  end if;
  select count(*) into parent_fk_count
  from pg_catalog.pg_constraint
  where conrelid = 'public.compute_run_inputs'::regclass
    and contype = 'f'
    and conname in (
      'fk_compute_run_inputs_compute_parent',
      'fk_compute_run_inputs_generation_parent',
      'fk_compute_run_inputs_revision_parent'
    );
  if parent_fk_count <> 3 then
    raise exception 'compute_run_inputs exact parent FK count differs: %', parent_fk_count;
  end if;
  select count(*) into parent_unique_count
  from pg_catalog.pg_indexes
  where schemaname = 'public'
    and tablename = 'compute_run_inputs'
    and indexname in (
      'uq_compute_run_inputs_compute_parent',
      'uq_compute_run_inputs_generation_parent',
      'uq_compute_run_inputs_revision_parent'
    )
    and indexdef ilike '%unique%where%is not null%';
  if parent_unique_count <> 3 then
    raise exception 'compute_run_inputs per-parent unique count differs: %', parent_unique_count;
  end if;
  if not (select relrowsecurity from pg_catalog.pg_class
          where oid = 'public.compute_run_inputs'::regclass)
     or exists (
       select 1 from pg_catalog.pg_policies
       where schemaname = 'public' and tablename = 'compute_run_inputs'
     ) then
    raise exception 'compute_run_inputs RLS/client policy boundary is invalid';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role')
     and (
       not has_table_privilege('service_role', 'public.compute_run_inputs', 'SELECT')
       or not has_table_privilege('service_role', 'public.compute_run_inputs', 'INSERT')
       or has_table_privilege('service_role', 'public.compute_run_inputs', 'UPDATE')
       or has_table_privilege('service_role', 'public.compute_run_inputs', 'DELETE')
       or has_table_privilege('service_role', 'public.compute_run_inputs', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.compute_run_inputs', 'REFERENCES')
       or has_table_privilege('service_role', 'public.compute_run_inputs', 'TRIGGER')
       or not has_function_privilege(
         'service_role',
         'public.shorten_compute_run_input_location_expiry(uuid,timestamptz)',
         'EXECUTE'
       )
     ) then
    raise exception 'compute_run_inputs service-only transition privileges are invalid';
  end if;
end;
$$;

do $$
declare
  owner_select_policy_count integer;
  client_write_policy_count integer;
begin
  if to_regclass('public.push_devices') is null
     or to_regclass('public.notification_preferences') is null then
    raise exception 'push notification tables are missing';
  end if;
  if not (select relrowsecurity from pg_catalog.pg_class
          where oid = 'public.push_devices'::regclass)
     or not (select relrowsecurity from pg_catalog.pg_class
             where oid = 'public.notification_preferences'::regclass) then
    raise exception 'push notification owner RLS is disabled';
  end if;
  select count(*) into owner_select_policy_count
  from pg_catalog.pg_policies
  where schemaname = 'public'
    and tablename in ('push_devices', 'notification_preferences')
    and roles = array['authenticated']::name[]
    and cmd = 'SELECT'
    and qual like '%auth.uid()%user_id%';
  if owner_select_policy_count <> 2 then
    raise exception 'push notification owner SELECT policy count differs: %',
      owner_select_policy_count;
  end if;
  select count(*) into client_write_policy_count
  from pg_catalog.pg_policies
  where schemaname = 'public'
    and tablename in ('push_devices', 'notification_preferences')
    and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL');
  if client_write_policy_count <> 0 then
    raise exception 'push notification client-write policy count differs: %',
      client_write_policy_count;
  end if;
  if not exists (
    select 1 from pg_catalog.pg_indexes
    where schemaname = 'public' and tablename = 'push_devices'
      and indexname = 'push_devices_active_token_fingerprint_key'
      and indexdef ilike '%unique%token_fingerprint%where%invalidated_at is null%'
  ) or not exists (
    select 1 from pg_catalog.pg_indexes
    where schemaname = 'public' and tablename = 'push_devices'
      and indexname = 'push_devices_user_active_idx'
      and indexdef ilike '%user_id%where%invalidated_at is null%permission_status%'
  ) then
    raise exception 'push device active uniqueness or owner lookup index is missing';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'anon')
     and has_any_column_privilege('anon', 'public.push_devices', 'SELECT') then
    raise exception 'anon can read push devices';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated')
     and (
       not has_column_privilege('authenticated', 'public.push_devices', 'device_id', 'SELECT')
       or has_column_privilege('authenticated', 'public.push_devices', 'token_ciphertext', 'SELECT')
       or has_column_privilege('authenticated', 'public.push_devices', 'token_fingerprint', 'SELECT')
       or has_table_privilege('authenticated', 'public.push_devices', 'INSERT')
       or has_table_privilege('authenticated', 'public.push_devices', 'UPDATE')
       or has_table_privilege('authenticated', 'public.push_devices', 'DELETE')
       or not has_table_privilege('authenticated', 'public.notification_preferences', 'SELECT')
       or has_table_privilege('authenticated', 'public.notification_preferences', 'INSERT')
       or has_table_privilege('authenticated', 'public.notification_preferences', 'UPDATE')
       or has_table_privilege('authenticated', 'public.notification_preferences', 'DELETE')
     ) then
    raise exception 'authenticated push notification privilege boundary is invalid';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role')
     and (
       not has_table_privilege('service_role', 'public.push_devices', 'SELECT')
       or not has_table_privilege('service_role', 'public.push_devices', 'INSERT')
       or not has_table_privilege('service_role', 'public.push_devices', 'UPDATE')
       or not has_table_privilege('service_role', 'public.push_devices', 'DELETE')
       or not has_table_privilege('service_role', 'public.notification_preferences', 'SELECT')
       or not has_table_privilege('service_role', 'public.notification_preferences', 'INSERT')
       or not has_table_privilege('service_role', 'public.notification_preferences', 'UPDATE')
       or not has_table_privilege('service_role', 'public.notification_preferences', 'DELETE')
       or has_table_privilege('service_role', 'public.push_devices', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.notification_preferences', 'TRUNCATE')
     ) then
    raise exception 'service role push notification DML/truncate boundary is invalid';
  end if;
end;
$$;

select 'schema_contract' as check_name, 'PASS' as result;
