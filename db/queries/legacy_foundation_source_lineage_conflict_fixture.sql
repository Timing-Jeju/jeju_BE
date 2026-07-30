\set ON_ERROR_STOP on

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key, metadata
) values (
  'e8200000-0000-0000-0000-000000000001',
  'tour_api', 'legacy-source-mismatch', 'areaBasedList2', 'foundation-v1',
  'succeeded', now(), 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'region:50', '{}'::jsonb
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload,
  parse_status, parsed_at
) values (
  'e8210000-0000-0000-0000-000000000001',
  'e8200000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedList2', 'region:50',
  repeat('7', 64), 'legacy-parser-v1', repeat('8', 64), '{}'::jsonb,
  'parsed', now()
);

insert into public.tour_places (
  id, external_place_id, name, normalized_name, category, location,
  source_provider, source_service, import_run_id, source_snapshot_id
) values (
  'e8220000-0000-0000-0000-000000000001', 'legacy-source-mismatch-place',
  'legacy source 불일치 장소', 'legacysource불일치장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  '잘못된공급자', 'KorService2',
  'e8200000-0000-0000-0000-000000000001',
  'e8210000-0000-0000-0000-000000000001'
);
