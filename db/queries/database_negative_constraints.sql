\set ON_ERROR_STOP on

begin;

set local time zone 'Asia/Seoul';

create function pg_temp.expect_rejected(
  test_name text,
  statement text,
  expected_states text[]
)
returns void
language plpgsql
as $$
declare
  rejected boolean := false;
  actual_state text;
begin
  begin
    execute statement;
  exception when others then
    actual_state := sqlstate;
    if actual_state = any(expected_states) then
      rejected := true;
    else
      raise exception 'negative contract % returned unexpected SQLSTATE %',
        test_name, actual_state;
    end if;
  end;

  if not rejected then
    raise exception 'negative contract % unexpectedly succeeded', test_name;
  end if;
end;
$$;

insert into data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  started_at, finished_at, row_count, parser_version, schema_version,
  sync_mode, scope_key, idempotency_key, retry_count, fetched_count,
  inserted_count, updated_count, skipped_count, rejected_count,
  deleted_count, staled_count, source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000001',
  'tour_api', 'TourAPI', 'areaBasedSyncList2', '2026-07-30', 'succeeded',
  now() - interval '1 minute', now(), 0, 'tour-parser-v1', 'tour-schema-v1',
  'incremental', 'region:50', 'negative-contract-success', 0, 0,
  0, 0, 0, 0, 0, 0, '한국관광공사', 'KorService2'
);

select pg_temp.expect_rejected(
  'duplicate import idempotency key',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key
    ) values (
      'tour_api', 'TourAPI', 'areaBasedSyncList2', '2026-07-30', 'succeeded',
      now(), 'tour-parser-v1', 'tour-schema-v1', 'incremental', 'region:50',
      'negative-contract-success'
    )
  $statement$,
  array['23505']
);

insert into data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  parser_version, schema_version, sync_mode, scope_key, idempotency_key,
  source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000002',
  'tour_api', 'TourAPI', 'detailCommon2', '2026-07-30', 'running',
  'tour-parser-v1', 'tour-schema-v1', 'lazy', 'content:running',
  'negative-running-primary', '한국관광공사', 'KorService2'
);

insert into data_import_checkpoints (
  source_provider, source_service, source_operation, scope_key,
  checkpoint, last_succeeded_run_id
) values (
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  '{"page":2}'::jsonb, 'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'checkpoint cannot reference a running run',
  $statement$
    insert into data_import_checkpoints (
      source_provider, source_service, source_operation, scope_key,
      checkpoint, last_succeeded_run_id
    ) values (
      '한국관광공사', 'KorService2', 'detailCommon2', 'content:running',
      '{}'::jsonb, 'f1000000-0000-0000-0000-000000000002'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'checkpoint scope must match its run',
  $statement$
    insert into data_import_checkpoints (
      source_provider, source_service, source_operation, scope_key,
      checkpoint, last_succeeded_run_id
    ) values (
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:other',
      '{}'::jsonb, 'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23503', '23514']
);

select pg_temp.expect_rejected(
  'checkpoint referenced run must remain succeeded',
  $statement$
    update data_import_runs
    set status = 'failed', error_code = 'LATE_FAILURE',
        error_message = 'sanitized failure'
    where id = 'f1000000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'concurrent import for same scope',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      parser_version, schema_version, sync_mode, scope_key, idempotency_key
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', '2026-07-30', 'running',
      'tour-parser-v1', 'tour-schema-v1', 'lazy', 'content:running',
      'negative-running-concurrent'
    )
  $statement$,
  array['23505']
);

