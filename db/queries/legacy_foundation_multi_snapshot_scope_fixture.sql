\set ON_ERROR_STOP on

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  parser_version, schema_version, sync_mode, scope_key, metadata
) values (
  'e7700000-0000-0000-0000-000000000001',
  'tour_api', 'legacy-multi-scope-run', 'areaBasedSyncList2', 'v1',
  'running', 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'legacy:run-scope', '{}'::jsonb
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload
) values
(
  'e7800000-0000-0000-0000-000000000001',
  'e7700000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50',
  repeat('1', 64), 'legacy-parser-v1', repeat('2', 64), '{}'::jsonb
),
(
  'e7800000-0000-0000-0000-000000000002',
  'e7700000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedSyncList2', 'region:50110',
  repeat('3', 64), 'legacy-parser-v1', repeat('4', 64), '{}'::jsonb
);
