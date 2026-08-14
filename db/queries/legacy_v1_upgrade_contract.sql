\set ON_ERROR_STOP on

do $$
declare
  snapshot_size bigint;
begin
  select payload_size_bytes into snapshot_size
  from public.external_api_snapshots
  where id = 'e1230000-0000-0000-0000-000000000023'
    and raw_payload = '{"safe":"legacy-preserved"}'::jsonb
    and redaction_version = 'legacy-unversioned'
    and payload_format = 'LEGACY_UNKNOWN'
    and initial_parse_status = parse_status
    and initial_error_code is not distinct from error_code
    and purged_at is null
    and purge_after is not null;

  if snapshot_size is null or snapshot_size <= 0 then
    raise exception 'legacy snapshot storage upgrade was not preserved';
  end if;

  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public'
      and table_name = 'external_api_snapshots'
      and column_name = 'raw_payload'
      and is_nullable = 'YES'
  ) then
    raise exception 'legacy snapshot raw payload did not become nullable';
  end if;

  if has_table_privilege('anon', 'public.external_api_snapshots', 'SELECT')
     or has_table_privilege('authenticated', 'public.external_api_snapshots', 'SELECT')
     or to_regprocedure('public.protect_external_snapshot_identity()') is null then
    raise exception 'legacy snapshot storage permissions or trigger are unsafe';
  end if;
end;
$$;

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version,
  status, finished_at, source_provider, source_service, scope_key
) values
(
  'e1100000-0000-0000-0000-000000000001',
  'tago', 'legacy route-stop laundering probe', 'legacyRouteStopProbe',
  'contract-v1', 'succeeded', now(), 'TAGO', 'legacy',
  'legacy:route-stop-probe'
),
(
  'e1100000-0000-0000-0000-000000000002',
  'tago', 'legacy timetable laundering probe', 'legacyTimetableProbe',
  'contract-v1', 'succeeded', now(), 'OTHER', 'legacy',
  'legacy:timetable-probe'
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload,
  parse_status, parsed_at
) values
(
  'e1200000-0000-0000-0000-000000000001',
  'e1100000-0000-0000-0000-000000000001',
  'TAGO', 'legacy', 'legacyRouteStopProbe', 'legacy:route-stop-probe',
  repeat('1', 64), 'contract-v1', repeat('2', 64), '{}'::jsonb,
  'parsed', now()
),
(
  'e1200000-0000-0000-0000-000000000002',
  'e1100000-0000-0000-0000-000000000002',
  'OTHER', 'legacy', 'legacyTimetableProbe', 'legacy:timetable-probe',
  repeat('3', 64), 'contract-v1', repeat('4', 64), '{}'::jsonb,
  'parsed', now()
);