select pg_temp.expect_rejected(
  'negative import retry count',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      parser_version, schema_version, sync_mode, scope_key, idempotency_key,
      retry_count
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'running',
      'parser-v1', 'schema-v1', 'lazy', 'content:1',
      'negative-retry', -1
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'running import with finish time',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'running', now(),
      'parser-v1', 'schema-v1', 'lazy', 'content:2', 'running-finished'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'failed import without error code',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'failed', now(),
      'parser-v1', 'schema-v1', 'lazy', 'content:3', 'failed-no-code'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'succeeded import with error code',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, error_code, parser_version, schema_version, sync_mode,
      scope_key, idempotency_key
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'succeeded', now(),
      'SHOULD_NOT_EXIST', 'parser-v1', 'schema-v1', 'lazy', 'content:4',
      'succeeded-with-code'
    )
  $statement$,
  array['23514']
);

insert into external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, external_record_id, request_hash, page_key, parser_version,
  payload_hash, request_metadata_redacted, raw_payload, parse_status,
  fetched_at, parsed_at
) values
(
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  '126435', repeat('a', 64), '', 'tour-parser-v1', repeat('b', 64),
  '{"contentId":"126435"}'::jsonb, '{}'::jsonb, 'parsed', now(), now()
),
(
  'f2000000-0000-0000-0000-000000000002',
  'f1000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  '126435', repeat('c', 64), '', 'tour-parser-v1', repeat('d', 64),
  '{}'::jsonb, '[]'::jsonb, 'parsed', now(), now()
),
(
  'f2000000-0000-0000-0000-000000000003',
  'f1000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  '126436', repeat('7', 64), '', 'tour-parser-v1', repeat('6', 64),
  '{}'::jsonb, '{}'::jsonb, 'received', now(), null
);

select pg_temp.expect_rejected(
  'snapshot provider and service must match its import run',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, request_hash, parser_version, payload_hash, raw_payload
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '다른공급자', 'KorService2', 'areaBasedSyncList2', 'region:50',
      repeat('9', 64), 'tour-parser-v1', repeat('8', 64), '{}'::jsonb
    )
  $statement$,
  array['23503']
);

select pg_temp.expect_rejected(
  'snapshot source identity is immutable',
  $statement$
    update external_api_snapshots
    set scope_key = 'region:changed'
    where id = 'f2000000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'normalized-capable snapshot cannot become unparsed',
  $statement$
    update external_api_snapshots
    set parse_status = 'received'
    where id = 'f2000000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'duplicate source snapshot identity',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, external_record_id, request_hash, page_key, parser_version,
      payload_hash, raw_payload, parse_status, fetched_at, parsed_at
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      '126435', repeat('a', 64), '', 'tour-parser-v1', repeat('b', 64),
      '{}'::jsonb, 'parsed', now(), now()
    )
  $statement$,
  array['23505']
);

select pg_temp.expect_rejected(
  'uppercase payload hash',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, request_hash, parser_version, payload_hash, raw_payload,
      parse_status, fetched_at
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      repeat('e', 64), 'tour-parser-v1', repeat('A', 64), '{}'::jsonb,
      'received', now()
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'json scalar raw payload',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, request_hash, parser_version, payload_hash, raw_payload,
      parse_status, fetched_at
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      repeat('f', 64), 'tour-parser-v1', repeat('1', 64), 'null'::jsonb,
      'received', now()
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'rejected snapshot without error code',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, request_hash, parser_version, payload_hash, raw_payload,
      parse_status, fetched_at
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      repeat('2', 64), 'tour-parser-v1', repeat('3', 64), '{}'::jsonb,
      'rejected', now()
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'parsed snapshot without parsed time',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, request_hash, parser_version, payload_hash, raw_payload,
      parse_status, fetched_at
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      repeat('4', 64), 'tour-parser-v1', repeat('5', 64), '{}'::jsonb,
      'parsed', now()
    )
  $statement$,
  array['23514']
);

insert into weather_grid_points (
  id, grid_provider, nx, ny, region_name
) values (
  'f2500000-0000-0000-0000-000000000001', 'KMA', 61, 37, 'DB 계약 격자'
);

