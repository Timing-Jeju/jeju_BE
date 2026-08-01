\set ON_ERROR_STOP on

-- 2026-07-28 v1 스키마에서 합법이었던 경계 데이터를 최신 migration 전에 적재한다.
set time zone 'Asia/Seoul';

-- v1에서는 개별 컬럼 또는 아예 인덱싱되지 않아 합법이었던 장문 값을
-- 최신 복합 인덱스가 그대로 합치면 B-tree tuple 크기 제한을 넘을 수 있다.
-- 압축에 기대지 않도록 고엔트로피 값을 사용해 실제 업그레이드를 검증한다.
create temporary table legacy_v1_oversized_values (
  value_name text primary key,
  value_text text not null
) on commit preserve rows;

insert into legacy_v1_oversized_values (value_name, value_text)
values
(
  'huge-a',
  'legacy-huge-a-' || encode(
    gen_random_bytes(1024) || gen_random_bytes(1024)
      || gen_random_bytes(1024) || gen_random_bytes(1024),
    'base64'
  )
),
(
  'huge-b',
  'legacy-huge-b-' || encode(
    gen_random_bytes(1024) || gen_random_bytes(1024)
      || gen_random_bytes(1024) || gen_random_bytes(1024),
    'base64'
  )
),
(
  'medium-a',
  'legacy-medium-a-' || encode(
    gen_random_bytes(512) || gen_random_bytes(512),
    'base64'
  )
),
(
  'medium-b',
  'legacy-medium-b-' || encode(
    gen_random_bytes(512) || gen_random_bytes(512),
    'base64'
  )
);

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version,
  status, finished_at, metadata
) values (
  'e1000000-0000-0000-0000-000000000001',
  'fixture', 'legacy-v1-run', null, 'v1',
  'succeeded', null, '["legacy-metadata-array"]'::jsonb
);

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version,
  status, finished_at, metadata
) values
(
  'e1000000-0000-0000-0000-000000000002',
  'fixture', 'legacy-v1-long-operation',
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-a'),
  'v1', 'succeeded', now(), '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000003',
  'fixture',
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-a'),
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-b'),
  'v1', 'succeeded', now(), '{}'::jsonb
);

insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version,
  status, metadata
) values
(
  'e1000000-0000-0000-0000-000000000020',
  'fixture', 'legacy-running-scope', 'sync', 'v1', 'running', '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000021',
  'fixture', 'legacy-running-scope', 'sync', 'v1', 'running', '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000022',
  'fixture',
  'legacy-running-scope#legacy-e1000000-0000-0000-0000-000000000021',
  'sync', 'v1', 'running', '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000023',
  'fixture', 'legacy-running-long-operation',
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-b'),
  'v1', 'running', '{}'::jsonb
),
(
  'e1000000-0000-0000-0000-000000000024',
  'fixture', '', 'sync', '', 'running', '[]'::jsonb
);

insert into public.app_sessions (id, public_token, display_name)
values (
  'e2000000-0000-0000-0000-000000000001',
  'legacy-v1-upgrade-session',
  'v1 업그레이드 계약'
);

insert into public.tour_places (
  id, name, normalized_name, category, location, source_provider
) values (
  'e3000000-0000-0000-0000-000000000001',
  'v1 업그레이드 장소', 'v1업그레이드장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  'admin_upload'
);

insert into public.place_operating_hours (
  id, place_id, day_of_week, open_time, close_time, last_entry_time,
  source_kind
) values
(
  'e3100000-0000-0000-0000-000000000001',
  'e3000000-0000-0000-0000-000000000001',
  5, '22:00', '02:00', '01:00', 'manual'
),
(
  'e3100000-0000-0000-0000-000000000002',
  'e3000000-0000-0000-0000-000000000001',
  6, '02:00', '03:00', '02:30', 'manual'
);

