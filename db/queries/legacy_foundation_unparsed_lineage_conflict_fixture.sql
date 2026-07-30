\set ON_ERROR_STOP on

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status,
  finished_at, parser_version, schema_version, sync_mode, scope_key, metadata
) values (
  'e8000000-0000-0000-0000-000000000001',
  'tour_api', 'legacy-unparsed-lineage', 'areaBasedList2', 'foundation-v1',
  'succeeded', now(), 'legacy-parser-v1', 'legacy-schema-v1', 'full',
  'region:50', '{}'::jsonb
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash, raw_payload,
  parse_status
) values (
  'e8010000-0000-0000-0000-000000000001',
  'e8000000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'areaBasedList2', 'region:50',
  repeat('1', 64), 'legacy-parser-v1', repeat('2', 64), '{}'::jsonb,
  'received'
);

insert into public.tour_places (
  id, external_place_id, name, normalized_name, category, location,
  source_provider, source_service, import_run_id, source_snapshot_id
) values (
  'e8020000-0000-0000-0000-000000000001', 'legacy-unparsed-place',
  'legacy 미파싱 계보 장소', 'legacy미파싱계보장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  '한국관광공사', 'KorService2',
  'e8000000-0000-0000-0000-000000000001',
  'e8010000-0000-0000-0000-000000000001'
);