do $$
begin
  if not exists (
    select 1
    from public.data_import_runs
    where id = 'e1000000-0000-0000-0000-000000000001'
      and status = 'succeeded'
      and finished_at is null
      and metadata = '["legacy-metadata-array"]'::jsonb
      and source_provider = 'fixture'
      and source_service = 'legacy-v1-run'
      and source_operation = 'legacy'
  ) then
    raise exception 'v1 import run was not preserved';
  end if;

  if not exists (
    select 1
    from public.place_operating_hours
    where id = 'e3100000-0000-0000-0000-000000000001'
      and open_time = '22:00'::time
      and close_time = '02:00'::time
      and last_entry_time = '01:00'::time
      and spans_next_day
  ) or not exists (
    select 1
    from public.place_operating_hours
    where id = 'e3100000-0000-0000-0000-000000000002'
      and open_time = '02:00'::time
      and close_time = '03:00'::time
  ) then
    raise exception 'v1 overnight operating hours boundary was not preserved';
  end if;

  if (
    select count(*)
    from public.place_images
    where place_id = 'e3000000-0000-0000-0000-000000000001'
      and image_url = 'https://images.example.test/legacy-v1.jpg'
      and source_url_key is null
  ) <> 2 then
    raise exception 'v1 duplicate image URLs were not preserved';
  end if;

  if not exists (
    select 1
    from public.place_images
    where id = 'e3200000-0000-0000-0000-000000000003'
      and image_url = ''
      and source_provider = ''
  ) or not exists (
    select 1
    from public.bus_stops
    where id = 'e3300000-0000-0000-0000-000000000003'
      and node_id = ''
      and external_stop_id = ''
      and source_provider = ''
  ) or not exists (
    select 1
    from public.bus_routes
    where id = 'e3400000-0000-0000-0000-000000000002'
      and external_route_id = ''
      and source_provider = ''
  ) or not exists (
    select 1
    from public.route_stops
    where route_id = 'e3400000-0000-0000-0000-000000000002'
      and stop_id = 'e3300000-0000-0000-0000-000000000003'
      and source_provider = ''
      and city_code = '39'
  ) then
    raise exception 'v1 blank external identifiers were not preserved';
  end if;

  if not exists (
    select 1
    from public.mobility_route_snapshots
    where id = 'e3600000-0000-0000-0000-000000000001'
      and request_hash = ''
      and source_operation = 'route'
  ) then
    raise exception 'v1 blank mobility request hash was not preserved';
  end if;

  if not exists (
    select 1
    from public.data_import_runs
    where id = 'e1000000-0000-0000-0000-000000000002'
      and octet_length(source_operation) > 5000
      and idempotency_key = 'legacy-oversized-idempotency'
      and not idempotency_enforced
  ) or not exists (
    select 1
    from public.place_images
    where id = 'e3200000-0000-0000-0000-000000000010'
      and octet_length(image_url) > 5000
  ) or not exists (
    select 1
    from public.bus_stops
    where id = 'e3300000-0000-0000-0000-000000000010'
      and octet_length(source_provider) > 5000
  ) or not exists (
    select 1
    from public.bus_routes
    where id = 'e3400000-0000-0000-0000-000000000010'
      and octet_length(source_provider) > 5000
  ) or not exists (
    select 1
    from public.timetable_entries
    where id = 'e3500000-0000-0000-0000-000000000011'
      and octet_length(direction_key) > 5000
  ) or not exists (
    select 1
    from public.mobility_route_snapshots
    where id = 'e3600000-0000-0000-0000-000000000010'
      and octet_length(source_provider) > 5000
      and octet_length(source_operation) > 5000
  ) then
    raise exception 'v1 oversized external identifiers were not preserved';
  end if;

  begin
    update public.data_import_runs
    set idempotency_enforced = true
    where id = 'e1000000-0000-0000-0000-000000000002';
    raise exception 'oversized legacy idempotency key unexpectedly enabled enforcement';
  exception
    when check_violation then null;
  end;

  if (
    select count(*)
    from public.data_import_runs
    where id in (
      'e1000000-0000-0000-0000-000000000020',
      'e1000000-0000-0000-0000-000000000021',
      'e1000000-0000-0000-0000-000000000022'
    )
      and status = 'running'
      and not running_scope_enforced
  ) <> 1 or exists (
    select 1
    from public.data_import_runs
    where id in (
      'e1000000-0000-0000-0000-000000000020',
      'e1000000-0000-0000-0000-000000000021',
      'e1000000-0000-0000-0000-000000000022'
    )
      and (
        source_name not like 'legacy-running-scope%'
        or scope_key <> 'global'
      )
  ) then
    raise exception 'v1 duplicate running scopes were not preserved safely';
  end if;

  if (
    select count(*)
    from public.data_import_runs import_run
    where import_run.id in (
      'e1000000-0000-0000-0000-000000000027',
      'e1000000-0000-0000-0000-000000000028'
    )
      and import_run.status = 'running'
      and import_run.source_name = 'foundation-snapshot-running'
      and not import_run.running_scope_enforced
      and import_run.scope_key = 'foundation:duplicate-running'
      and exists (
        select 1
        from public.external_api_snapshots snapshot
        where snapshot.import_run_id = import_run.id
          and snapshot.source_provider = import_run.source_provider
          and snapshot.source_service = import_run.source_service
          and snapshot.source_operation = import_run.source_operation
          and snapshot.scope_key = import_run.scope_key
      )
  ) <> 1 then
    raise exception 'snapshot-linked duplicate running scopes were not grandfathered';
  end if;

  if (
    select count(*)
    from public.data_import_runs
    where id in (
      'e1000000-0000-0000-0000-000000000029',
      'e1000000-0000-0000-0000-000000000030'
    )
      and idempotency_key = 'same-foundation-idempotency-key'
      and not idempotency_enforced
  ) <> 1 or (
    select count(*)
    from public.data_import_runs
    where id in (
      'e1000000-0000-0000-0000-000000000029',
      'e1000000-0000-0000-0000-000000000030'
    )
      and idempotency_key = 'same-foundation-idempotency-key'
      and idempotency_enforced
  ) <> 1 then
    raise exception 'legacy duplicate idempotency keys were not grandfathered';
  end if;

  begin
    update public.data_import_runs
    set idempotency_enforced = true
    where id in (
      'e1000000-0000-0000-0000-000000000029',
      'e1000000-0000-0000-0000-000000000030'
    )
      and not idempotency_enforced;
    raise exception 'legacy duplicate idempotency key enforcement unexpectedly enabled';
  exception
    when unique_violation then null;
  end;

  begin
    update public.data_import_runs
    set idempotency_key = 'mutated-while-grandfathered'
    where id in (
      'e1000000-0000-0000-0000-000000000029',
      'e1000000-0000-0000-0000-000000000030'
    )
      and not idempotency_enforced;
    raise exception 'grandfathered idempotency key changed without enforcement opt-in';
  exception
    when check_violation then null;
  end;

  begin
    insert into public.data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key, source_provider, source_service
    ) values (
      'tago', 'new-duplicate-idempotency-probe', 'getRouteInfo',
      'foundation-v2', 'succeeded', now(), 'foundation-parser-v2',
      'foundation-schema-v2', 'full', 'foundation:idempotency-duplicate',
      'same-foundation-idempotency-key', 'tago',
      'foundation-idempotency-duplicate'
    );
    raise exception 'new run reused grandfathered idempotency key';
  exception
    when unique_violation then null;
  end;

  insert into public.data_import_runs (
    source_kind, source_name, source_operation, data_version, status,
    finished_at, parser_version, schema_version, sync_mode, scope_key,
    idempotency_key, source_provider, source_service
  ) values (
    'tago', 'new-idempotency-upsert-probe', 'getRouteInfo',
    'foundation-v2', 'succeeded', now(), 'foundation-parser-v2',
    'foundation-schema-v2', 'full', 'foundation:idempotency-duplicate',
    'same-foundation-idempotency-key', 'tago',
    'foundation-idempotency-duplicate'
  )
  on conflict (
    source_provider, source_service, source_operation, scope_key,
    idempotency_key
  ) where idempotency_key is not null
    and idempotency_enforced
  do nothing;

  if (
    select count(*)
    from public.data_import_runs
    where source_provider = 'tago'
      and source_service = 'foundation-idempotency-duplicate'
      and source_operation = 'getRouteInfo'
      and scope_key = 'foundation:idempotency-duplicate'
      and idempotency_key = 'same-foundation-idempotency-key'
  ) <> 2 then
    raise exception 'grandfathered idempotency ON CONFLICT arbiter was bypassed';
  end if;

  begin
    delete from public.data_import_runs
    where id in (
      'e1000000-0000-0000-0000-000000000029',
      'e1000000-0000-0000-0000-000000000030'
    )
      and idempotency_enforced;
    raise exception 'canonical grandfathered idempotency arbiter was deleted';
  exception
    when check_violation then null;
  end;

  begin
    insert into public.data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      parser_version, schema_version, sync_mode, scope_key,
      source_provider, source_service
    ) values (
      'tago', 'new-running-scope-probe', 'getRouteInfo', 'foundation-v2',
      'running', 'foundation-parser-v2', 'foundation-schema-v2', 'full',
      'foundation:duplicate-running', 'TAGO', '버스노선정보 API'
    );
    raise exception 'new run reused grandfathered running scope';
  exception
    when unique_violation then null;
  end;

  -- v1에서 합법이었던 장문 실행 scope는 원문을 보존하면서도 상태 머신을
  -- 끝낼 수 있어야 한다. 신규 INSERT와 source key 변경만 길이를 제한한다.
  update public.data_import_runs
  set row_count = row_count + 1
  where id = 'e1000000-0000-0000-0000-000000000023';

  update public.data_import_runs
  set status = 'succeeded',
      finished_at = pg_catalog.clock_timestamp()
  where id = 'e1000000-0000-0000-0000-000000000023';

  if not exists (
    select 1
    from public.data_import_runs
    where id = 'e1000000-0000-0000-0000-000000000023'
      and status = 'succeeded'
      and finished_at is not null
      and octet_length(source_operation) > 5000
  ) then
    raise exception 'v1 oversized running import could not finish';
  end if;

  begin
    update public.data_import_runs
    set status = 'running', finished_at = null
    where id = 'e1000000-0000-0000-0000-000000000023';
    raise exception 'v1 oversized terminal import restarted as running';
  exception
    when check_violation then null;
  end;

  update public.data_import_runs
  set row_count = row_count + 1
  where id = 'e1000000-0000-0000-0000-000000000024';

  update public.data_import_runs
  set status = 'succeeded',
      finished_at = pg_catalog.clock_timestamp()
  where id = 'e1000000-0000-0000-0000-000000000024';

  if not exists (
    select 1
    from public.data_import_runs
    where id = 'e1000000-0000-0000-0000-000000000024'
      and status = 'succeeded'
      and finished_at is not null
      and source_name = ''
      and data_version = ''
      and metadata = '[]'::jsonb
  ) then
    raise exception 'v1 malformed running import could not finish';
  end if;

  begin
    update public.data_import_runs
    set status = 'running', finished_at = null
    where id = 'e1000000-0000-0000-0000-000000000024';
    raise exception 'v1 malformed terminal import restarted as running';
  exception
    when check_violation then null;
  end;

  if not exists (
    select 1
    from public.route_stops
    where route_id = 'e3400000-0000-0000-0000-000000000001'
      and stop_id = 'e3300000-0000-0000-0000-000000000001'
  ) or not exists (
    select 1
    from public.timetable_entries
    where id = 'e3500000-0000-0000-0000-000000000002'
      and city_code is null
  ) or not exists (
    select 1
    from public.timetable_entries
    where id = 'e3500000-0000-0000-0000-000000000003'
      and source_provider = 'OTHER'
      and city_code is null
  ) then
    raise exception 'v1 transport source identity was not preserved';
  end if;

  if not exists (
    select 1
    from public.trip_weather_impacts
    where id = 'e4500000-0000-0000-0000-000000000001'
      and trip_day_id is null
  ) or not exists (
    select 1
    from public.recommendation_candidates
    where id = 'e4600000-0000-0000-0000-000000000001'
      and trip_day_id is null
  ) then
    raise exception 'v1 nullable day-scoped result was not preserved';
  end if;

  if not exists (
    select 1
    from public.trip_schedule_versions
    where id = 'e4200000-0000-0000-0000-000000000001'
      and status = 'candidate'
  ) then
    raise exception 'valid v1 sealed schedule was not preserved';
  end if;

  -- 값이 바뀌지 않는 legacy 유지 작업은 허용하지만, 외부 데이터 갱신은
  -- 새 snapshot/run lineage 없이 기존 행을 덮어쓸 수 없어야 한다.
  update public.place_images
  set display_order = display_order
  where id = 'e3200000-0000-0000-0000-000000000001';

  begin
    update public.place_images
    set image_url = 'https://images.example.test/lineage-bypass.jpg'
    where id = 'e3200000-0000-0000-0000-000000000001';
    raise exception 'legacy lineage-free external row was mutable';
  exception
    when check_violation then null;
  end;

  begin
    update public.place_images
    set source_provider = 'admin_upload',
        image_url = 'https://images.example.test/optional-lineage-bypass.jpg'
    where id = 'e3200000-0000-0000-0000-000000000001';
    raise exception 'legacy external row cannot become an optional lineage row';
  exception
    when check_violation then null;
  end;

  begin
    update public.place_images
    set source_provider = 'fixture',
        source_service = 'legacy-v1-run',
        import_run_id = 'e1000000-0000-0000-0000-000000000001',
        image_url = 'https://images.example.test/optional-run-lineage-bypass.jpg'
    where id = 'e3200000-0000-0000-0000-000000000001';
    raise exception 'legacy external row cannot borrow an optional import run';
  exception
    when check_violation then null;
  end;

  begin
    insert into public.timetable_entries (
      route_id, stop_id, direction_key, service_day_type, departure_time,
      source_provider, source_service, city_code, source_record_key
    ) values (
      'e3400000-0000-0000-0000-000000000001',
      'e3300000-0000-0000-0000-000000000001',
      'outbound', 'daily', '14:00', 'TAGO', 'legacy', '39',
      'legacy-invalid-parent-new-reference'
    );
    raise exception 'new timetable reused an invalid legacy route-stop parent';
  exception
    when check_violation then null;
  end;

  begin
    update public.timetable_entries
    set source_record_key = 'mutated-legacy-null-city'
    where id = 'e3500000-0000-0000-0000-000000000002';
    raise exception 'legacy null-city timetable identity was mutable';
  exception
    when check_violation then null;
  end;

  begin
    update public.route_stops
    set travel_minutes_from_prev = 99,
        import_run_id = 'e1100000-0000-0000-0000-000000000001',
        source_snapshot_id = 'e1200000-0000-0000-0000-000000000001'
    where route_id = 'e3400000-0000-0000-0000-000000000001'
      and stop_id = 'e3300000-0000-0000-0000-000000000001'
      and direction_key = 'outbound';
    raise exception 'invalid legacy route stop accepted new payload lineage';
  exception
    when check_violation then null;
  end;

  begin
    update public.timetable_entries
    set departure_time = '11:30',
        import_run_id = 'e1100000-0000-0000-0000-000000000002',
        source_snapshot_id = 'e1200000-0000-0000-0000-000000000002'
    where id = 'e3500000-0000-0000-0000-000000000003';
    raise exception 'invalid legacy timetable accepted new payload lineage';
  exception
    when check_violation then null;
  end;

  -- legacy source row는 lineage 없이 덮어쓸 수 없지만, parsed snapshot과 matching
  -- run을 붙인 정상 재수집에서는 잘못된 관계/city scope를 한 번에 복구한다.
  update public.route_stops
  set stop_id = 'e3300000-0000-0000-0000-000000000002',
      import_run_id = 'e1100000-0000-0000-0000-000000000001',
      source_snapshot_id = 'e1200000-0000-0000-0000-000000000001'
  where route_id = 'e3400000-0000-0000-0000-000000000001'
    and stop_id = 'e3300000-0000-0000-0000-000000000001'
    and direction_key = 'repairable';

  insert into public.route_stops (
    route_id, stop_id, direction_key, stop_sequence,
    source_provider, city_code, import_run_id, source_snapshot_id
  ) values (
    'e3400000-0000-0000-0000-000000000001',
    'e3300000-0000-0000-0000-000000000002',
    'outbound', 2, 'TAGO', '39',
    'e1100000-0000-0000-0000-000000000001',
    'e1200000-0000-0000-0000-000000000001'
  );

  update public.timetable_entries
  set city_code = '39',
      import_run_id = 'e1100000-0000-0000-0000-000000000001',
      source_snapshot_id = 'e1200000-0000-0000-0000-000000000001'
  where id = 'e3500000-0000-0000-0000-000000000002';

  if not exists (
    select 1
    from public.route_stops
    where route_id = 'e3400000-0000-0000-0000-000000000001'
      and stop_id = 'e3300000-0000-0000-0000-000000000002'
      and direction_key = 'repairable'
      and source_provider = 'TAGO'
      and city_code = '39'
      and import_run_id = 'e1100000-0000-0000-0000-000000000001'
      and source_snapshot_id = 'e1200000-0000-0000-0000-000000000001'
  ) or not exists (
    select 1
    from public.timetable_entries
    where id = 'e3500000-0000-0000-0000-000000000002'
      and city_code = '39'
      and import_run_id = 'e1100000-0000-0000-0000-000000000001'
      and source_snapshot_id = 'e1200000-0000-0000-0000-000000000001'
  ) then
    raise exception 'legacy transport row could not be repaired by parsed lineage';
  end if;

  if not exists (
    select 1
    from public.compute_runs
    where id = 'e4400000-0000-0000-0000-000000000003'
      and status = 'succeeded'
      and result_source = 'fallback'
      and attempt_count = 0
      and fencing_token = 0
  ) then
    raise exception 'legacy fallback compute run was not normalized';
  end if;


  begin
    update public.compute_runs
    set trip_day_id = 'e4100000-0000-0000-0000-000000000002'
    where id = 'e4400000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day compute parent moved days';
  exception
    when check_violation then null;
  end;

  begin
    update public.trip_items
    set trip_day_id = 'e4100000-0000-0000-0000-000000000002'
    where id = 'e4300000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day item parent moved days';
  exception
    when check_violation then null;
  end;

  begin
    update public.trip_legs
    set trip_day_id = 'e4100000-0000-0000-0000-000000000002',
        from_item_id = 'e4300000-0000-0000-0000-000000000002',
        to_item_id = 'e4300000-0000-0000-0000-000000000004'
    where id = 'e4350000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day leg parent moved days';
  exception
    when check_violation then null;
  end;

  begin
    update public.trip_weather_impacts
    set compute_run_id = 'e4400000-0000-0000-0000-000000000002',
        trip_item_id = 'e4300000-0000-0000-0000-000000000002'
    where id = 'e4500000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day weather lineage crossed days';
  exception
    when check_violation then null;
  end;

  begin
    update public.recommendation_candidates
    set compute_run_id = 'e4400000-0000-0000-0000-000000000002',
        base_item_id = 'e4300000-0000-0000-0000-000000000002'
    where id = 'e4600000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day recommendation lineage crossed days';
  exception
    when check_violation then null;
  end;

  update public.trip_weather_impacts
  set trip_day_id = 'e4100000-0000-0000-0000-000000000002',
      compute_run_id = 'e4400000-0000-0000-0000-000000000002',
      trip_item_id = 'e4300000-0000-0000-0000-000000000002'
  where id = 'e4500000-0000-0000-0000-000000000002';

  update public.recommendation_candidates
  set trip_day_id = 'e4100000-0000-0000-0000-000000000002',
      compute_run_id = 'e4400000-0000-0000-0000-000000000002',
      base_item_id = 'e4300000-0000-0000-0000-000000000002'
  where id = 'e4600000-0000-0000-0000-000000000002';

  if not exists (
    select 1
    from public.trip_weather_impacts
    where id = 'e4500000-0000-0000-0000-000000000002'
      and trip_day_id = 'e4100000-0000-0000-0000-000000000002'
      and compute_run_id = 'e4400000-0000-0000-0000-000000000002'
      and trip_item_id = 'e4300000-0000-0000-0000-000000000002'
  ) or not exists (
    select 1
    from public.recommendation_candidates
    where id = 'e4600000-0000-0000-0000-000000000002'
      and trip_day_id = 'e4100000-0000-0000-0000-000000000002'
      and compute_run_id = 'e4400000-0000-0000-0000-000000000002'
      and base_item_id = 'e4300000-0000-0000-0000-000000000002'
  ) then
    raise exception 'legacy null-day result could not be repaired to one day';
  end if;

  begin
    update public.trip_weather_impacts
    set compute_run_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'
    where id = 'e4500000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day weather impact lost its compute parent FK';
  exception
    when foreign_key_violation or check_violation then null;
  end;

  begin
    update public.recommendation_candidates
    set base_item_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'
    where id = 'e4600000-0000-0000-0000-000000000001';
    raise exception 'legacy null-day recommendation lost its item parent FK';
  exception
    when foreign_key_violation or check_violation then null;
  end;

  delete from public.compute_runs
  where id = 'e4400000-0000-0000-0000-000000000001';

  if exists (
    select 1
    from public.trip_weather_impacts
    where id = 'e4500000-0000-0000-0000-000000000001'
  ) or exists (
    select 1
    from public.recommendation_candidates
    where id = 'e4600000-0000-0000-0000-000000000001'
  ) or exists (
    select 1
    from public.trip_weather_impacts
    where id = 'e4500000-0000-0000-0000-000000000003'
  ) then
    raise exception 'legacy null-day result lost its compute parent cascade';
  end if;