insert into public.place_images (
  id, place_id, image_url, thumbnail_url, display_order, source_provider
) values (
  'e3200000-0000-0000-0000-000000000001',
  'e3000000-0000-0000-0000-000000000001',
  'https://images.example.test/legacy-v1.jpg', null, 0, '한국관광공사'
), (
  'e3200000-0000-0000-0000-000000000002',
  'e3000000-0000-0000-0000-000000000001',
  'https://images.example.test/legacy-v1.jpg', null, 1, '한국관광공사'
), (
  'e3200000-0000-0000-0000-000000000003',
  'e3000000-0000-0000-0000-000000000001',
  '', null, 2, ''
);

insert into public.place_images (
  id, place_id, image_url, thumbnail_url, display_order, source_provider
) values (
  'e3200000-0000-0000-0000-000000000010',
  'e3000000-0000-0000-0000-000000000001',
  'https://images.example.test/'
    || (select value_text from legacy_v1_oversized_values where value_name = 'huge-a'),
  null, 10, 'legacy-image-provider'
);

insert into public.bus_stops (
  id, external_stop_id, node_id, node_name, location, source_provider
) values
(
  'e3300000-0000-0000-0000-000000000001', 'legacy-stop-a',
  'LEGACY-A', 'legacy 다른 공급자 정류장',
  st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography,
  'OTHER'
),
(
  'e3300000-0000-0000-0000-000000000002', 'legacy-stop-b',
  'LEGACY-B', 'legacy 경로 누락 정류장',
  st_setsrid(st_makepoint(126.52, 33.52), 4326)::geography,
  'TAGO'
),
(
  'e3300000-0000-0000-0000-000000000003', '',
  '', 'legacy 빈 식별자 정류장',
  st_setsrid(st_makepoint(126.53, 33.53), 4326)::geography,
  ''
);

insert into public.bus_stops (
  id, external_stop_id, node_id, node_name, location, source_provider
) values
(
  'e3300000-0000-0000-0000-000000000010',
  'legacy-long-provider-stop', 'LEGACY-LONG-PROVIDER',
  'legacy 장문 provider 정류장',
  st_setsrid(st_makepoint(126.54, 33.54), 4326)::geography,
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-a')
),
(
  'e3300000-0000-0000-0000-000000000011',
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-b'),
  'LEGACY-COMPOSITE-STOP', 'legacy 복합 키 정류장',
  st_setsrid(st_makepoint(126.55, 33.55), 4326)::geography,
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-a')
);

insert into public.bus_routes (
  id, external_route_id, route_no, direction_name, source_provider
) values
(
  'e3400000-0000-0000-0000-000000000001',
  'legacy-route', 'L-1', 'outbound', 'TAGO'
),
(
  'e3400000-0000-0000-0000-000000000002',
  '', 'L-EMPTY', 'outbound', ''
);

insert into public.bus_routes (
  id, external_route_id, route_no, direction_name, source_provider
) values
(
  'e3400000-0000-0000-0000-000000000010',
  'legacy-long-provider-route', 'L-LONG', 'outbound',
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-a')
),
(
  'e3400000-0000-0000-0000-000000000011',
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-b'),
  'L-COMPOSITE', 'outbound',
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-a')
);

insert into public.route_stops (
  route_id, stop_id, direction_key, stop_sequence
) values
(
  'e3400000-0000-0000-0000-000000000001',
  'e3300000-0000-0000-0000-000000000001',
  'outbound', 1
),
(
  'e3400000-0000-0000-0000-000000000001',
  'e3300000-0000-0000-0000-000000000001',
  'repairable', 1
),
(
  'e3400000-0000-0000-0000-000000000002',
  'e3300000-0000-0000-0000-000000000003',
  'legacy-empty', 1
);

insert into public.route_stops (
  route_id, stop_id, direction_key, stop_sequence
) values (
  'e3400000-0000-0000-0000-000000000011',
  'e3300000-0000-0000-0000-000000000011',
  (select value_text from legacy_v1_oversized_values where value_name = 'medium-b'),
  1
);

