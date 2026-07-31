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
  'oversized import source key',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key, source_provider, source_service
    ) values (
      'tour_api', 'oversized', 'sync', 'v1', 'succeeded', now(),
      'parser-v1', 'schema-v1', 'full', 'global', 'oversized-import',
      repeat('p', 129), 'service'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'assigned import idempotency key cannot be cleared',
  $statement$
    update data_import_runs
    set idempotency_key = null
    where id = 'f1000000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'assigned import idempotency key cannot change',
  $statement$
    update data_import_runs
    set idempotency_key = 'changed-idempotency-key'
    where id = 'f1000000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'idempotency enforcement cannot be disabled',
  $statement$
    update data_import_runs
    set idempotency_enforced = false
    where id = 'f1000000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'new import cannot opt out of idempotency enforcement',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key, idempotency_enforced, source_provider, source_service
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'succeeded', now(),
      'parser-v1', 'schema-v1', 'lazy', 'content:opt-out',
      'idempotency-opt-out', false, '한국관광공사', 'KorService2'
    )
  $statement$,
  array['23514']
);

-- same provider service ignores source name for idempotency
select pg_temp.expect_rejected(
  'duplicate import idempotency key',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      finished_at, parser_version, schema_version, sync_mode, scope_key,
      idempotency_key, source_provider, source_service
    ) values (
      'tour_api', 'renamed-source', 'areaBasedSyncList2', '2026-07-30', 'succeeded',
      now(), 'tour-parser-v1', 'tour-schema-v1', 'incremental', 'region:50',
      'negative-contract-success', '한국관광공사', 'KorService2'
    )
  $statement$,
  array['23505']
);

-- different provider service may reuse an idempotency key
insert into data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key,
  idempotency_key, source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000004',
  'admin_upload', 'TourAPI', 'areaBasedSyncList2', '2026-07-30', 'succeeded',
  now(), 'admin-parser-v1', 'admin-schema-v1', 'incremental', 'region:50',
  'negative-contract-success', '다른공급자', 'OtherService'
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

select pg_temp.expect_rejected(
  'running-scope enforcement cannot be disabled',
  $statement$
    update data_import_runs
    set running_scope_enforced = false
    where id = 'f1000000-0000-0000-0000-000000000002'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'new running import cannot opt out of scope enforcement',
  $statement$
    insert into data_import_runs (
      source_kind, source_name, source_operation, data_version, status,
      parser_version, schema_version, sync_mode, scope_key,
      running_scope_enforced, source_provider, source_service
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'running',
      'parser-v1', 'schema-v1', 'lazy', 'content:opt-out', false,
      '한국관광공사', 'KorService2'
    )
  $statement$,
  array['23514']
);

insert into data_import_checkpoints (
  source_provider, source_service, source_operation, scope_key,
  checkpoint, last_succeeded_run_id
) values (
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  '{"page":2}'::jsonb, 'f1000000-0000-0000-0000-000000000001'
);

select public.advance_data_import_checkpoint(
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  0, '{"page":3}'::jsonb, now(),
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'checkpoint semantic fields cannot bypass the CAS function',
  $statement$
    update data_import_checkpoints
    set checkpoint = '{"page":999}'::jsonb,
        version = 2
    where source_provider = '한국관광공사'
      and source_service = 'KorService2'
      and source_operation = 'areaBasedSyncList2'
      and scope_key = 'region:50'
  $statement$,
  array['40001']
);

select pg_temp.expect_rejected(
  'checkpoint compare-and-set version rejects stale writer',
  $statement$
    select public.advance_data_import_checkpoint(
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      0, '{"page":4}'::jsonb, now(),
      'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['40001']
);

select pg_temp.expect_rejected(
  'checkpoint source scope is immutable',
  $statement$
    update data_import_checkpoints
    set scope_key = 'region:changed'
    where source_provider = '한국관광공사'
      and source_service = 'KorService2'
      and source_operation = 'areaBasedSyncList2'
      and scope_key = 'region:50'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'checkpoint rows cannot be deleted to reset progress',
  $statement$
    delete from data_import_checkpoints
    where source_provider = '한국관광공사'
      and source_service = 'KorService2'
      and source_operation = 'areaBasedSyncList2'
      and scope_key = 'region:50'
  $statement$,
  array['P0001']
);

select pg_temp.expect_rejected(
  'checkpoint table cannot be truncated to reset progress',
  $statement$
    truncate table data_import_checkpoints
  $statement$,
  array['P0001']
);

insert into data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  started_at, finished_at, parser_version, schema_version, sync_mode,
  scope_key, idempotency_key, source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000005',
  'tour_api', 'TourAPI older', 'areaBasedSyncList2', '2026-07-29', 'succeeded',
  now() - interval '10 minutes', now() - interval '9 minutes',
  'tour-parser-v1', 'tour-schema-v1', 'incremental', 'region:50',
  'negative-contract-older', '한국관광공사', 'KorService2'
);

