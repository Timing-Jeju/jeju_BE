-- 일반 PostgreSQL/PostGIS Docker 검증에서만 사용하는 로컬 fixture다.
-- 운영 Supabase 또는 supabase db reset 경로에는 적용하지 않는다.
begin;

set local time zone 'Asia/Seoul';

insert into data_import_runs (
  id, source_kind, source_name, source_operation, data_version,
  status, finished_at, row_count, metadata, source_provider, source_service,
  scope_key
) values
(
  '00000000-0000-0000-0000-000000000001',
  'fixture', 'timing-jeju-place-bus-fixtures', 'seed', 'fixture-v1.1',
  'succeeded', now(), 24,
  '{"domains":["places","bus"],"fixture":true}'::jsonb,
  'fixture', 'place-bus', 'fixture:place-bus'
),
(
  '00000000-0000-0000-0000-000000000002',
  'fixture', 'timing-jeju-weather-fixtures', 'seed', 'fixture-v1.1',
  'succeeded', now(), 2,
  '{"domain":"weather","fixture":true}'::jsonb,
  'fixture', 'weather', 'fixture:weather'
),
(
  '00000000-0000-0000-0000-000000000003',
  'fixture', 'timing-jeju-mobility-fixtures', 'seed', 'fixture-v1.1',
  'succeeded', now(), 4,
  '{"domain":"directions","fixture":true}'::jsonb,
  'fixture', 'directions', 'fixture:directions'
),
(
  '00000000-0000-0000-0000-000000000011',
  'tour_api', 'TourAPI 관광지 fixture', 'areaBasedList2', 'fixture-v1.1',
  'succeeded', now(), 2, '{"domain":"places","fixture":true}'::jsonb,
  '한국관광공사', 'TourAPI 국문 관광정보', 'fixture:kto-places'
),
(
  '00000000-0000-0000-0000-000000000012',
  'tour_api', 'TourAPI 상세 fixture', 'detailIntro2', 'fixture-v1.1',
  'succeeded', now(), 4, '{"domain":"place-details","fixture":true}'::jsonb,
  '한국관광공사', 'TourAPI 상세소개', 'fixture:kto-details'
),
(
  '00000000-0000-0000-0000-000000000013',
  'tour_api', 'TourAPI 이미지 fixture', 'detailImage2', 'fixture-v1.1',
  'succeeded', now(), 2, '{"domain":"place-images","fixture":true}'::jsonb,
  '한국관광공사', 'TourAPI 이미지', 'fixture:kto-images'
),
(
  '00000000-0000-0000-0000-000000000021',
  'tago', 'TAGO 정류소 fixture', 'getSttnNoList', 'fixture-v1.1',
  'succeeded', now(), 3, '{"domain":"bus-stops","fixture":true}'::jsonb,
  'TAGO', '버스정류소정보 API', 'fixture:tago-stops'
),
(
  '00000000-0000-0000-0000-000000000022',
  'tago', 'TAGO 노선 fixture', 'getRouteInfo', 'fixture-v1.1',
  'succeeded', now(), 4, '{"domain":"bus-routes","fixture":true}'::jsonb,
  'TAGO', '버스노선정보 API', 'fixture:tago-routes'
),
(
  '00000000-0000-0000-0000-000000000023',
  'tago', 'TAGO 시간표 fixture', 'timetable', 'fixture-v1.1',
  'succeeded', now(), 3, '{"domain":"timetable","fixture":true}'::jsonb,
  'TAGO', 'fixture timetable', 'fixture:tago-timetable'
),
(
  '00000000-0000-0000-0000-000000000024',
  'tago', 'TAGO 도착 fixture', 'getSttnAcctoArvlPrearngeInfoList', 'fixture-v1.1',
  'succeeded', now(), 1, '{"domain":"bus-arrival","fixture":true}'::jsonb,
  'TAGO', '버스도착정보 API', 'fixture:tago-arrival'
),
(
  '00000000-0000-0000-0000-000000000031',
  'weather_api', 'KMA 초단기실황 fixture', 'getUltraSrtNcst', 'fixture-v1.1',
  'succeeded', now(), 1, '{"domain":"weather-observation","fixture":true}'::jsonb,
  'KMA', '기상청 단기예보', 'fixture:kma-observation'
),
(
  '00000000-0000-0000-0000-000000000032',
  'weather_api', 'KMA 단기예보 fixture', 'getVilageFcst', 'fixture-v1.1',
  'succeeded', now(), 1, '{"domain":"weather-forecast","fixture":true}'::jsonb,
  'KMA', '기상청 단기예보', 'fixture:kma-forecast'
),
(
  '00000000-0000-0000-0000-000000000033',
  'weather_api', 'KMA 초단기예보 fixture', 'getUltraSrtFcst', 'fixture-v1.1',
  'succeeded', now(), 1, '{"domain":"weather-ultra-forecast","fixture":true}'::jsonb,
  'KMA', '기상청 단기예보', 'fixture:kma-ultra-forecast'
);