insert into public.timetable_entries (
  id, route_id, stop_id, direction_key, service_day_type,
  departure_time, source_provider
) values
(
  'e3500000-0000-0000-0000-000000000001',
  'e3400000-0000-0000-0000-000000000001',
  'e3300000-0000-0000-0000-000000000001',
  'outbound', 'daily', '09:00', 'TAGO'
),
(
  'e3500000-0000-0000-0000-000000000002',
  'e3400000-0000-0000-0000-000000000001',
  'e3300000-0000-0000-0000-000000000002',
  'outbound', 'daily', '10:00', 'TAGO'
),
(
  'e3500000-0000-0000-0000-000000000003',
  'e3400000-0000-0000-0000-000000000001',
  'e3300000-0000-0000-0000-000000000001',
  'outbound', 'daily', '11:00', 'OTHER'
);

insert into public.timetable_entries (
  id, route_id, stop_id, direction_key, service_day_type,
  departure_time, source_provider
) values
(
  'e3500000-0000-0000-0000-000000000010',
  'e3400000-0000-0000-0000-000000000010',
  'e3300000-0000-0000-0000-000000000010',
  'outbound', 'daily', '12:00',
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-a')
),
(
  'e3500000-0000-0000-0000-000000000011',
  'e3400000-0000-0000-0000-000000000001',
  'e3300000-0000-0000-0000-000000000001',
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-b'),
  'daily', '13:00', 'TAGO'
);

insert into public.trip_plans (
  id, session_id, public_token, title, start_date, end_date,
  source_mode, data_version
) values (
  'e4000000-0000-0000-0000-000000000001',
  'e2000000-0000-0000-0000-000000000001',
  'legacy-v1-upgrade-trip', 'v1 일정',
  '2026-08-01', '2026-08-02', 'fixture', 'v1'
);

insert into public.mobility_route_snapshots (
  id, request_hash, origin_location, destination_location, transport_mode,
  duration_minutes, source_provider, source_operation, expires_at
) values (
  'e3600000-0000-0000-0000-000000000001',
  '',
  st_setsrid(st_makepoint(126.50, 33.50), 4326)::geography,
  st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography,
  'walk', 10, 'legacy-provider', null, now() + interval '1 hour'
);

insert into public.mobility_route_snapshots (
  id, request_hash, origin_location, destination_location, transport_mode,
  duration_minutes, source_provider, source_operation, expires_at
) values (
  'e3600000-0000-0000-0000-000000000010',
  'legacy-long-provider-request',
  st_setsrid(st_makepoint(126.50, 33.50), 4326)::geography,
  st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography,
  'walk', 10,
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-a'),
  (select value_text from legacy_v1_oversized_values where value_name = 'huge-b'),
  now() + interval '1 hour'
);

insert into public.trip_days (
  id, trip_plan_id, day_no, trip_date, start_time, end_time
) values
(
  'e4100000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  1, '2026-08-01', '09:00', '18:00'
),
(
  'e4100000-0000-0000-0000-000000000002',
  'e4000000-0000-0000-0000-000000000001',
  2, '2026-08-02', '09:00', '18:00'
);

insert into public.trip_schedule_versions (
  id, trip_plan_id, version_no, status, source_type
) values (
  'e4200000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  1, 'draft', 'initial'
);

insert into public.trip_items (
  id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
  item_type, place_id, title, planned_start_at, planned_end_at,
  stay_minutes, source
) values
(
  'e4300000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000001',
  'e4200000-0000-0000-0000-000000000001',
  1, 'place_visit', 'e3000000-0000-0000-0000-000000000001',
  'v1 장소 방문', '2026-08-01 09:00:00+09', '2026-08-01 10:00:00+09',
  60, 'system'
),
(
  'e4300000-0000-0000-0000-000000000002',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000002',
  'e4200000-0000-0000-0000-000000000001',
  1, 'place_visit', 'e3000000-0000-0000-0000-000000000001',
  'v1 둘째 날 장소 방문', '2026-08-02 09:00:00+09',
  '2026-08-02 10:00:00+09', 60, 'system'
),
(
  'e4300000-0000-0000-0000-000000000003',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000001',
  'e4200000-0000-0000-0000-000000000001',
  2, 'place_visit', 'e3000000-0000-0000-0000-000000000001',
  'v1 첫째 날 두 번째 장소', '2026-08-01 11:00:00+09',
  '2026-08-01 12:00:00+09', 60, 'system'
),
(
  'e4300000-0000-0000-0000-000000000004',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000002',
  'e4200000-0000-0000-0000-000000000001',
  2, 'place_visit', 'e3000000-0000-0000-0000-000000000001',
  'v1 둘째 날 두 번째 장소', '2026-08-02 11:00:00+09',
  '2026-08-02 12:00:00+09', 60, 'system'
);