select pg_temp.expect_rejected(
  'checkpoint cannot move to an older succeeded import run',
  $statement$
    select public.advance_data_import_checkpoint(
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
      1, '{"page":1}'::jsonb, now(),
      'f1000000-0000-0000-0000-000000000005'
    )
  $statement$,
  array['23514']
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
      parser_version, schema_version, sync_mode, scope_key, idempotency_key,
      source_provider, source_service
    ) values (
      'tour_api', 'renamed-running-source', 'detailCommon2', '2026-07-30', 'running',
      'tour-parser-v1', 'tour-schema-v1', 'lazy', 'content:running',
      'negative-running-concurrent', '한국관광공사', 'KorService2'
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
      retry_count, source_provider, source_service
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'running',
      'parser-v1', 'schema-v1', 'lazy', 'content:1',
      'negative-retry', -1, '한국관광공사', 'KorService2'
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
      idempotency_key, source_provider, source_service
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'running', now(),
      'parser-v1', 'schema-v1', 'lazy', 'content:2', 'running-finished',
      '한국관광공사', 'KorService2'
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
      idempotency_key, source_provider, source_service
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'failed', now(),
      'parser-v1', 'schema-v1', 'lazy', 'content:3', 'failed-no-code',
      '한국관광공사', 'KorService2'
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
      scope_key, idempotency_key, source_provider, source_service
    ) values (
      'tour_api', 'TourAPI', 'detailCommon2', 'v1', 'succeeded', now(),
      'SHOULD_NOT_EXIST', 'parser-v1', 'schema-v1', 'lazy', 'content:4',
      'succeeded-with-code', '한국관광공사', 'KorService2'
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
),
(
  'f2000000-0000-0000-0000-000000000005',
  'f1000000-0000-0000-0000-000000000004',
  '다른공급자', 'OtherService', 'areaBasedSyncList2', 'region:50',
  'same-id', repeat('5', 64), '', 'admin-parser-v1', repeat('4', 64),
  '{}'::jsonb, '{}'::jsonb, 'parsed', now(), now()
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
  array['23514']
);

select pg_temp.expect_rejected(
  'oversized snapshot source key',
  $statement$
    insert into external_api_snapshots (
      import_run_id, source_provider, source_service, source_operation,
      scope_key, request_hash, parser_version, payload_hash, raw_payload
    ) values (
      'f1000000-0000-0000-0000-000000000001',
      '한국관광공사', 'KorService2', 'areaBasedSyncList2', repeat('s', 513),
      repeat('1', 64), 'tour-parser-v1', repeat('2', 64), '{}'::jsonb
    )
  $statement$,
  array['23514']
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
  'snapshot audit payload is immutable',
  $statement$
    update external_api_snapshots
    set raw_payload = '{"changed":true}'::jsonb,
        payload_hash = repeat('0', 64),
        request_hash = repeat('1', 64),
        parser_version = 'tampered-parser'
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
  'admin_upload'
),
(
  'f3000000-0000-0000-0000-000000000002', '계약 장소 2', '계약장소2',
  'tourist_attraction', st_setsrid(st_makepoint(126.6, 33.4), 4326)::geography,
  'admin_upload'
);

update public.tour_places
set name = '수정 가능한 계약 장소 1'
where id = 'f3000000-0000-0000-0000-000000000001';

do $$
begin
  if not exists (
    select 1
    from public.tour_places
    where id = 'f3000000-0000-0000-0000-000000000001'
      and name = '수정 가능한 계약 장소 1'
  ) then
    raise exception 'admin exception row remains editable failed';
  end if;
end;
$$;

