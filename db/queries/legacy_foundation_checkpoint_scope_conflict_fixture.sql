\set ON_ERROR_STOP on

-- .100에서는 succeeded run이라도 다른 source scope의 checkpoint가 참조할 수 있었다.
insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key, metadata
) values (
  'e7920000-0000-0000-0000-000000000001',
  'tago', 'legacy-checkpoint-scope-run', 'getRouteInfo', 'foundation-v1',
  'succeeded', now(), 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'legacy:actual-scope', '{}'::jsonb
);

insert into public.data_import_checkpoints (
  id, source_provider, source_service, source_operation, scope_key,
  checkpoint, last_succeeded_run_id
) values (
  'e7930000-0000-0000-0000-000000000001',
  'tago', 'legacy-checkpoint-scope-run', 'getRouteInfo',
  'legacy:expected-scope', '{"page": 1}'::jsonb,
  'e7920000-0000-0000-0000-000000000001'
);