insert into external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation,
  scope_key, request_hash, parser_version, payload_hash,
  raw_payload, parse_status, parsed_at
) values
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000011', '한국관광공사', 'TourAPI 국문 관광정보', 'areaBasedList2', 'fixture:kto-places', repeat('1', 64), 'fixture-parser-v1', repeat('a', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000012', '한국관광공사', 'TourAPI 상세소개', 'detailIntro2', 'fixture:kto-details', repeat('2', 64), 'fixture-parser-v1', repeat('b', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000013', '한국관광공사', 'TourAPI 이미지', 'detailImage2', 'fixture:kto-images', repeat('3', 64), 'fixture-parser-v1', repeat('c', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000021', 'TAGO', '버스정류소정보 API', 'getSttnNoList', 'fixture:tago-stops', repeat('4', 64), 'fixture-parser-v1', repeat('d', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000022', 'TAGO', '버스노선정보 API', 'getRouteInfo', 'fixture:tago-routes', repeat('5', 64), 'fixture-parser-v1', repeat('e', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000023', 'TAGO', 'fixture timetable', 'timetable', 'fixture:tago-timetable', repeat('6', 64), 'fixture-parser-v1', repeat('f', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000024', 'TAGO', '버스도착정보 API', 'getSttnAcctoArvlPrearngeInfoList', 'fixture:tago-arrival', repeat('7', 64), 'fixture-parser-v1', repeat('0', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000031', 'KMA', '기상청 단기예보', 'getUltraSrtNcst', 'fixture:kma-observation', repeat('8', 64), 'fixture-parser-v1', repeat('1', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000032', 'KMA', '기상청 단기예보', 'getVilageFcst', 'fixture:kma-forecast', repeat('9', 64), 'fixture-parser-v1', repeat('2', 64), '{"fixture":true}'::jsonb, 'parsed', now()),
('00000000-0000-0000-0000-000000000303', '00000000-0000-0000-0000-000000000033', 'KMA', '기상청 단기예보', 'getUltraSrtFcst', 'fixture:kma-ultra-forecast', repeat('a', 64), 'fixture-parser-v1', repeat('3', 64), '{"fixture":true}'::jsonb, 'parsed', now());

insert into auth.users (
  id, email, raw_app_meta_data, raw_user_meta_data, last_sign_in_at
) values (
  '09000000-0000-0000-0000-000000000001',
  'demo@timing-jeju.local',
  '{"provider":"kakao","providers":["email","kakao"]}'::jsonb,
  '{"nickname":"타이밍제주 데모"}'::jsonb,
  now()
);

insert into user_profiles (
  id, email, nickname, locale, onboarding_completed_at, last_login_at
) values (
  '09000000-0000-0000-0000-000000000001',
  'demo@timing-jeju.local',
  '타이밍제주 데모',
  'ko-KR',
  now(),
  now()
);

insert into social_accounts (
  id, user_id, provider, provider_user_id, provider_email,
  provider_email_verified, provider_nickname, scopes, last_login_at, raw_profile
) values (
  '09100000-0000-0000-0000-000000000001',
  '09000000-0000-0000-0000-000000000001',
  'kakao',
  'demo-kakao-user-001',
  'demo@timing-jeju.local',
  true,
  '타이밍제주 데모',
  array['profile_nickname', 'account_email'],
  now(),
  '{"fixture":true}'::jsonb
);

insert into app_sessions (id, user_id, public_token, display_name)
values (
  '10000000-0000-0000-0000-000000000001',
  '09000000-0000-0000-0000-000000000001',
  'demo-session-v11',
  '타이밍제주 데모'
);

insert into legal_documents (
  id, document_type, version, title, content_url, required, effective_at
) values
(
  '09200000-0000-0000-0000-000000000001',
  'terms', '1.0', '서비스 이용약관', 'https://example.local/legal/terms/1.0', true, now()
),
(
  '09200000-0000-0000-0000-000000000002',
  'privacy', '1.0', '개인정보 처리방침', 'https://example.local/legal/privacy/1.0', true, now()
),
(
  '09200000-0000-0000-0000-000000000003',
  'location', '1.0', '위치기반서비스 이용약관', 'https://example.local/legal/location/1.0', true, now()
);

insert into user_consents (user_id, legal_document_id, agreed, source)
select
  '09000000-0000-0000-0000-000000000001'::uuid,
  id,
  true,
  'web'
from legal_documents;

insert into tour_places (
  id, external_place_id, content_id, content_type_id, name, normalized_name,
  category, region_code, region_label, address, location, image_url,
  thumbnail_url, overview, recommended_stay_minutes, source_provider,
  source_service, source_snapshot_id, import_run_id
) values
(
  '20000000-0000-0000-0000-000000000001',
  'place_jeju_airport', null, null, '제주국제공항', '제주국제공항',
  'transport_hub', 'jeju-si', '제주시', '제주특별자치도 제주시 공항로 2',
  st_setsrid(st_makepoint(126.4930, 33.5066), 4326)::geography,
  null, null, '제주 여행의 주요 항공 관문', 20,
  'fixture', 'manual transport hub fixture', null, null
),
(
  '20000000-0000-0000-0000-000000000002',
  'place_seongsan_ilchulbong', '126435', '12',
  '성산일출봉 [유네스코 세계자연유산]', '성산일출봉',
  'VE', 'seongsan', '성산',
  '제주특별자치도 서귀포시 성산읍 일출로 284-12',
  st_setsrid(st_makepoint(126.9415156012, 33.4581111174), 4326)::geography,
  'https://example.local/images/seongsan.jpg',
  'https://example.local/images/seongsan-thumb.jpg',
  '제주 동쪽의 대표 오름 관광지', 70,
  '한국관광공사', 'TourAPI 국문 관광정보',
  '00000000-0000-0000-0000-000000000101',
  '00000000-0000-0000-0000-000000000011'
),
(
  '20000000-0000-0000-0000-000000000003',
  'place_seopjikoji', '127813', '12', '섭지코지', '섭지코지',
  'VE', 'seongsan', '성산',
  '제주특별자치도 서귀포시 성산읍 섭지코지로 107',
  st_setsrid(st_makepoint(126.9280674087, 33.4302500880), 4326)::geography,
  'https://example.local/images/seopjikoji.jpg',
  'https://example.local/images/seopjikoji-thumb.jpg',
  '해안 산책로와 경관을 즐길 수 있는 관광지', 60,
  '한국관광공사', 'TourAPI 국문 관광정보',
  '00000000-0000-0000-0000-000000000101',
  '00000000-0000-0000-0000-000000000011'
),
(
  '20000000-0000-0000-0000-000000000004',
  'place_hotel_seongsan_a', 'hotel-content-001', '32',
  '성산 숙소 A', '성산숙소a', 'accommodation', 'seongsan', '성산',
  '제주특별자치도 서귀포시 성산읍 성산중앙로 1',
  st_setsrid(st_makepoint(126.9340, 33.4550), 4326)::geography,
  null, null, '첫째 날 숙소 fixture', null,
  'fixture', 'accommodation fixture', null, null
),
(
  '20000000-0000-0000-0000-000000000005',
  'place_hotel_jeju_b', 'hotel-content-002', '32',
  '제주시 숙소 B', '제주시숙소b', 'accommodation', 'jeju-si', '제주시',
  '제주특별자치도 제주시 중앙로 2',
  st_setsrid(st_makepoint(126.5220, 33.5000), 4326)::geography,
  null, null, '둘째 날 숙소 fixture', null,
  'fixture', 'accommodation fixture', null, null
),
(
  '20000000-0000-0000-0000-000000000006',
  'place_seongsan_cafe', null, null, '성산 바다 카페', '성산바다카페',
  'content-type:39', 'seongsan', '성산',
  '제주특별자치도 서귀포시 성산읍 해맞이해안로 3',
  st_setsrid(st_makepoint(126.9300, 33.4410), 4326)::geography,
  null, null, '빈 시간 추천 검증용 카페', 45,
  'admin_upload', 'curated place fixture', null, null
);

insert into place_details (
  place_id, phone, homepage_url, operating_hours_text, closed_days_text,
  parking_text, admission_fee_text, intro_attributes, source_provider, source_service,
  source_snapshot_id, import_run_id
) values
(
  '20000000-0000-0000-0000-000000000002', '064-000-0001',
  'https://example.local/places/seongsan', '07:30~20:00', '기상 악화 시 통제',
  '주차 가능', '성인 5,000원', '{"contentTypeId":"12"}'::jsonb,
  '한국관광공사', 'TourAPI 상세소개',
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000012'
),
(
  '20000000-0000-0000-0000-000000000003', '064-000-0002',
  'https://example.local/places/seopjikoji', '09:00~18:00', '연중무휴',
  '주차 가능', '무료', '{"contentTypeId":"12"}'::jsonb,
  '한국관광공사', 'TourAPI 상세소개',
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000012'
),
(
  '20000000-0000-0000-0000-000000000006', '064-000-0003',
  null, '10:00~19:00', '화요일', '주차 가능', null,
  '{"curated":true}'::jsonb, 'admin_upload', 'curated place fixture', null, null
);

insert into place_operating_hours (
  id, place_id, day_of_week, open_time, close_time, last_entry_time, source_kind,
  source_snapshot_id, import_run_id
) values
(
  '21000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000002', 1, '07:30', '20:00', '19:00', 'parsed',
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000012'
),
(
  '21000000-0000-0000-0000-000000000002',
  '20000000-0000-0000-0000-000000000003', 1, '09:00', '18:00', '17:30', 'parsed',
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000012'
),
(
  '21000000-0000-0000-0000-000000000003',
  '20000000-0000-0000-0000-000000000006', 1, '10:00', '19:00', '18:30', 'manual', null, null
);

insert into place_aliases (
  place_id, alias, normalized_alias, alias_type, source_snapshot_id, import_run_id
) values
('20000000-0000-0000-0000-000000000001', '제주공항', '제주공항', 'user_query', null, null),
('20000000-0000-0000-0000-000000000002', '성산일출봉', '성산일출봉', 'official', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000011'),
('20000000-0000-0000-0000-000000000002', '성산일출봉입구', '성산일출봉입구', 'fallback_stop_keyword', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000011'),
('20000000-0000-0000-0000-000000000003', '섭지코지', '섭지코지', 'official', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000011'),
('20000000-0000-0000-0000-000000000003', '신양리', '신양리', 'fallback_stop_keyword', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000011');

insert into place_images (
  place_id, image_url, thumbnail_url, display_order, source_provider,
  source_service, source_snapshot_id, import_run_id
)
select id, image_url, thumbnail_url, 0, '한국관광공사', 'TourAPI 이미지',
  '00000000-0000-0000-0000-000000000103'::uuid,
  '00000000-0000-0000-0000-000000000013'::uuid
from tour_places
where image_url is not null;

insert into saved_places (user_id, place_id, memo, tags, target_day, priority) values
(
  '09000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000002',
  '오전에 방문', array['필수', '동쪽'], 1, 10
),
(
  '09000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000003',
  '날씨가 좋으면 방문', array['선택', '산책'], 1, 5
);

insert into bus_stops (
  id, external_stop_id, node_id, node_name, node_no, location,
  source_provider, source_service, source_snapshot_id, import_run_id
) values
(
  '30000000-0000-0000-0000-000000000001', 'stop_jeju_airport',
  'JEB405002100', '제주국제공항4', '405002100',
  st_setsrid(st_makepoint(126.492978, 33.506439), 4326)::geography,
  'TAGO', '버스정류소정보 API', '00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000021'
),
(
  '30000000-0000-0000-0000-000000000002', 'stop_seongsan',
  'JEB406000816', '성산일출봉입구[동]', '406000816',
  st_setsrid(st_makepoint(126.933033, 33.462717), 4326)::geography,
  'TAGO', '버스정류소정보 API', '00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000021'
),
(
  '30000000-0000-0000-0000-000000000003', 'stop_seopjikoji',
  'JEB406001405', '섭지코지', '406001405',
  st_setsrid(st_makepoint(126.921991, 33.436621), 4326)::geography,
  'TAGO', '버스정류소정보 API', '00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000021'
),
(
  '30000000-0000-0000-0000-000000000004', 'stop_seongsan_hotel',
  'JEB406009999', '성산 숙소 앞', '406009999',
  st_setsrid(st_makepoint(126.9341, 33.4551), 4326)::geography,
  'fixture', 'local fixture', null, null
);

insert into place_stop_links (
  place_id, stop_id, distance_meters, walk_minutes, link_method,
  source_provider, observed_at, expires_at
) values
('20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 40, 1, 'fixture', 'fixture', '2026-07-28T00:00:00Z', '2099-12-31T00:00:00Z'),
('20000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 939, 14, 'fixture', 'fixture', '2026-07-28T00:00:00Z', '2099-12-31T00:00:00Z'),
('20000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', 905, 13, 'fixture', 'fixture', '2026-07-28T00:00:00Z', '2099-12-31T00:00:00Z'),
('20000000-0000-0000-0000-000000000004', '30000000-0000-0000-0000-000000000004', 80, 2, 'fixture', 'fixture', '2026-07-28T00:00:00Z', '2099-12-31T00:00:00Z');

insert into bus_routes (
  id, external_route_id, route_no, route_type, direction_name,
  source_provider, source_service, source_snapshot_id, import_run_id
) values (
  '40000000-0000-0000-0000-000000000001', 'JEB405320112', '201',
  '간선버스', '성산 방면', 'TAGO', '버스노선정보 API',
  '00000000-0000-0000-0000-000000000202',
  '00000000-0000-0000-0000-000000000022'
);

insert into route_stops (
  route_id, stop_id, direction_key, stop_sequence, travel_minutes_from_prev,
  source_snapshot_id, import_run_id, source_provider, city_code
) values
('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'eastbound', 1, null, '00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000022', 'TAGO', '39'),
('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002', 'eastbound', 2, 80, '00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000022', 'TAGO', '39'),
('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000003', 'eastbound', 3, 18, '00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000022', 'TAGO', '39');

insert into timetable_entries (
  id, route_id, stop_id, direction_key, service_day_type, departure_time,
  trip_key, valid_from, source_provider, source_service, city_code,
  source_record_key, source_snapshot_id, import_run_id
) values
('41000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'eastbound', 'daily', '09:40', '201-0940', current_date, 'TAGO', 'fixture timetable', '39', 'fixture-201-eastbound-airport-0940', '00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000023'),
('41000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002', 'eastbound', 'daily', '11:00', '201-0940', current_date, 'TAGO', 'fixture timetable', '39', 'fixture-201-eastbound-seongsan-1100', '00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000023'),
('41000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000003', 'eastbound', 'daily', '11:18', '201-0940', current_date, 'TAGO', 'fixture timetable', '39', 'fixture-201-eastbound-seopji-1118', '00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000023');

insert into bus_arrival_snapshots (
  id, stop_id, route_id, external_route_id, route_no, direction_name,
  estimated_arrival_seconds, remaining_stops, observed_at, expires_at,
  source_provider, source_operation, source_snapshot_id, import_run_id, raw_payload
) values (
  '42000000-0000-0000-0000-000000000001',
  '30000000-0000-0000-0000-000000000002',
  '40000000-0000-0000-0000-000000000001',
  'JEB405320112', '201', '섭지코지 방면', 2520, 18,
  now(), now() + interval '30 seconds', 'TAGO',
  'getSttnAcctoArvlPrearngeInfoList',
  '00000000-0000-0000-0000-000000000204',
  '00000000-0000-0000-0000-000000000024',
  '{"fixture":true}'::jsonb
);

insert into weather_grid_points (
  id, grid_provider, nx, ny, region_name, representative_location, nearest_place_id
) values (
  '35000000-0000-0000-0000-000000000001', 'KMA', 60, 37, '서귀포시 성산읍',
  st_setsrid(st_makepoint(126.9415156012, 33.4581111174), 4326)::geography,
  '20000000-0000-0000-0000-000000000002'
);

insert into weather_observations (
  id, grid_point_id, observed_at, base_date, base_time, temperature_c,
  precipitation_mm, precipitation_type, humidity_percent, wind_speed_mps,
  source_operation, source_snapshot_id, import_run_id, raw_payload
) values (
  '35100000-0000-0000-0000-000000000001',
  '35000000-0000-0000-0000-000000000001',
  current_date + time '09:00', current_date, '09:00', 26.2, 0.0, 'none', 68, 4.2,
  'getUltraSrtNcst', '00000000-0000-0000-0000-000000000301',
  '00000000-0000-0000-0000-000000000031',
  '{"fixture":true}'::jsonb
);

insert into weather_forecasts (
  id, grid_point_id, forecasted_at, valid_at, forecast_type, forecast_version, sky_code,
  precipitation_type, precipitation_probability_percent, precipitation_amount_mm,
  temperature_c, humidity_percent, wind_speed_mps, source_operation,
  source_snapshot_id, import_run_id, raw_payload
) values
(
  '35200000-0000-0000-0000-000000000001',
  '35000000-0000-0000-0000-000000000001',
  current_date + time '08:00', current_date + time '14:00', 'short', '202608160800', 'cloudy',
  'rain', 60, 1.5, 25.8, 76, 6.1, 'getVilageFcst',
  '00000000-0000-0000-0000-000000000302',
  '00000000-0000-0000-0000-000000000032', '{"fixture":true}'::jsonb
),
(
  '35200000-0000-0000-0000-000000000002',
  '35000000-0000-0000-0000-000000000001',
  current_date + time '08:30', current_date + time '14:00', 'ultra_short', null, 'cloudy',
  'none', null, 0.0, 25.8, 76, 6.1, 'getUltraSrtFcst',
  '00000000-0000-0000-0000-000000000303',
  '00000000-0000-0000-0000-000000000033', '{"fixture":true}'::jsonb
);

insert into mobility_route_snapshots (
  id, request_hash, origin_location, destination_location, transport_mode,
  departure_at, distance_meters, duration_minutes, estimated_fare,
  source_provider, source_operation, route_summary, expires_at, raw_payload,
  import_run_id
) values
(
  '43000000-0000-0000-0000-000000000001', 'fixture-airport-seongsan-bus',
  st_setsrid(st_makepoint(126.4930, 33.5066), 4326)::geography,
  st_setsrid(st_makepoint(126.9415, 33.4581), 4326)::geography,
  'public_transit', current_date + time '09:20', 47000, 105, 3000,
  'fixture', 'route', '{"routeNo":"201","transfers":0}'::jsonb,
  now() + interval '1 hour', '{"fixture":true}'::jsonb, null
),
(
  '43000000-0000-0000-0000-000000000002', 'fixture-seongsan-seopji-bus',
  st_setsrid(st_makepoint(126.9415, 33.4581), 4326)::geography,
  st_setsrid(st_makepoint(126.9281, 33.4303), 4326)::geography,
  'public_transit', current_date + time '12:20', 5100, 42, 1250,
  'fixture', 'route', '{"routeNo":"201","waitMinutes":22}'::jsonb,
  now() + interval '1 hour', '{"fixture":true}'::jsonb, null
),
(
  '43000000-0000-0000-0000-000000000003', 'fixture-seopji-hotel-taxi',
  st_setsrid(st_makepoint(126.9281, 33.4303), 4326)::geography,
  st_setsrid(st_makepoint(126.9340, 33.4550), 4326)::geography,
  'taxi', current_date + time '15:10', 4800, 12, 8500,
  'fixture', 'route', '{"traffic":"normal"}'::jsonb,
  now() + interval '1 hour', '{"fixture":true}'::jsonb, null
),
(
  '43000000-0000-0000-0000-000000000004', 'fixture-hotel-cafe-car',
  st_setsrid(st_makepoint(126.9340, 33.4550), 4326)::geography,
  st_setsrid(st_makepoint(126.9300, 33.4410), 4326)::geography,
  'rental_car', (current_date + 1) + time '10:00', 2600, 8, null,
  'fixture', 'route', '{"parking":"available"}'::jsonb,
  now() + interval '1 hour', '{"fixture":true}'::jsonb, null
);

insert into trip_plans (
  id, user_id, session_id, public_token, title, status, input_text,
  start_date, end_date, user_pace, total_score, source_mode, data_version
) values (
  '50000000-0000-0000-0000-000000000001',
  '09000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000001',
  'demo-east-jeju-v11',
  '제주 동쪽 2박 3일',
  'draft',
  '공항 도착 후 성산일출봉과 섭지코지를 방문하고 숙소 두 곳을 이용할래',
  current_date,
  current_date + 2,
  'normal',
  81,
  'fixture',
  'fixture-v1.1'
);

insert into trip_preferences (
  trip_plan_id, preferred_categories, arrival_region_code, departure_region_code,
  preferred_region_codes, start_place_id, end_place_id, raw_answers
) values (
  '50000000-0000-0000-0000-000000000001',
  array['tourist_attraction', 'cafe'], 'jeju-si', 'jeju-si', array['seongsan', 'jeju-si'],
  '20000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001',
  '{"pace":"normal","generationMode":"structured","dayGeneration":"one_click"}'::jsonb
);

insert into trip_transport_modes (
  trip_plan_id, transport_mode, priority, is_primary
) values
('50000000-0000-0000-0000-000000000001', 'public_transit', 1, true),
('50000000-0000-0000-0000-000000000001', 'rental_car', 2, false),
('50000000-0000-0000-0000-000000000001', 'taxi', 3, false);

insert into trip_place_preferences (
  trip_plan_id, place_id, preference_type, target_day_no, priority, source
) values
('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'must_visit', 1, 10, 'saved_place'),
('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', 'must_visit', null, 5, 'saved_place');

insert into trip_transport_events (
  id, trip_plan_id, event_type, transport_type, terminal_place_id,
  scheduled_at, transport_number, source, note
) values
(
  '50100000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  'arrival', 'flight', '20000000-0000-0000-0000-000000000001',
  current_date + time '09:00', 'KE1001', 'user_input', '수하물 수령 20분 반영'
),
(
  '50100000-0000-0000-0000-000000000002',
  '50000000-0000-0000-0000-000000000001',
  'departure', 'flight', '20000000-0000-0000-0000-000000000001',
  (current_date + 2) + time '19:00', 'KE1002', 'user_input', '출발 90분 전 공항 도착'
);

insert into trip_accommodations (
  id, trip_plan_id, place_id, check_in_date, check_out_date,
  check_in_time, check_out_time, sequence_no, source
) values
(
  '50200000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000004',
  current_date, current_date + 1, '15:00', '11:00', 1, 'user_input'
),
(
  '50200000-0000-0000-0000-000000000002',
  '50000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000005',
  current_date + 1, current_date + 2, '15:00', '11:00', 2, 'user_input'
);

insert into trip_days (
  id, trip_plan_id, day_no, trip_date, start_time, end_time, title, safety_score
) values
('51000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 1, current_date, '09:00', '21:00', '성산과 섭지코지', 81),
('51000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', 2, current_date + 1, '09:00', '21:00', '성산에서 제주시로', 88),
('51000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', 3, current_date + 2, '09:00', '19:00', '제주시와 출발', 92);

insert into trip_schedule_versions (
  id, trip_plan_id, version_no, base_schedule_version_id, status,
  source_type, summary, resulting_score, created_by_user_id, applied_at
) values
(
  '60000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001', 1, null, 'draft', 'initial',
  '사용자 입력을 바탕으로 만든 최초 일정', 81,
  '09000000-0000-0000-0000-000000000001', null
),
(
  '60000000-0000-0000-0000-000000000002',
  '50000000-0000-0000-0000-000000000001', 2,
  '60000000-0000-0000-0000-000000000001', 'draft', 'ai_generation',
  '대기 시간을 줄인 AI 생성 후보', 88,
  '09000000-0000-0000-0000-000000000001', null
),
(
  '60000000-0000-0000-0000-000000000003',
  '50000000-0000-0000-0000-000000000001', 3,
  '60000000-0000-0000-0000-000000000001', 'draft', 'recovery',
  '섭지코지를 둘째 날로 이동한 복구 후보', 90,
  '09000000-0000-0000-0000-000000000001', null
);

insert into trip_items (
  id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
  item_type, place_id, title, planned_start_at, planned_end_at,
  stay_minutes, buffer_after_minutes, required, source, facts
) values
-- Active version: day 1
('61000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 1, 'arrival', '20000000-0000-0000-0000-000000000001', '제주 도착', current_date + time '09:00', current_date + time '09:20', 20, 10, true, 'user_input', '{"transportNumber":"KE1001"}'::jsonb),
('61000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 2, 'place_visit', '20000000-0000-0000-0000-000000000002', '성산일출봉', current_date + time '11:20', current_date + time '12:30', 70, 10, true, 'ai_generated', '{"recommendedStayMinutes":70}'::jsonb),
('61000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 3, 'place_visit', '20000000-0000-0000-0000-000000000003', '섭지코지', current_date + time '13:20', current_date + time '14:20', 60, 10, false, 'ai_generated', '{"recommendedStayMinutes":60}'::jsonb),
('61000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 4, 'accommodation', '20000000-0000-0000-0000-000000000004', '성산 숙소 A', current_date + time '15:00', current_date + time '21:00', 360, 0, true, 'user_input', '{}'::jsonb),
-- Active version: day 2 (sequence 1 is valid again)
('61000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', 1, 'accommodation', '20000000-0000-0000-0000-000000000004', '성산 숙소 A 체크아웃', (current_date + 1) + time '09:00', (current_date + 1) + time '10:00', 60, 10, true, 'user_input', '{}'::jsonb),
('61000000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', 2, 'meal', '20000000-0000-0000-0000-000000000006', '성산 바다 카페', (current_date + 1) + time '10:20', (current_date + 1) + time '11:05', 45, 10, false, 'ai_generated', '{}'::jsonb),
('61000000-0000-0000-0000-000000000007', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', 3, 'accommodation', '20000000-0000-0000-0000-000000000005', '제주시 숙소 B', (current_date + 1) + time '16:00', (current_date + 1) + time '21:00', 300, 0, true, 'user_input', '{}'::jsonb),
-- Active version: day 3
('61000000-0000-0000-0000-000000000008', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000001', 1, 'accommodation', '20000000-0000-0000-0000-000000000005', '제주시 숙소 B 체크아웃', (current_date + 2) + time '09:00', (current_date + 2) + time '11:00', 120, 20, true, 'user_input', '{}'::jsonb),
('61000000-0000-0000-0000-000000000009', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000001', 2, 'departure', '20000000-0000-0000-0000-000000000001', '제주 출발', (current_date + 2) + time '17:30', (current_date + 2) + time '19:00', 90, 0, true, 'user_input', '{"transportNumber":"KE1002"}'::jsonb),
-- AI candidate version: complete schedule copy with adjusted day 1
('61100000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 1, 'arrival', '20000000-0000-0000-0000-000000000001', '제주 도착', current_date + time '09:00', current_date + time '09:20', 20, 10, true, 'user_input', '{}'::jsonb),
('61100000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 2, 'place_visit', '20000000-0000-0000-0000-000000000003', '섭지코지', current_date + time '10:50', current_date + time '11:50', 60, 10, false, 'ai_generated', '{}'::jsonb),
('61100000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 3, 'place_visit', '20000000-0000-0000-0000-000000000002', '성산일출봉', current_date + time '12:20', current_date + time '13:30', 70, 10, true, 'ai_generated', '{}'::jsonb),
('61100000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 4, 'accommodation', '20000000-0000-0000-0000-000000000004', '성산 숙소 A', current_date + time '15:00', current_date + time '21:00', 360, 0, true, 'user_input', '{}'::jsonb),
('61100000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000002', 1, 'accommodation', '20000000-0000-0000-0000-000000000004', '성산 숙소 A 체크아웃', (current_date + 1) + time '09:00', (current_date + 1) + time '10:00', 60, 10, true, 'user_input', '{}'::jsonb),
('61100000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000002', 2, 'meal', '20000000-0000-0000-0000-000000000006', '성산 바다 카페', (current_date + 1) + time '10:20', (current_date + 1) + time '11:05', 45, 10, false, 'ai_generated', '{}'::jsonb),
('61100000-0000-0000-0000-000000000007', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000002', 3, 'accommodation', '20000000-0000-0000-0000-000000000005', '제주시 숙소 B', (current_date + 1) + time '16:00', (current_date + 1) + time '21:00', 300, 0, true, 'user_input', '{}'::jsonb),
('61100000-0000-0000-0000-000000000008', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000002', 1, 'accommodation', '20000000-0000-0000-0000-000000000005', '제주시 숙소 B 체크아웃', (current_date + 2) + time '09:00', (current_date + 2) + time '11:00', 120, 20, true, 'user_input', '{}'::jsonb),
('61100000-0000-0000-0000-000000000009', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000002', 2, 'departure', '20000000-0000-0000-0000-000000000001', '제주 출발', (current_date + 2) + time '17:30', (current_date + 2) + time '19:00', 90, 0, true, 'user_input', '{}'::jsonb),
-- Recovery candidate: move Seopjikoji from day 1 to day 2
('61200000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000003', 1, 'arrival', '20000000-0000-0000-0000-000000000001', '제주 도착', current_date + time '09:00', current_date + time '09:20', 20, 10, true, 'user_input', '{}'::jsonb),
('61200000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000003', 2, 'place_visit', '20000000-0000-0000-0000-000000000002', '성산일출봉', current_date + time '11:20', current_date + time '12:30', 70, 20, true, 'recovery', '{}'::jsonb),
('61200000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000003', 3, 'accommodation', '20000000-0000-0000-0000-000000000004', '성산 숙소 A', current_date + time '14:00', current_date + time '21:00', 420, 0, true, 'recovery', '{}'::jsonb),
('61200000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 1, 'accommodation', '20000000-0000-0000-0000-000000000004', '성산 숙소 A 체크아웃', (current_date + 1) + time '09:00', (current_date + 1) + time '10:00', 60, 10, true, 'user_input', '{}'::jsonb),
('61200000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 2, 'place_visit', '20000000-0000-0000-0000-000000000003', '섭지코지', (current_date + 1) + time '10:20', (current_date + 1) + time '11:20', 60, 10, false, 'recovery', '{}'::jsonb),
('61200000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 3, 'meal', '20000000-0000-0000-0000-000000000006', '성산 바다 카페', (current_date + 1) + time '11:40', (current_date + 1) + time '12:25', 45, 10, false, 'recovery', '{}'::jsonb),
('61200000-0000-0000-0000-000000000007', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 4, 'accommodation', '20000000-0000-0000-0000-000000000005', '제주시 숙소 B', (current_date + 1) + time '16:00', (current_date + 1) + time '21:00', 300, 0, true, 'user_input', '{}'::jsonb),
('61200000-0000-0000-0000-000000000008', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000003', 1, 'accommodation', '20000000-0000-0000-0000-000000000005', '제주시 숙소 B 체크아웃', (current_date + 2) + time '09:00', (current_date + 2) + time '11:00', 120, 20, true, 'user_input', '{}'::jsonb),
('61200000-0000-0000-0000-000000000009', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000003', 2, 'departure', '20000000-0000-0000-0000-000000000001', '제주 출발', (current_date + 2) + time '17:30', (current_date + 2) + time '19:00', 90, 0, true, 'user_input', '{}'::jsonb);

insert into trip_legs (
  id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
  from_item_id, to_item_id, transport_mode, origin_stop_id, destination_stop_id,
  route_id, mobility_route_snapshot_id, planned_departure_at, planned_arrival_at,
  walk_minutes, wait_minutes, ride_minutes, transfer_minutes, duration_minutes,
  buffer_minutes, distance_meters, estimated_fare, risk_score, facts
) values
('62000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 1, '61000000-0000-0000-0000-000000000001', '61000000-0000-0000-0000-000000000002', 'public_transit', '30000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', '43000000-0000-0000-0000-000000000001', current_date + time '09:30', current_date + time '11:15', 8, 12, 80, 5, 105, 5, 47000, 3000, 81, '{"routeNo":"201"}'::jsonb),
('62000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 2, '61000000-0000-0000-0000-000000000002', '61000000-0000-0000-0000-000000000003', 'public_transit', '30000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', '43000000-0000-0000-0000-000000000002', current_date + time '12:40', current_date + time '13:20', 10, 22, 8, 0, 40, 5, 5100, 1250, 62, '{"routeNo":"201","lowFrequency":true}'::jsonb),
('62000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 3, '61000000-0000-0000-0000-000000000003', '61000000-0000-0000-0000-000000000004', 'taxi', null, null, null, '43000000-0000-0000-0000-000000000003', current_date + time '14:30', current_date + time '14:42', 0, 0, 12, 0, 12, 8, 4800, 8500, 95, '{}'::jsonb),
('62000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', 1, '61000000-0000-0000-0000-000000000005', '61000000-0000-0000-0000-000000000006', 'rental_car', null, null, null, '43000000-0000-0000-0000-000000000004', (current_date + 1) + time '10:10', (current_date + 1) + time '10:18', 2, 0, 6, 0, 8, 2, 2600, null, 96, '{}'::jsonb),
('62000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', 2, '61000000-0000-0000-0000-000000000006', '61000000-0000-0000-0000-000000000007', 'taxi', null, null, null, null, (current_date + 1) + time '14:50', (current_date + 1) + time '15:50', 0, 5, 55, 0, 60, 10, 45000, 55000, 85, '{}'::jsonb),
('62000000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000001', 1, '61000000-0000-0000-0000-000000000008', '61000000-0000-0000-0000-000000000009', 'public_transit', null, null, null, null, (current_date + 2) + time '16:20', (current_date + 2) + time '17:20', 8, 12, 40, 0, 60, 10, 6000, 1500, 92, '{}'::jsonb),
-- AI candidate version: complete consecutive movement chain
('62100000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 1, '61100000-0000-0000-0000-000000000001', '61100000-0000-0000-0000-000000000002', 'public_transit', null, null, null, null, current_date + time '09:30', current_date + time '10:45', 8, 10, 57, 0, 75, 5, 45000, 2800, 90, '{"candidate":true}'::jsonb),
('62100000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 2, '61100000-0000-0000-0000-000000000002', '61100000-0000-0000-0000-000000000003', 'taxi', null, null, null, null, current_date + time '11:55', current_date + time '12:15', 0, 0, 20, 0, 20, 5, 12000, 17000, 94, '{"candidate":true}'::jsonb),
('62100000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 3, '61100000-0000-0000-0000-000000000003', '61100000-0000-0000-0000-000000000004', 'public_transit', null, null, null, null, current_date + time '13:40', current_date + time '14:50', 8, 12, 50, 0, 70, 10, 39000, 1800, 89, '{"candidate":true}'::jsonb),
('62100000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000002', 1, '61100000-0000-0000-0000-000000000005', '61100000-0000-0000-0000-000000000006', 'rental_car', null, null, null, null, (current_date + 1) + time '10:05', (current_date + 1) + time '10:15', 2, 0, 8, 0, 10, 5, 2600, null, 96, '{"candidate":true}'::jsonb),
('62100000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000002', 2, '61100000-0000-0000-0000-000000000006', '61100000-0000-0000-0000-000000000007', 'taxi', null, null, null, null, (current_date + 1) + time '14:50', (current_date + 1) + time '15:50', 0, 5, 55, 0, 60, 10, 45000, 55000, 85, '{"candidate":true}'::jsonb),
('62100000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000002', 1, '61100000-0000-0000-0000-000000000008', '61100000-0000-0000-0000-000000000009', 'public_transit', null, null, null, null, (current_date + 2) + time '16:20', (current_date + 2) + time '17:20', 8, 12, 40, 0, 60, 10, 6000, 1500, 92, '{"candidate":true}'::jsonb),
-- Recovery candidate: preserve ordering and move only the affected place to day 2
('62200000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000003', 1, '61200000-0000-0000-0000-000000000001', '61200000-0000-0000-0000-000000000002', 'public_transit', null, null, null, null, current_date + time '09:30', current_date + time '11:15', 8, 12, 80, 5, 105, 5, 47000, 3000, 88, '{"recovery":true}'::jsonb),
('62200000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000003', 2, '61200000-0000-0000-0000-000000000002', '61200000-0000-0000-0000-000000000003', 'taxi', null, null, null, null, current_date + time '12:40', current_date + time '13:50', 0, 0, 70, 0, 70, 10, 43000, 50000, 90, '{"recovery":true}'::jsonb),
('62200000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 1, '61200000-0000-0000-0000-000000000004', '61200000-0000-0000-0000-000000000005', 'rental_car', null, null, null, null, (current_date + 1) + time '10:05', (current_date + 1) + time '10:15', 2, 0, 8, 0, 10, 5, 2600, null, 96, '{"recovery":true}'::jsonb),
('62200000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 2, '61200000-0000-0000-0000-000000000005', '61200000-0000-0000-0000-000000000006', 'walk', null, null, null, null, (current_date + 1) + time '11:25', (current_date + 1) + time '11:35', 10, 0, 0, 0, 10, 5, 700, null, 98, '{"recovery":true}'::jsonb),
('62200000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000003', 3, '61200000-0000-0000-0000-000000000006', '61200000-0000-0000-0000-000000000007', 'taxi', null, null, null, null, (current_date + 1) + time '14:50', (current_date + 1) + time '15:50', 0, 5, 55, 0, 60, 10, 45000, 55000, 90, '{"recovery":true}'::jsonb),
('62200000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000003', 1, '61200000-0000-0000-0000-000000000008', '61200000-0000-0000-0000-000000000009', 'public_transit', null, null, null, null, (current_date + 2) + time '16:20', (current_date + 2) + time '17:20', 8, 12, 40, 0, 60, 10, 6000, 1500, 92, '{"recovery":true}'::jsonb);

insert into trip_item_progress (
  trip_plan_id, schedule_version_id, trip_item_id, status,
  actual_started_at, actual_arrived_at, actual_completed_at
) values
(
  '50000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000001',
  'completed', current_date + time '09:00', current_date + time '09:00', current_date + time '09:20'
),
(
  '50000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000002',
  'arrived', current_date + time '11:15', current_date + time '11:20', null
);

insert into trip_item_progress (
  trip_plan_id, schedule_version_id, trip_item_id, status
)
select
  trip_plan_id,
  schedule_version_id,
  id,
  'planned'
from trip_items
where schedule_version_id = '60000000-0000-0000-0000-000000000001'
  and id not in (
    '61000000-0000-0000-0000-000000000001',
    '61000000-0000-0000-0000-000000000002'
  );

insert into trip_execution_events (
  id, trip_plan_id, schedule_version_id, trip_item_id, trip_leg_id,
  event_type, client_event_id, location, occurred_at, metadata
) values (
  '62500000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000002',
  '62000000-0000-0000-0000-000000000001',
  'arrived', 'demo-arrive-seongsan-001',
  st_setsrid(st_makepoint(126.9415, 33.4581), 4326)::geography,
  current_date + time '11:20',
  '{"source":"mobile","accuracyMeters":18}'::jsonb
);

insert into itinerary_generation_runs (
  id, trip_plan_id, trip_day_id, base_schedule_version_id, input_mode,
  status, structured_input, contract_version, algorithm_version, model,
  idempotency_key, requested_by_user_id, started_at, completed_at
) values (
  '64000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '51000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  'structured', 'succeeded',
  '{"dayNo":1,"preferredCategories":["tourist_attraction"],"transportModes":["public_transit","taxi"]}'::jsonb,
  'itinerary-generation.v1', 'scheduler-2026-07', 'fixture-model',
  'demo-generate-day-1', '09000000-0000-0000-0000-000000000001',
  now() - interval '3 seconds', now()
);

insert into itinerary_generation_candidates (
  id, trip_plan_id, generation_run_id, schedule_version_id,
  rank_no, score, explanation
) values (
  '64100000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '64000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000002',
  1, 88, '섭지코지를 먼저 방문해 버스 대기 위험을 줄였습니다.'
);

insert into compute_runs (
  id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
  input_hash, contract_version, algorithm_version, facts_snapshot_at,
  source_data_version, result_source, result_summary, started_at, completed_at
) values
(
  '63000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '51000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  'feasibility', 'succeeded', 'fixture-feasibility-v1',
  'feasibility.v1', 'risk-engine-2026-07', now(), 'fixture-v1.1', 'computed',
  '{"overallStatus":"caution","score":81}'::jsonb,
  now() - interval '2 seconds', now()
),
(
  '63000000-0000-0000-0000-000000000002',
  '50000000-0000-0000-0000-000000000001',
  '51000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  'recovery', 'succeeded', 'fixture-recovery-v1',
  'recovery.v1', 'recovery-engine-2026-07', now(), 'fixture-v1.1', 'computed',
  '{"optionCount":1,"bestScore":90}'::jsonb,
  now() - interval '2 seconds', now()
);

insert into risk_events (
  id, trip_plan_id, schedule_version_id, compute_run_id, trip_leg_id,
  event_type, severity, score_delta, wait_risk_minutes, reason_code, computed_facts
) values (
  '63100000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '63000000-0000-0000-0000-000000000001',
  '62000000-0000-0000-0000-000000000002',
  'low_frequency', 'yellow', -19, 42, 'LOW_FREQUENCY_ROUTE',
  '{"routeNo":"201","missedBusWaitMinutes":42}'::jsonb
);

insert into trip_weather_impacts (
  id, trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
  trip_item_id, weather_forecast_id, impact_type, severity, score_delta,
  recommendation_text, computed_facts
) values (
  '63200000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '51000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '63000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000003',
  '35200000-0000-0000-0000-000000000001',
  'rain', 'yellow', -8,
  '섭지코지 방문 시간에 비 예보가 있어 우비를 준비하세요.',
  '{"precipitationProbabilityPercent":60,"precipitationAmountMm":1.5}'::jsonb
);

insert into recommendation_candidates (
  id, trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
  base_item_id, candidate_place_id, recommendation_type, available_gap_minutes,
  required_total_minutes, travel_minutes, stay_minutes, safety_buffer_minutes,
  score, reason_code, facts
) values (
  '63300000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '51000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '63000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000002',
  '20000000-0000-0000-0000-000000000006',
  'spare_time', 90, 65, 10, 45, 10, 78, 'PLACE_FITS_GAP',
  '{"distanceMeters":1800}'::jsonb
);

insert into recovery_options (
  id, trip_plan_id, compute_run_id, trigger_risk_event_id,
  base_schedule_version_id, proposed_schedule_version_id, option_type,
  status, title, explanation, impact_minutes, resulting_score, change_summary,
  expires_at
) values (
  '65000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '63000000-0000-0000-0000-000000000002',
  '63100000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000003',
  'move_to_another_day', 'proposed',
  '섭지코지를 둘째 날로 이동',
  '첫째 날의 긴 버스 대기를 피하고 기존 필수 장소와 숙소는 유지합니다.',
  20, 90,
  '{"changedItemCount":1,"preservedRequiredItems":true}'::jsonb,
  now() + interval '30 minutes'
);

insert into recovery_option_changes (
  id, recovery_option_id, base_schedule_version_id, proposed_schedule_version_id,
  change_order, action, source_item_id, proposed_item_id,
  before_value, after_value, reason_code
) values (
  '65100000-0000-0000-0000-000000000001',
  '65000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000003',
  1, 'move_day',
  '61000000-0000-0000-0000-000000000003',
  '61200000-0000-0000-0000-000000000005',
  '{"dayNo":1,"startTime":"13:20"}'::jsonb,
  '{"dayNo":2,"startTime":"10:20"}'::jsonb,
  'AVOID_LOW_FREQUENCY_ROUTE'
);

insert into live_state_snapshots (
  id, trip_plan_id, schedule_version_id, active_item_id, active_leg_id,
  compute_run_id, status, current_location, current_place_id, next_action, facts
) values (
  '66000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000002',
  '62000000-0000-0000-0000-000000000002',
  '63000000-0000-0000-0000-000000000001',
  'yellow',
  st_setsrid(st_makepoint(126.9415, 33.4581), 4326)::geography,
  '20000000-0000-0000-0000-000000000002',
  '12:38까지 정류장으로 출발하세요.',
  '{"leaveByTime":"12:38","busWaitMinutes":22}'::jsonb
);

insert into mcp_compute_call_logs (
  id, user_id, trip_plan_id, compute_run_id, generation_run_id,
  request_id, tool_name, status, contract_version, provider, model,
  request_payload_redacted, response_payload_redacted, latency_ms
) values
(
  '67000000-0000-0000-0000-000000000001',
  '09000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  null, '64000000-0000-0000-0000-000000000001',
  'req-generate-day-001', 'generate_day_itinerary', 'succeeded',
  'itinerary-generation.v1', 'fastapi-mcp', 'fixture-model',
  '{"factsRef":"sha256:fixture"}'::jsonb,
  '{"candidateCount":1}'::jsonb, 1320
),
(
  '67000000-0000-0000-0000-000000000002',
  '09000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '63000000-0000-0000-0000-000000000001', null,
  'req-feasibility-001', 'calculate_feasibility', 'succeeded',
  'feasibility.v1', 'fastapi-mcp', null,
  '{"factsRef":"sha256:fixture"}'::jsonb,
  '{"overallStatus":"caution","score":81}'::jsonb, 86
),
(
  '67000000-0000-0000-0000-000000000003',
  '09000000-0000-0000-0000-000000000001',
  '50000000-0000-0000-0000-000000000001',
  '63000000-0000-0000-0000-000000000002', null,
  'req-recovery-001', 'generate_recovery_options', 'succeeded',
  'recovery.v1', 'fastapi-mcp', null,
  '{"factsRef":"sha256:fixture"}'::jsonb,
  '{"optionCount":1}'::jsonb, 114
);

update trip_schedule_versions
set
  status = case
    when id = '60000000-0000-0000-0000-000000000001' then 'active'
    else 'candidate'
  end,
  applied_at = case
    when id = '60000000-0000-0000-0000-000000000001' then now()
    else null
  end
where trip_plan_id = '50000000-0000-0000-0000-000000000001';

update trip_plans
set
  active_schedule_version_id = '60000000-0000-0000-0000-000000000001',
  status = 'planned',
  updated_at = now()
where id = '50000000-0000-0000-0000-000000000001';

insert into place_stay_policy_versions (
  version, status, payload_hash, effective_at, imported_at
) values (
  'fixture-v1', 'active', repeat('65', 32), now(), now()
);

insert into place_stay_policies (
  version, scope, category, place_id, minutes, source, updated_at
) values
(
  'fixture-v1', 'category_default', 'VE', null,
  75, 'app_curation', now()
),
(
  'fixture-v1', 'place_override', null,
  '20000000-0000-0000-0000-000000000002',
  70, 'app_curation', now()
);

commit;