insert into public.place_aliases (
  id, place_id, alias, normalized_alias, alias_type,
  source_snapshot_id, import_run_id
) values (
  'f3100000-0000-0000-0000-000000000001',
  'f3000000-0000-0000-0000-000000000001',
  '외부 원문 사용자 검색어', '외부원문사용자검색어', 'user_query',
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'snapshot-backed user-query alias cannot clear a live snapshot pointer',
  $statement$
    update public.place_aliases
    set source_snapshot_id = null
    where id = 'f3100000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'snapshot-backed user-query alias cannot remove external lineage',
  $statement$
    update public.place_aliases
    set alias = '계보를 지운 사용자 검색어',
        normalized_alias = '계보를지운사용자검색어',
        source_snapshot_id = null,
        import_run_id = null
    where id = 'f3100000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'same external snapshot and run cannot rewrite normalized content',
  $statement$
    update public.place_aliases
    set alias = '같은 원문으로 바꾼 사용자 검색어',
        normalized_alias = '같은원문으로바꾼사용자검색어'
    where id = 'f3100000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key,
  idempotency_key, source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000008',
  'tour_api', 'reserved-marker-external', 'reservedMarkerSync', 'contract-v1',
  'succeeded', now(), 'reserved-parser-v1', 'reserved-schema-v1', 'full',
  'reserved:50', 'reserved-marker-external', 'admin_upload', 'reserved-service'
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, external_record_id, request_hash, parser_version, payload_hash,
  raw_payload, parse_status, parsed_at
) values (
  'f2000000-0000-0000-0000-000000000008',
  'f1000000-0000-0000-0000-000000000008',
  'admin_upload', 'reserved-service', 'reservedMarkerSync', 'reserved:50',
  'reserved-source', repeat('1', 64), 'reserved-parser-v1', repeat('2', 64),
  '{"external_id":"reserved-source"}'::jsonb, 'parsed', now()
);

insert into public.tour_place_sources (
  id, place_id, source_provider, source_service, external_id,
  source_snapshot_id, last_import_run_id
) values (
  'f3200000-0000-0000-0000-000000000001',
  'f3000000-0000-0000-0000-000000000001',
  'admin_upload', 'reserved-service', 'reserved-source',
  'f2000000-0000-0000-0000-000000000008',
  'f1000000-0000-0000-0000-000000000008'
);

select pg_temp.expect_rejected(
  'import run source kind is immutable',
  $statement$
    update public.data_import_runs
    set source_kind = 'admin_upload'
    where id = 'f1000000-0000-0000-0000-000000000008'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'snapshot-backed reserved provider cannot remove external lineage',
  $statement$
    update public.tour_place_sources
    set source_provider = 'admin_upload',
        source_service = 'manual',
        external_id = 'reserved-source-laundered',
        source_snapshot_id = null,
        last_import_run_id = null
    where id = 'f3200000-0000-0000-0000-000000000001'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'new external tour place requires source snapshot lineage',
  $statement$
    insert into tour_places (
      name, normalized_name, category, location, source_provider,
      import_run_id
    ) values (
      'lineage 없는 외부 장소', 'lineage없는외부장소', 'tourist_attraction',
      st_setsrid(st_makepoint(126.7, 33.3), 4326)::geography,
      '한국관광공사', 'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'external-looking row cannot borrow an admin import run',
  $statement$
    insert into tour_places (
      name, normalized_name, category, location, source_provider,
      import_run_id
    ) values (
      'admin run 위장 외부 장소', 'adminrun위장외부장소', 'tourist_attraction',
      st_setsrid(st_makepoint(126.71, 33.31), 4326)::geography,
      '한국관광공사', 'f1000000-0000-0000-0000-000000000004'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'admin marker cannot borrow a tour API import run',
  $statement$
    insert into tour_places (
      name, normalized_name, category, location, source_provider,
      import_run_id
    ) values (
      '외부 run 위장 관리 장소', '외부run위장관리장소', 'tourist_attraction',
      st_setsrid(st_makepoint(126.72, 33.32), 4326)::geography,
      'admin_upload', 'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23514']
);

insert into external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, external_record_id, request_hash, parser_version, payload_hash,
  raw_payload, parse_status, parsed_at
) values (
  'f2000000-0000-0000-0000-000000000004',
  'f1000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  'purge-source', repeat('8', 64), 'tour-parser-v1', repeat('9', 64),
  '{"contentid":"purge-source"}'::jsonb, 'parsed', now()
);

insert into tour_place_sources (
  place_id, source_provider, source_service, external_id,
  source_snapshot_id, last_import_run_id
) values (
  'f3000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'purge-source',
  'f2000000-0000-0000-0000-000000000004',
  'f1000000-0000-0000-0000-000000000001'
);

insert into public.place_aliases (
  id, place_id, alias, normalized_alias, alias_type,
  source_snapshot_id, import_run_id
) values (
  'f3100000-0000-0000-0000-000000000002',
  'f3000000-0000-0000-0000-000000000001',
  '보존기간 만료 외부 별칭', '보존기간만료외부별칭', 'user_query',
  'f2000000-0000-0000-0000-000000000004',
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'snapshot-backed external row cannot become optional without lineage',
  $statement$
    update public.tour_place_sources
    set source_provider = 'admin_upload',
        source_service = 'manual',
        external_id = 'purge-source-laundered',
        source_snapshot_id = null,
        last_import_run_id = null
    where external_id = 'purge-source'
  $statement$,
  array['23514']
);

delete from external_api_snapshots
where id = 'f2000000-0000-0000-0000-000000000004';

select pg_temp.expect_rejected(
  'retained external optional row cannot remove its last import run',
  $statement$
    update public.place_aliases
    set import_run_id = null
    where id = 'f3100000-0000-0000-0000-000000000002'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'retained external optional row cannot rewrite normalized content',
  $statement$
    update public.place_aliases
    set alias = '보존기간 이후 바꾼 외부 별칭',
        normalized_alias = '보존기간이후바꾼외부별칭'
    where id = 'f3100000-0000-0000-0000-000000000002'
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'retained external optional row cannot rewrite content and remove lineage',
  $statement$
    update public.place_aliases
    set alias = '계보를 세탁한 외부 별칭',
        normalized_alias = '계보를세탁한외부별칭',
        import_run_id = null
    where id = 'f3100000-0000-0000-0000-000000000002'
  $statement$,
  array['23514']
);

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key,
  idempotency_key, source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000007',
  'admin_upload', 'manual-contract', 'manual', 'contract-v1', 'succeeded',
  now(), 'manual-parser', 'manual-schema', 'full', 'manual:contract',
  'manual-contract', 'admin_upload', 'manual'
);