insert into public.trip_legs (
  id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
  from_item_id, to_item_id, transport_mode, planned_departure_at,
  planned_arrival_at, walk_minutes, duration_minutes
) values
(
  'e4350000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000001',
  'e4200000-0000-0000-0000-000000000001',
  1, 'e4300000-0000-0000-0000-000000000001',
  'e4300000-0000-0000-0000-000000000003', 'walk',
  '2026-08-01 10:00:00+09', '2026-08-01 11:00:00+09', 60, 60
),
(
  'e4350000-0000-0000-0000-000000000002',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000002',
  'e4200000-0000-0000-0000-000000000001',
  1, 'e4300000-0000-0000-0000-000000000002',
  'e4300000-0000-0000-0000-000000000004', 'walk',
  '2026-08-02 10:00:00+09', '2026-08-02 11:00:00+09', 60, 60
);

insert into public.compute_runs (
  id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
  input_hash, contract_version, algorithm_version, facts_snapshot_at,
  source_data_version
) values
(
  'e4400000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  null,
  'e4200000-0000-0000-0000-000000000001',
  'feasibility', 'succeeded', 'legacy-v1-compute',
  'v1', 'v1', now(), 'v1'
),
(
  'e4400000-0000-0000-0000-000000000002',
  'e4000000-0000-0000-0000-000000000001',
  'e4100000-0000-0000-0000-000000000002',
  'e4200000-0000-0000-0000-000000000001',
  'feasibility', 'succeeded', 'legacy-v1-compute-day-2',
  'v1', 'v1', now(), 'v1'
);

insert into public.trip_weather_impacts (
  id, trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
  trip_item_id, trip_leg_id, impact_type, severity
) values
(
  'e4500000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  null,
  'e4200000-0000-0000-0000-000000000001',
  'e4400000-0000-0000-0000-000000000001',
  'e4300000-0000-0000-0000-000000000001', null,
  'rain', 'info'
),
(
  'e4500000-0000-0000-0000-000000000002',
  'e4000000-0000-0000-0000-000000000001',
  null,
  'e4200000-0000-0000-0000-000000000001',
  'e4400000-0000-0000-0000-000000000001',
  'e4300000-0000-0000-0000-000000000001', null,
  'rain', 'info'
),
(
  'e4500000-0000-0000-0000-000000000003',
  'e4000000-0000-0000-0000-000000000001',
  null,
  'e4200000-0000-0000-0000-000000000001',
  'e4400000-0000-0000-0000-000000000001',
  null, 'e4350000-0000-0000-0000-000000000001',
  'rain', 'info'
);

insert into public.recommendation_candidates (
  id, trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
  base_item_id, candidate_place_id, recommendation_type
) values
(
  'e4600000-0000-0000-0000-000000000001',
  'e4000000-0000-0000-0000-000000000001',
  null,
  'e4200000-0000-0000-0000-000000000001',
  'e4400000-0000-0000-0000-000000000001',
  'e4300000-0000-0000-0000-000000000001',
  'e3000000-0000-0000-0000-000000000001',
  'nearby'
),
(
  'e4600000-0000-0000-0000-000000000002',
  'e4000000-0000-0000-0000-000000000001',
  null,
  'e4200000-0000-0000-0000-000000000001',
  'e4400000-0000-0000-0000-000000000001',
  'e4300000-0000-0000-0000-000000000001',
  'e3000000-0000-0000-0000-000000000001',
  'nearby'
);

update public.trip_schedule_versions
set status = 'candidate'
where id = 'e4200000-0000-0000-0000-000000000001';
