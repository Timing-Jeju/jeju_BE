\set ON_ERROR_STOP on

-- .100에서는 같은 scope여도 running run을 last_succeeded로 참조할 수 있었다.
insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  parser_version, schema_version, sync_mode, scope_key, metadata
) values (
  'e7900000-0000-0000-0000-000000000001',
  'tago', 'legacy-checkpoint-status-run', 'getRouteInfo', 'foundation-v1',
  'running', 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'legacy:checkpoint-status', '{}'::jsonb
);

insert into public.data_import_checkpoints (
  id, source_provider, source_service, source_operation, scope_key,
  checkpoint, last_succeeded_run_id
) values (
  'e7910000-0000-0000-0000-000000000001',
  'tago', 'legacy-checkpoint-status-run', 'getRouteInfo',
  'legacy:checkpoint-status', '{"page": 1}'::jsonb,
  'e7900000-0000-0000-0000-000000000001'
);