select pg_temp.expect_rejected(
  'retained external row cannot borrow an optional import run',
  $statement$
    update public.tour_place_sources
    set source_provider = 'admin_upload',
        source_service = 'manual',
        external_id = 'purge-source-laundered',
        last_import_run_id = 'f1000000-0000-0000-0000-000000000007'
    where external_id = 'purge-source'
  $statement$,
  array['23514']
);

do $$
begin
  if not exists (
    select 1
    from public.tour_place_sources source_row
    where source_row.external_id = 'purge-source'
      and source_row.source_snapshot_id is null
      and source_row.last_import_run_id =
        'f1000000-0000-0000-0000-000000000001'
  ) then
    raise exception 'snapshot retention purge preserves import run lineage failed';
  end if;
end;
$$;

update public.place_aliases
set alias = '새 원문으로 복구한 외부 별칭',
    normalized_alias = '새원문으로복구한외부별칭',
    source_snapshot_id = 'f2000000-0000-0000-0000-000000000001',
    import_run_id = 'f1000000-0000-0000-0000-000000000001'
where id = 'f3100000-0000-0000-0000-000000000002';

do $$
begin
  if not exists (
    select 1
    from public.place_aliases alias_row
    where alias_row.id = 'f3100000-0000-0000-0000-000000000002'
      and alias_row.alias = '새 원문으로 복구한 외부 별칭'
      and alias_row.source_snapshot_id =
        'f2000000-0000-0000-0000-000000000001'
      and alias_row.import_run_id =
        'f1000000-0000-0000-0000-000000000001'
  ) then
    raise exception 'matching snapshot and run lineage repair failed';
  end if;
end;
$$;

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
  '다른공급자', 'OtherService', 'same-id',
  'f2000000-0000-0000-0000-000000000005',
  'f1000000-0000-0000-0000-000000000004'
);

select pg_temp.expect_rejected(
  'new external normalized row requires source snapshot lineage',
  $statement$
    insert into tour_place_sources (
      place_id, source_provider, source_service, external_id,
      last_import_run_id
    ) values (
      'f3000000-0000-0000-0000-000000000002',
      '한국관광공사', 'KorService2', 'missing-source-snapshot',
      'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23514']
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
  'overnight hours cannot overlap the next service day',
  $statement$
    insert into place_operating_hours (
      place_id, day_of_week, interval_no, open_time, close_time,
      spans_next_day, valid_from, valid_to, source_kind
    ) values (
      'f3000000-0000-0000-0000-000000000001', 6, 1,
      '01:00', '03:00', false, '2026-01-01', '2026-12-31', 'manual'
    )
  $statement$,
  array['23P01']
);

