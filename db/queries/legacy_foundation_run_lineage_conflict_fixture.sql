\set ON_ERROR_STOP on

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key, metadata
) values
(
  'e8100000-0000-0000-0000-000000000001',
  'tour_api', 'legacy-snapshot-run-a', 'areaBasedList2', 'foundation-v1',
  'succeeded', now(), 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'region:50', '{}'::jsonb
),
(
  'e8100000-0000-0000-0000-000000000002',
  'tour_api', 'legacy-normalized-run-b', 'areaBasedList2', 'foundation-v1',
  'succeeded', now(), 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'region:50', '{}'::jsonb
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload,
  parse_status, parsed_at
) values
(
  'e8110000-0000-0000-0000-000000000001',
  'e8100000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedList2', 'region:50',
  repeat('3', 64), 'legacy-parser-v1', repeat('4', 64), '{}'::jsonb,
  'parsed', now()
),
(
  'e8110000-0000-0000-0000-000000000002',
  'e8100000-0000-0000-0000-000000000002',
  '한국관광공사', 'KorService2', 'areaBasedList2', 'region:50',
  repeat('5', 64), 'legacy-parser-v1', repeat('6', 64), '{}'::jsonb,
  'parsed', now()
);

insert into public.tour_places (
  id, external_place_id, name, normalized_name, category, location,
  source_provider, source_service, import_run_id, source_snapshot_id
) values (
  'e8120000-0000-0000-0000-000000000001', 'legacy-run-mismatch-place',
  'legacy run 불일치 장소', 'legacyrun불일치장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  '한국관광공사', 'KorService2',
  'e8100000-0000-0000-0000-000000000002',
  'e8110000-0000-0000-0000-000000000001'
);
