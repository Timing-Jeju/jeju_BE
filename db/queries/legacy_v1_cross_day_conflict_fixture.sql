\set ON_ERROR_STOP on

-- v1에서는 각각 합법이지만 최신 교차 요일 규칙에서는 동시에 보존할 수 없는 조합이다.
set time zone 'Asia/Seoul';

insert into public.tour_places (
  id, name, normalized_name, category, location, source_provider
) values (
  'ea000000-0000-0000-0000-000000000001',
  'v1 교차 요일 충돌 장소', 'v1교차요일충돌장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  'admin_upload'
);

insert into public.place_operating_hours (
  id, place_id, day_of_week, open_time, close_time, last_entry_time,
  source_kind
) values
(
  'ea100000-0000-0000-0000-000000000001',
  'ea000000-0000-0000-0000-000000000001',
  5, '22:00', '02:00', '01:00', 'manual'
),
(
  'ea100000-0000-0000-0000-000000000002',
  'ea000000-0000-0000-0000-000000000001',
  6, '01:00', '03:00', '02:30', 'manual'
);