-- midnight-ending overnight hours do not occupy the next day
insert into place_operating_hours (
  place_id, day_of_week, interval_no, is_closed, open_time, close_time,
  spans_next_day, valid_from, valid_to, source_kind
) values
(
  'f3000000-0000-0000-0000-000000000002', 5, 1, false,
  '22:00', '00:00', true, '2026-01-01', '2026-12-31', 'manual'
),
(
  'f3000000-0000-0000-0000-000000000002', 6, 1, true,
  null, null, false, '2026-01-01', '2026-12-31', 'manual'
);

update public.place_operating_hours
set last_entry_time = '23:30'
where place_id = 'f3000000-0000-0000-0000-000000000002'
  and day_of_week = 5;

do $$
begin
  if not exists (
    select 1
    from public.place_operating_hours
    where place_id = 'f3000000-0000-0000-0000-000000000002'
      and day_of_week = 5
      and last_entry_time = '23:30'::time
  ) then
    raise exception 'manual exception row remains editable failed';
  end if;
end;
$$;

do $$
begin
  if (
    select count(*)
    from public.place_operating_hours
    where place_id = 'f3000000-0000-0000-0000-000000000002'
      and day_of_week in (5, 6)
  ) <> 2 then
    raise exception 'midnight-ending overnight hours boundary was not preserved';
  end if;
end;
$$;

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
  valid_from, valid_to, source_snapshot_id, import_run_id
) values (
  '한국관광공사', 'KorService2', 'lclsSystm1', 'EX01', '계약 분류',
  '2026-01-01', '2026-12-31',
  'f2000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001'
);

