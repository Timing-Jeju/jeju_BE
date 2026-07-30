\set ON_ERROR_STOP on

-- .000에서 idempotency_key가 추가된 뒤에도 v1 장문 source key는 합법이었다.
update public.data_import_runs
set idempotency_key = 'legacy-oversized-idempotency'
where id = 'e1000000-0000-0000-0000-000000000002';

-- 적재 기반(.100)은 적용됐지만 exact scope/동시 실행 계약(.200)은 아직 없는
-- 중간 버전에서 합법인 snapshot-linked 중복 running scope를 재현한다.
insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  parser_version, schema_version, sync_mode, scope_key, metadata
) values
(
  'e1000000-0000-0000-0000-000000000027',
  'tago', 'foundation-snapshot-running', 'getRouteInfo', 'foundation-v1',
  'running', 'foundation-parser-v1', 'foundation-schema-v1', 'full',
  'foundation:duplicate-running', '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000028',
  'tago', 'foundation-snapshot-running', 'getRouteInfo', 'foundation-v1',
  'running', 'foundation-parser-v1', 'foundation-schema-v1', 'full',
  'foundation:duplicate-running', '{}'::jsonb
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload,
  parse_status, parsed_at
) values
(
  'e1300000-0000-0000-0000-000000000001',
  'e1000000-0000-0000-0000-000000000027',
  'TAGO', '버스노선정보 API', 'getRouteInfo',
  'foundation:duplicate-running', repeat('5', 64), 'foundation-parser-v1',
  repeat('6', 64), '{}'::jsonb, 'parsed', now()
),
(
  'e1300000-0000-0000-0000-000000000002',
  'e1000000-0000-0000-0000-000000000028',
  'TAGO', '버스노선정보 API', 'getRouteInfo',
  'foundation:duplicate-running', repeat('7', 64), 'foundation-parser-v1',
  repeat('8', 64), '{}'::jsonb, 'parsed', now()
);

-- .000에는 idempotency column만 있고 UNIQUE가 없으므로 같은 완료 scope/key도
-- .200 직전까지는 합법이다.
insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key,
  idempotency_key, metadata
) values
(
  'e1000000-0000-0000-0000-000000000029',
  'tago', 'foundation-idempotency-duplicate', 'getRouteInfo',
  'foundation-v1', 'succeeded', now(), 'foundation-parser-v1',
  'foundation-schema-v1', 'full', 'foundation:idempotency-duplicate',
  'same-foundation-idempotency-key', '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000030',
  'tago', 'foundation-idempotency-duplicate', 'getRouteInfo',
  'foundation-v1', 'succeeded', now(), 'foundation-parser-v1',
  'foundation-schema-v1', 'full', 'foundation:idempotency-duplicate',
  'same-foundation-idempotency-key', '{}'::jsonb
);
