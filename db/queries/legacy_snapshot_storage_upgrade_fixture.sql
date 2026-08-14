\set ON_ERROR_STOP on

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash,
  request_metadata_redacted, raw_payload, parse_status, purge_after
) values (
  'e1230000-0000-0000-0000-000000000023',
  'e1000000-0000-0000-0000-000000000001',
  'fixture', 'legacy-v1-run', 'legacy', 'global',
  repeat('a', 64), 'legacy-parser', repeat('b', 64),
  '{"page":1}'::jsonb, '{"safe":"legacy-preserved"}'::jsonb,
  'received', now() + interval '7 days'
);