select pg_temp.expect_rejected(
  'reference code validity cannot overlap in source scope',
  $statement$
    insert into external_reference_codes (
      source_provider, source_service, code_type, external_code, code_name,
      valid_from, valid_to, source_snapshot_id, import_run_id
    ) values (
      '한국관광공사', 'KorService2', 'lclsSystm1', 'EX01', '중복 분류',
      '2026-06-01', '2027-01-01',
      'f2000000-0000-0000-0000-000000000001',
      'f1000000-0000-0000-0000-000000000001'
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
  'oversized place image URL',
  $statement$
    insert into place_images (
      place_id, image_url, source_provider, source_service
    ) values (
      'f3000000-0000-0000-0000-000000000001',
      'https://images.example.test/' || repeat('x', 8193),
      'fixture', 'image-length-contract'
    )
  $statement$,
  array['23514']
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, external_record_id, request_hash, parser_version, payload_hash,
  raw_payload, parse_status, parsed_at
) values (
  'f2000000-0000-0000-0000-000000000009',
  'f1000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  'image-url-enrichment', repeat('e', 64), 'tour-parser-v1',
  repeat('f', 64), '{"image":"serial-enriched"}'::jsonb, 'parsed', now()
);

insert into place_images (
  place_id, image_url, source_provider, source_service, source_image_id,
  source_snapshot_id, import_run_id
) values (
  'f3000000-0000-0000-0000-000000000001',
  'https://images.example.test/url-only.jpg',
  '한국관광공사', 'KorService2', 'serial-enriched',
  'f2000000-0000-0000-0000-000000000009',
  'f1000000-0000-0000-0000-000000000001'
)
on conflict on constraint uq_place_images_source_url_key
do update set
  source_image_id = excluded.source_image_id,
  source_snapshot_id = excluded.source_snapshot_id,
  import_run_id = excluded.import_run_id;

do $$
begin
  if (
    select count(*)
    from public.place_images image
    where image.place_id = 'f3000000-0000-0000-0000-000000000001'
      and image.source_provider = '한국관광공사'
      and image.source_service = 'KorService2'
      and image.image_url = 'https://images.example.test/url-only.jpg'
      and image.source_image_id = 'serial-enriched'
  ) <> 1 then
    raise exception 'image URL enrichment did not reuse exactly one row';
  end if;
end;
$$;

update public.place_images
set source_url_key = null
where place_id = 'f3000000-0000-0000-0000-000000000001'
  and source_provider = '한국관광공사'
  and source_service = 'KorService2'
  and image_url = 'https://images.example.test/url-only.jpg';

do $$
begin
  if not exists (
    select 1
    from public.place_images image
    where image.place_id = 'f3000000-0000-0000-0000-000000000001'
      and image.source_provider = '한국관광공사'
      and image.source_service = 'KorService2'
      and image.image_url = 'https://images.example.test/url-only.jpg'
      and image.source_url_key = public.source_identity_digest(
        image.place_id::text,
        image.source_provider,
        image.source_service,
        image.image_url
      )
  ) then
    raise exception 'image source URL key cannot be cleared';
  end if;
end;
$$;

select pg_temp.expect_rejected(
  'image URL-only row must be updated instead of duplicated during enrichment',
  $statement$
    insert into place_images (
      place_id, image_url, source_provider, source_service, source_image_id,
      source_snapshot_id, import_run_id
    ) values (
      'f3000000-0000-0000-0000-000000000001',
      'https://images.example.test/url-only.jpg',
      '한국관광공사', 'KorService2', 'serial-other',
      'f2000000-0000-0000-0000-000000000002',
      'f1000000-0000-0000-0000-000000000001'
    )
  $statement$,
  array['23505']
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

insert into data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key,
  idempotency_key, source_provider, source_service
) values (
  'f1000000-0000-0000-0000-000000000006',
  'fixture', 'transport-contract', 'seed', 'fixture-v1', 'succeeded',
  now(), 'fixture-parser', 'fixture-schema', 'full', 'fixture:transport',
  'transport-contract', 'fixture', 'transport'
);

select pg_temp.expect_rejected(
  'optional row scope must match its fixture import run',
  $statement$
    insert into tour_places (
      name, normalized_name, category, location, source_provider,
      source_service, import_run_id
    ) values (
      'scope 불일치 fixture', 'scope불일치fixture', 'tourist_attraction',
      st_setsrid(st_makepoint(126.73, 33.33), 4326)::geography,
      'fixture', 'different-service',
      'f1000000-0000-0000-0000-000000000006'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank stop node id',
  $statement$
    insert into bus_stops (
      node_id, node_name, city_code, location, source_provider, source_service
    ) values (
      '', '빈 node 정류장', '39',
      st_setsrid(st_makepoint(126.53, 33.53), 4326)::geography,
      'fixture', 'transport'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank external stop id',
  $statement$
    insert into bus_stops (
      external_stop_id, node_id, node_name, city_code, location,
      source_provider, source_service
    ) values (
      '', 'NODE-BLANK-EXTERNAL', '빈 external 정류장', '39',
      st_setsrid(st_makepoint(126.54, 33.54), 4326)::geography,
      'fixture', 'transport'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank provider source scope',
  $statement$
    insert into bus_stops (
      node_id, node_name, city_code, location, source_provider, source_service
    ) values (
      'NODE-BLANK-SCOPE', '빈 scope 정류장', '39',
      st_setsrid(st_makepoint(126.55, 33.55), 4326)::geography,
      ' ', ''
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank place image URL',
  $statement$
    insert into place_images (
      place_id, image_url, source_provider, source_service
    ) values (
      'f3000000-0000-0000-0000-000000000001', '',
      'admin_upload', 'manual'
    )
  $statement$,
  array['23514']
);

insert into bus_stops (
  id, node_id, node_name, city_code, location, source_provider, source_service,
  import_run_id
) values
(
  'f4000000-0000-0000-0000-000000000001', 'NODE-SAME', '제주 정류장', '39',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography, 'fixture', 'BusSttnInfoInqireService',
  null
),
(
  'f4000000-0000-0000-0000-000000000002', 'NODE-SAME', '다른 도시 정류장', '50',
  st_setsrid(st_makepoint(127.0, 37.5), 4326)::geography, 'fixture', 'BusSttnInfoInqireService',
  null
),
(
  'f4000000-0000-0000-0000-000000000003', 'NODE-OTHER', '제주 다른 정류장', '39',
  st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography, 'fixture', 'BusSttnInfoInqireService',
  null
);

update public.bus_stops
set node_name = '수정 가능한 fixture 정류장'
where id = 'f4000000-0000-0000-0000-000000000003';

do $$
begin
  if not exists (
    select 1
    from public.bus_stops
    where id = 'f4000000-0000-0000-0000-000000000003'
      and node_name = '수정 가능한 fixture 정류장'
  ) then
    raise exception 'fixture exception row remains editable failed';
  end if;
end;
$$;

select pg_temp.expect_rejected(
  'oversized bus stop source key',
  $statement$
    insert into bus_stops (
      node_id, node_name, city_code, location, source_provider, source_service
    ) values (
      'OVERSIZED-NODE', '장문 provider 정류장', '39',
      st_setsrid(st_makepoint(126.52, 33.52), 4326)::geography,
      repeat('p', 129), 'service'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'duplicate provider city stop key',
  $statement$
    insert into bus_stops (
      node_id, node_name, city_code, location, source_provider, source_service
    ) values (
      'NODE-SAME', '중복 정류장', '39',
      st_setsrid(st_makepoint(126.52, 33.52), 4326)::geography,
      'fixture', 'BusSttnInfoInqireService'
    )
  $statement$,
  array['23505']
);

insert into bus_routes (
  id, external_route_id, route_no, direction_name, city_code,
  source_provider, source_service, import_run_id
) values
(
  'f4100000-0000-0000-0000-000000000001',
  'ROUTE-1', '101', '성산 방면', '39', 'fixture', 'BusRouteInfoInqireService',
  null
),
(
  'f4100000-0000-0000-0000-000000000002',
  'ROUTE-1', '101', '다른 도시 방면', '50', 'fixture', 'BusRouteInfoInqireService',
  null
);

select pg_temp.expect_rejected(
  'duplicate provider city route key',
  $statement$
    insert into bus_routes (
      external_route_id, route_no, direction_name, city_code,
      source_provider, source_service
    ) values (
      'ROUTE-1', '101-duplicate', '중복 방면', '39',
      'fixture', 'BusRouteInfoInqireService'
    )
  $statement$,
  array['23505']
);

select pg_temp.expect_rejected(
  'blank external route id',
  $statement$
    insert into bus_routes (
      external_route_id, route_no, direction_name, city_code,
      source_provider, source_service
    ) values (
      '', 'EMPTY', '빈 route', '39', 'fixture', 'transport'
    )
  $statement$,
  array['23514']
);

insert into route_stops (
  route_id, stop_id, direction_key, stop_sequence, source_provider, city_code,
  import_run_id
) values (
  'f4100000-0000-0000-0000-000000000001',
  'f4000000-0000-0000-0000-000000000001', 'outbound', 1, 'fixture', '39',
  null
);

select pg_temp.expect_rejected(
  'route stop cannot cross provider city scope',
  $statement$
    insert into route_stops (
      route_id, stop_id, direction_key, stop_sequence, source_provider, city_code
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000002',
      'outbound', 2, 'fixture', '39'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank route stop direction key',
  $statement$
    insert into route_stops (
      route_id, stop_id, direction_key, stop_sequence, source_provider, city_code
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000003', '', 2, 'fixture', '39'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank mobility request hash',
  $statement$
    insert into mobility_route_snapshots (
      request_hash, origin_location, destination_location, transport_mode,
      duration_minutes, source_provider, source_operation, expires_at
    ) values (
      '',
      st_setsrid(st_makepoint(126.50, 33.50), 4326)::geography,
      st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography,
      'walk', 10, 'fixture', 'route', now() + interval '1 hour'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'oversized mobility source key',
  $statement$
    insert into mobility_route_snapshots (
      request_hash, origin_location, destination_location, transport_mode,
      duration_minutes, source_provider, source_operation, expires_at
    ) values (
      repeat('r', 513),
      st_setsrid(st_makepoint(126.50, 33.50), 4326)::geography,
      st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography,
      'walk', 10, 'fixture', 'route', now() + interval '1 hour'
    )
  $statement$,
  array['23514']
);

insert into timetable_entries (
  route_id, stop_id, direction_key, service_day_type, departure_time,
  source_provider, source_service, city_code, source_record_key, import_run_id
) values (
  'f4100000-0000-0000-0000-000000000001',
  'f4000000-0000-0000-0000-000000000001', 'outbound',
  'weekday', '09:00', 'fixture', 'TimetableService', '39',
  'route-1-stop-1-0900', null
);

select pg_temp.expect_rejected(
  'oversized timetable source key',
  $statement$
    insert into timetable_entries (
      route_id, stop_id, direction_key, service_day_type, departure_time,
      source_provider, source_service, city_code, source_record_key
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000001', 'outbound',
      'weekday', '09:30', 'fixture', 'TimetableService', '39',
      repeat('t', 513)
    )
  $statement$,
  array['23514']
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
      'weekday', '09:00', 'fixture', 'TimetableService', '39',
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
      'weekday', '10:00', 'fixture', 'TimetableService', '39',
      'route-1-wrong-stop-1000'
    )
  $statement$,
  array['23514']
);

select pg_temp.expect_rejected(
  'blank timetable direction key',
  $statement$
    insert into timetable_entries (
      route_id, stop_id, direction_key, service_day_type, departure_time,
      source_provider, source_service, city_code, source_record_key
    ) values (
      'f4100000-0000-0000-0000-000000000001',
      'f4000000-0000-0000-0000-000000000001', '',
      'weekday', '10:30', 'fixture', 'TimetableService', '39',
      'blank-direction'
    )
  $statement$,
  array['23514']
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
      'fixture', 'TimetableService', '39', 'route-1-stop-1-0900'
    )
  $statement$,
  array['23P01']
);

insert into timetable_entries (
  route_id, stop_id, direction_key, service_day_type, departure_time,
  valid_from, valid_to, source_provider, source_service, city_code,
  source_record_key, import_run_id
) values (
  'f4100000-0000-0000-0000-000000000001',
  'f4000000-0000-0000-0000-000000000001', 'outbound',
  'weekday', '09:00', '2026-01-01', '2026-12-31',
  'fixture', 'OtherTimetableService', '39', 'route-1-stop-1-0900',
  null
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

-- 부모 trip_plans UPDATE 자체가 일정 쓰기 펜스다. BEFORE trigger에서 같은
-- 행을 다시 UPDATE하지 않고, 봉인되지 않은 여행 날짜는 정상 변경되어야 한다.
update trip_plans
set end_date = '2026-09-02'
where id = 'f5100000-0000-0000-0000-000000000002';

do $$
declare
  actual_end_date date;
begin
  select plan.end_date
    into actual_end_date
  from trip_plans plan
  where plan.id = 'f5100000-0000-0000-0000-000000000002';

  if actual_end_date <> date '2026-09-02' then
    raise exception 'draft trip dates remain mutable';
  end if;
end;
$$;

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

select pg_temp.expect_rejected(
  'schedule version number is immutable after creation',
  $statement$
    update trip_schedule_versions
    set version_no = 3
    where id = 'f5300000-0000-0000-0000-000000000002'
  $statement$,
  array['P0001']
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

select pg_temp.expect_rejected(
  'schedule item cannot end on another day',
  $statement$
    insert into trip_items (
      id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
      item_type, place_id, planned_start_at, planned_end_at, stay_minutes, source
    ) values (
      'f5400000-0000-0000-0000-000000000004',
      'f5100000-0000-0000-0000-000000000002',
      'f5200000-0000-0000-0000-000000000003',
      'f5300000-0000-0000-0000-000000000003', 1, 'place_visit',
      'f3000000-0000-0000-0000-000000000001',
      '2026-09-01 23:30:00+09', '2026-09-02 00:30:00+09', 60, 'system'
    )
  $statement$,
  array['P0001']
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
  'new weather impact requires trip day',
  $statement$
    insert into trip_weather_impacts (
      trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
      trip_item_id, impact_type, severity
    ) values (
      'f5100000-0000-0000-0000-000000000001', null,
      'f5300000-0000-0000-0000-000000000001',
      'f5500000-0000-0000-0000-000000000001',
      'f5400000-0000-0000-0000-000000000001',
      'rain', 'yellow'
    )
  $statement$,
  array['23502']
);

select pg_temp.expect_rejected(
  'new recommendation requires trip day',
  $statement$
    insert into recommendation_candidates (
      trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
      base_item_id, candidate_place_id, recommendation_type
    ) values (
      'f5100000-0000-0000-0000-000000000001', null,
      'f5300000-0000-0000-0000-000000000001',
      'f5500000-0000-0000-0000-000000000001',
      'f5400000-0000-0000-0000-000000000001',
      'f3000000-0000-0000-0000-000000000002',
      'replacement'
    )
  $statement$,
  array['23502']
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

-- 실제 SHA-256 collision을 만들 수 없으므로 transaction 안에서 함수만 고정값으로
-- 바꿔 exact 원문 collision guard를 강제로 실행하고 ROLLBACK으로 원복한다.
create or replace function public.source_identity_digest(variadic components text[])
returns text
language sql
immutable
security invoker
set search_path = ''
as $$
  select repeat('0', 64)
$$;

insert into place_images (
  place_id, image_url, source_provider, source_service
) values (
  'f3000000-0000-0000-0000-000000000001',
  'https://images.example.test/collision-a.jpg',
  'fixture', 'digest-collision'
);

select pg_temp.expect_rejected(
  'place image source digest collision',
  $statement$
    insert into place_images (
      place_id, image_url, source_provider, source_service
    ) values (
      'f3000000-0000-0000-0000-000000000001',
      'https://images.example.test/collision-b.jpg',
      'fixture', 'digest-collision'
    )
  $statement$,
  array['23505']
);

select 'database_negative_constraints' as check_name, 'PASS' as result;

rollback;