end;
$$;

do $$
declare
  legacy_owner uuid;
  legacy_fence bigint;
begin
  select owner_token, fencing_token
    into legacy_owner, legacy_fence
  from public.data_import_runs
  where id = 'e1000000-0000-0000-0000-000000000001';

  if legacy_owner is null or legacy_fence <> 1 then
    raise exception 'legacy import run lease backfill is invalid';
  end if;

  begin
    update public.data_import_runs
    set owner_token = gen_random_uuid()
    where id = 'e1000000-0000-0000-0000-000000000001';
    raise exception 'legacy import run owner token unexpectedly changed';
  exception when check_violation then
    null;
  end;
end;
$$;

do $$
declare
  owner_default text;
  fence_default text;
begin
  select column_default into owner_default
  from information_schema.columns
  where table_schema = 'public' and table_name = 'data_import_runs'
    and column_name = 'owner_token' and is_nullable = 'NO';

  select column_default into fence_default
  from information_schema.columns
  where table_schema = 'public' and table_name = 'data_import_runs'
    and column_name = 'fencing_token' and is_nullable = 'NO';

  if owner_default is null or owner_default not ilike '%gen_random_uuid%'
     or fence_default is null or fence_default not like '1%' then
    raise exception 'import run lease defaults are invalid';
  end if;
end;
$$;

do $$
declare
  function_definition text;
  missing_objects text;
begin
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
    'from public.place_detail_items target',
    'from public.place_images target',
    'tourapi operation provenance target does not exist'
  ]) as required_fragment
  where strpos(function_definition, required_fragment) = 0;

  if missing_objects is not null then
    raise exception 'legacy TourAPI provenance target guard is incomplete: %', missing_objects;
  end if;
end;
$$;

select 'legacy_v1_upgrade_contract' as check_name, 'PASS' as result;