select pg_temp.expect_rejected(
  'negative weather observation value',
  $statement$
    insert into weather_observations (
      grid_point_id, observed_at, base_date, base_time,
      precipitation_mm, source_operation
    ) values (
      'f2500000-0000-0000-0000-000000000001',
      '2026-07-30 09:00:00+09', '2026-07-30', '09:00',
      -0.1, 'getUltraSrtNcst'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'weather forecast issued after valid time',
  $statement$
    insert into weather_forecasts (
      grid_point_id, forecasted_at, valid_at, forecast_type,
      source_operation
    ) values (
      'f2500000-0000-0000-0000-000000000001',
      '2026-07-30 10:00:00+09', '2026-07-30 09:00:00+09',
      'short', 'getVilageFcst'
    )
  $statement$,
  array['23514']
);

insert into tour_places (
  id, name, normalized_name, category, location, source_provider
) values
(
  'f3000000-0000-0000-0000-000000000001', '계약 장소 1', '계약장소1',
  'tourist_attraction', st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  '한국관광공사'
),
(
  'f3000000-0000-0000-0000-000000000002', '계약 장소 2', '계약장소2',
  'tourist_attraction', st_setsrid(st_makepoint(126.6, 33.4), 4326)::geography,
  '다른공급자'
);

insert into tour_place_sources (
  place_id, source_provider, source_service, external_id,
  source_snapshot_id, last_import_run_id
) values
(
  'f3000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'same-id',
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001'
),
(
  'f3000000-0000-0000-0000-000000000002',
  '다른공급자', 'OtherService', 'same-id', null,
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'duplicate provider scoped place key',
  $statement$
    insert into tour_place_sources (
      place_id, source_provider, source_service, external_id,
      last_import_run_id
    ) values (
      'f3000000-0000-0000-0000-000000000002',
      '한국관광공사', 'KorService2', 'same-id',
      'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23505']
);

select pg_temp.expect_rejected(
  'normalized source provider must match its snapshot',
  $statement$
    insert into tour_place_sources (
      place_id, source_provider, source_service, external_id,
      source_snapshot_id, last_import_run_id
    ) values (
      'f3000000-0000-0000-0000-000000000002',
      '다른공급자', 'OtherService', 'lineage-mismatch',
      'f2000000-0000-0000-0000-000000000001',
      'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'unparsed snapshot cannot produce a normalized source row',
  $statement$
    insert into tour_place_sources (
      place_id, source_provider, source_service, external_id,
      source_snapshot_id, last_import_run_id
    ) values (
      'f3000000-0000-0000-0000-000000000002',
      '한국관광공사', 'KorService2', 'unparsed-lineage',
      'f2000000-0000-0000-0000-000000000003',
      'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23514']
);

insert into place_operating_hours (
  place_id, day_of_week, interval_no, open_time, close_time,
  spans_next_day, valid_from, valid_to, source_kind,
  source_snapshot_id, import_run_id
) values
(
  'f3000000-0000-0000-0000-000000000001', 1, 1, '09:00', '12:00',
  false, '-infinity', null, 'parsed',
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001'
),
(
  'f3000000-0000-0000-0000-000000000001', 1, 2, '13:00', '18:00',
  false, '-infinity', null, 'parsed',
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001'
),
(
  'f3000000-0000-0000-0000-000000000001', 5, 1, '22:00', '02:00',
  true, '2026-01-01', '2026-12-31', 'parsed',
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'overlapping operating-hours interval',
  $statement$
    insert into place_operating_hours (
      place_id, day_of_week, interval_no, open_time, close_time,
      spans_next_day, valid_from, source_kind
    ) values (
      'f3000000-0000-0000-0000-000000000001', 1, 3,
      '11:30', '14:00', false, '-infinity', 'parsed'
    )
  $statement$,
  array['23P01']
);

select pg_temp.expect_rejected(
  'reversed operating-hours validity',
  $statement$
    insert into place_operating_hours (
      place_id, day_of_week, interval_no, is_closed, valid_from, valid_to,
      source_kind
    ) values (
      'f3000000-0000-0000-0000-000000000001', 2, 1, true,
      '2026-12-31', '2026-01-01', 'manual'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'open and closed hours cannot overlap',
  $statement$
    insert into place_operating_hours (
      place_id, day_of_week, interval_no, is_closed, valid_from, valid_to,
      source_kind
    ) values (
      'f3000000-0000-0000-0000-000000000001', 1, 9, true,
      '2026-01-01', '2026-12-31', 'manual'
    )
  $statement$,
  array['23P01']
);

select pg_temp.expect_rejected(
  'same-day last entry must be within opening interval',
  $statement$
    insert into place_operating_hours (
      place_id, day_of_week, interval_no, open_time, close_time,
      last_entry_time, valid_from, valid_to, source_kind
    ) values (
      'f3000000-0000-0000-0000-000000000001', 2, 2,
      '09:00', '18:00', '08:59', '2026-01-01', '2026-12-31', 'manual'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'overnight last entry must be within opening interval',
  $statement$
    insert into place_operating_hours (
      place_id, day_of_week, interval_no, open_time, close_time,
      last_entry_time, spans_next_day, valid_from, valid_to, source_kind
    ) values (
      'f3000000-0000-0000-0000-000000000001', 3, 1,
      '22:00', '02:00', '12:00', true,
      '2026-01-01', '2026-12-31', 'manual'
    )
  $statement$,
  array['23514']
);

insert into external_reference_codes (
  source_provider, source_service, code_type, external_code, code_name,
  valid_from, valid_to
) values (
  '한국관광공사', 'KorService2', 'lclsSystm1', 'EX01', '계약 분류',
  '2026-01-01', '2026-12-31'
);

select pg_temp.expect_rejected(
  'reference code validity cannot overlap in source scope',
  $statement$
    insert into external_reference_codes (
      source_provider, source_service, code_type, external_code, code_name,
      valid_from, valid_to
    ) values (
      '한국관광공사', 'KorService2', 'lclsSystm1', 'EX01', '중복 분류',
      '2026-06-01', '2027-01-01'
    )
  $statement$,
  array['23P01']
);

insert into place_images (
  place_id, image_url, source_provider, source_service, source_image_id,
  source_snapshot_id, import_run_id
) values
(
  'f3000000-0000-0000-0000-000000000001',
  'https://images.example.test/one.jpg', '한국관광공사', 'KorService2',
  'serial-1', 'f2000000-0000-0000-0000-000000000002',
  'f1000000-0000-0000-0000-000000000001'
),
(
  'f3000000-0000-0000-0000-000000000001',
  'https://images.example.test/url-only.jpg', '한국관광공사', 'KorService2',
  null, 'f2000000-0000-0000-0000-000000000002',
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'duplicate provider image id',
  $statement$
    insert into place_images (
      place_id, image_url, source_provider, source_service, source_image_id
    ) values (
      'f3000000-0000-0000-0000-000000000001',
      'https://images.example.test/replaced.jpg',
      '한국관광공사', 'KorService2', 'serial-1'
    )
  $statement$,
  array['23505']
);

select pg_temp.expect_rejected(
  'duplicate provider image url without source id',
  $statement$
    insert into place_images (
      place_id, image_url, source_provider, source_service, source_image_id
    ) values (
      'f3000000-0000-0000-0000-000000000001',
      'https://images.example.test/url-only.jpg',
      '한국관광공사', 'KorService2', null
    )
  $statement$,
  array['23505']
);

insert into bus_stops (
  id, node_id, node_name, city_code, location, source_provider, source_service
) values
(
  'f4000000-0000-0000-0000-000000000001', 'NODE-SAME', '제주 정류장', '39',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography, 'TAGO', 'BusSttnInfoInqireService'
),
(
  'f4000000-0000-0000-0000-000000000002', 'NODE-SAME', '다른 도시 정류장', '50',
  st_setsrid(st_makepoint(127.0, 37.5), 4326)::geography, 'TAGO', 'BusSttnInfoInqireService'
),
(
  'f4000000-0000-0000-0000-000000000003', 'NODE-OTHER', '제주 다른 정류장', '39',
  st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography, 'TAGO', 'BusSttnInfoInqireService'
);

select pg_temp.expect_rejected(
  'duplicate provider city stop key',
  $statement$
    insert into bus_stops (
      node_id, node_name, city_code, location, source_provider, source_service
    ) values (
      'NODE-SAME', '중복 정류장', '39',
      st_setsrid(st_makepoint(126.52, 33.52), 4326)::geography,
      'TAGO', 'BusSttnInfoInqireService'
    )
  $statement$,
  array['23505']
);

insert into bus_routes (
  id, external_route_id, route_no, direction_name, city_code,
  source_provider, source_service
) values
(
  'f4100000-0000-0000-0000-000000000001',
  'ROUTE-1', '101', '성산 방면', '39', 'TAGO', 'BusRouteInfoInqireService'
),
(
  'f4100000-0000-0000-0000-000000000002',
  'ROUTE-1', '101', '다른 도시 방면', '50', 'TAGO', 'BusRouteInfoInqireService'
);

select pg_temp.expect_rejected(
  'duplicate provider city route key',
  $statement$
    insert into bus_routes (
      external_route_id, route_no, direction_name, city_code,
      source_provider, source_service
    ) values (
      'ROUTE-1', '101-duplicate', '중복 방면', '39',
      'TAGO', 'BusRouteInfoInqireService'
    )
  $statement$,
  array['23505']
);

insert into route_stops (
  route_id, stop_id, direction_key, stop_sequence, source_provider, city_code
) values (
  'f4100000-0000-0000-0000-000000000001',
  'f4000000-0000-0000-0000-000000000001', 'outbound', 1, 'TAGO', '39'
);

select pg_temp.expect_rejected(
  'route stop cannot cross provider city scope',
  $statement$
    insert into route_stops (
      route_id, stop_id, direction_key, stop_sequence, source_provider, city_code
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000002',
      'outbound', 2, 'TAGO', '39'
    )
  $statement$,
  array['23503']
);

insert into timetable_entries (
  route_id, stop_id, direction_key, service_day_type, departure_time,
  source_provider, source_service, city_code, source_record_key
) values (
  'f4100000-0000-0000-0000-000000000001',
  'f4000000-0000-0000-0000-000000000001', 'outbound',
  'weekday', '09:00', 'TAGO', 'TimetableService', '39',
  'route-1-stop-1-0900'
);

select pg_temp.expect_rejected(
  'duplicate timetable source record',
  $statement$
    insert into timetable_entries (
      route_id, stop_id, direction_key, service_day_type, departure_time,
      source_provider, source_service, city_code, source_record_key
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000001', 'outbound',
      'weekday', '09:00', 'TAGO', 'TimetableService', '39',
      'route-1-stop-1-0900'
    )
  $statement$,
  array['23505']
);

select pg_temp.expect_rejected(
  'timetable stop outside route direction',
  $statement$
    insert into timetable_entries (
      route_id, stop_id, direction_key, service_day_type, departure_time,
      source_provider, source_service, city_code, source_record_key
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000003', 'outbound',
      'weekday', '10:00', 'TAGO', 'TimetableService', '39',
      'route-1-wrong-stop-1000'
    )
  $statement$,
  array['23503']
);

select pg_temp.expect_rejected(
  'timetable validity cannot overlap in source scope',
  $statement$
    insert into timetable_entries (
      route_id, stop_id, direction_key, service_day_type, departure_time,
      valid_from, valid_to, source_provider, source_service, city_code,
      source_record_key
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000001', 'outbound',
      'weekday', '09:00', '2026-01-01', '2026-12-31',
      'TAGO', 'TimetableService', '39', 'route-1-stop-1-0900'
    )
  $statement$,
  array['23P01']
);

insert into timetable_entries (
  route_id, stop_id, direction_key, service_day_type, departure_time,
  valid_from, valid_to, source_provider, source_service, city_code,
  source_record_key
) values (
  'f4100000-0000-0000-0000-000000000001',
  'f4000000-0000-0000-0000-000000000001', 'outbound',
  'weekday', '09:00', '2026-01-01', '2026-12-31',
  'TAGO', 'OtherTimetableService', '39', 'route-1-stop-1-0900'
);

insert into app_sessions (id, public_token)
values
  ('f5000000-0000-0000-0000-000000000001', 'db-contract-session-1'),
  ('f5000000-0000-0000-0000-000000000002', 'db-contract-session-2');

insert into trip_plans (
  id, session_id, public_token, status, start_date, end_date,
  source_mode, data_version
) values
(
  'f5100000-0000-0000-0000-000000000001',
  'f5000000-0000-0000-0000-000000000001', 'db-contract-trip-1',
  'draft', '2026-08-10', '2026-08-11', 'fixture', 'contract-v1'
),
(
  'f5100000-0000-0000-0000-000000000002',
  'f5000000-0000-0000-0000-000000000002', 'db-contract-trip-2',
  'draft', '2026-09-01', '2026-09-01', 'fixture', 'contract-v1'
);

insert into trip_days (id, trip_plan_id, day_no, trip_date)
values
(
  'f5200000-0000-0000-0000-000000000001',
  'f5100000-0000-0000-0000-000000000001', 1, '2026-08-10'
),
(
  'f5200000-0000-0000-0000-000000000002',
  'f5100000-0000-0000-0000-000000000001', 2, '2026-08-11'
),
(
  'f5200000-0000-0000-0000-000000000003',
  'f5100000-0000-0000-0000-000000000002', 1, '2026-09-01'
);

insert into trip_schedule_versions (
  id, trip_plan_id, version_no, status, source_type
) values
(
  'f5300000-0000-0000-0000-000000000001',
  'f5100000-0000-0000-0000-000000000001', 1, 'draft', 'initial'
),
(
  'f5300000-0000-0000-0000-000000000002',
  'f5100000-0000-0000-0000-000000000001', 2, 'draft', 'user_edit'
),
(
  'f5300000-0000-0000-0000-000000000003',
  'f5100000-0000-0000-0000-000000000002', 1, 'draft', 'initial'
);

select pg_temp.expect_rejected(
  'schedule version cannot reference itself as base',
  $statement$
    update trip_schedule_versions
    set base_schedule_version_id = id
    where id = 'f5300000-0000-0000-0000-000000000002'
  $statement$,
  array['23514']
);

insert into trip_items (
  id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
  item_type, place_id, planned_start_at, planned_end_at, stay_minutes, source
) values
(
  'f5400000-0000-0000-0000-000000000001',
  'f5100000-0000-0000-0000-000000000001',
  'f5200000-0000-0000-0000-000000000001',
  'f5300000-0000-0000-0000-000000000001', 1, 'place_visit',
  'f3000000-0000-0000-0000-000000000001',
  '2026-08-10 09:00:00+09', '2026-08-10 10:00:00+09', 60, 'system'
),
(
  'f5400000-0000-0000-0000-000000000002',
  'f5100000-0000-0000-0000-000000000001',
  'f5200000-0000-0000-0000-000000000002',
  'f5300000-0000-0000-0000-000000000001', 1, 'place_visit',
  'f3000000-0000-0000-0000-000000000002',
  '2026-08-11 09:00:00+09', '2026-08-11 10:00:00+09', 60, 'system'
),
(
  'f5400000-0000-0000-0000-000000000003',
  'f5100000-0000-0000-0000-000000000001',
  'f5200000-0000-0000-0000-000000000001',
  'f5300000-0000-0000-0000-000000000002', 1, 'place_visit',
  'f3000000-0000-0000-0000-000000000001',
  '2026-08-10 11:00:00+09', '2026-08-10 12:00:00+09', 60, 'system'
);

select public.assert_schedule_day_coverage(
  'f5300000-0000-0000-0000-000000000001',
  'f5100000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'schedule missing one trip day',
  $statement$
    select public.assert_schedule_day_coverage(
      'f5300000-0000-0000-0000-000000000002',
      'f5100000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['P0001']
);

update trip_schedule_versions
set status = 'candidate'
where id = 'f5300000-0000-0000-0000-000000000001';

select pg_temp.expect_rejected(
  'moving content out of sealed schedule version',
  $statement$
    update trip_items
    set schedule_version_id = 'f5300000-0000-0000-0000-000000000002'
    where id = 'f5400000-0000-0000-0000-000000000001'
  $statement$,
  array['P0001']
);

select pg_temp.expect_rejected(
  'cross trip day ownership',
  $statement$
    insert into trip_items (
      trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
      item_type, title, source
    ) values (
      'f5100000-0000-0000-0000-000000000001',
      'f5200000-0000-0000-0000-000000000003',
      'f5300000-0000-0000-0000-000000000002', 2,
      'custom', '다른 여행의 일자', 'system'
    )
  $statement$,
  array['23503', 'P0001']
);

select pg_temp.expect_rejected(
  'sealed schedule day window is immutable',
  $statement$
    update trip_days
    set start_time = '10:30'
    where id = 'f5200000-0000-0000-0000-000000000001'
  $statement$,
  array['P0001']
);

select pg_temp.expect_rejected(
  'sealed schedule day cannot be deleted through cascade',
  $statement$
    delete from trip_days
    where id = 'f5200000-0000-0000-0000-000000000002'
  $statement$,
  array['P0001']
);

select pg_temp.expect_rejected(
  'trip dates are immutable after schedule sealing',
  $statement$
    update trip_plans
    set end_date = '2026-08-12'
    where id = 'f5100000-0000-0000-0000-000000000001'
  $statement$,
  array['P0001']
);

select pg_temp.expect_rejected(
  'schedule base lineage cannot point forward',
  $statement$
    update trip_schedule_versions
    set base_schedule_version_id = 'f5300000-0000-0000-0000-000000000002'
    where id = 'f5300000-0000-0000-0000-000000000001'
  $statement$,
  array['P0001']
);

insert into compute_runs (
  id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
  input_hash, contract_version, algorithm_version, facts_snapshot_at,
  source_data_version
) values (
  'f5500000-0000-0000-0000-000000000001',
  'f5100000-0000-0000-0000-000000000001',
  'f5200000-0000-0000-0000-000000000001',
  'f5300000-0000-0000-0000-000000000001',
  'feasibility', 'succeeded', 'negative-contract-day-1',
  'contract-v1', 'algorithm-v1', now(), 'source-v1'
);

select pg_temp.expect_rejected(
  'weather impact day must match compute and item day',
  $statement$
    insert into trip_weather_impacts (
      trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
      trip_item_id, impact_type, severity
    ) values (
      'f5100000-0000-0000-0000-000000000001',
      'f5200000-0000-0000-0000-000000000002',
      'f5300000-0000-0000-0000-000000000001',
      'f5500000-0000-0000-0000-000000000001',
      'f5400000-0000-0000-0000-000000000001',
      'rain', 'yellow'
    )
  $statement$,
  array['23503']
);

select pg_temp.expect_rejected(
  'recommendation day must match compute and base item day',
  $statement$
    insert into recommendation_candidates (
      trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
      base_item_id, candidate_place_id, recommendation_type
    ) values (
      'f5100000-0000-0000-0000-000000000001',
      'f5200000-0000-0000-0000-000000000002',
      'f5300000-0000-0000-0000-000000000001',
      'f5500000-0000-0000-0000-000000000001',
      'f5400000-0000-0000-0000-000000000001',
      'f3000000-0000-0000-0000-000000000002',
      'replacement'
    )
  $statement$,
  array['23503']
);

select 'database_negative_constraints' as check_name, 'PASS' as result;

rollback;
