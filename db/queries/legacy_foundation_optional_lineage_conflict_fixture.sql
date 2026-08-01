\set ON_ERROR_STOP on

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key, metadata
) values (
  'e8300000-0000-0000-0000-000000000001',
  'tour_api', 'legacy-optional-lineage', 'areaBasedList2', 'foundation-v1',
  'succeeded', now(), 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'region:50', '{}'::jsonb
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload,
  parse_status, parsed_at
) values (
  'e8310000-0000-0000-0000-000000000001',
  'e8300000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedList2', 'region:50',
  repeat('9', 64), 'legacy-parser-v1', repeat('a', 64), '{}'::jsonb,
  'parsed', now()
);

insert into public.tour_places (
  id, name, normalized_name, category, location, source_provider
) values (
  'e8320000-0000-0000-0000-000000000001',
  'legacy optional 계보 장소', 'legacyoptional계보장소',
  'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  'admin_upload'
);

insert into public.place_aliases (
  id, place_id, alias, normalized_alias, alias_type,
  source_snapshot_id, import_run_id
) values (
  'e8330000-0000-0000-0000-000000000001',
  'e8320000-0000-0000-0000-000000000001',
  'legacy 외부 user query', 'legacy외부userquery', 'user_query',
  'e8310000-0000-0000-0000-000000000001',
  'e8300000-0000-0000-0000-000000000001'
);
