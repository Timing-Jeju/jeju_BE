\set ON_ERROR_STOP on

insert into public.tour_places (
  id, name, normalized_name, category, location, source_provider
) values (
  'e7500000-0000-0000-0000-000000000001',
  '영업 상태 겹침 장소', '영업상태겹침장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.51, 33.51), 4326)::geography,
  'fixture'
);

-- .100의 open-only exclusion은 closed 행을 제외하므로 둘 다 저장할 수 있다.
insert into public.place_operating_hours (
  id, place_id, day_of_week, interval_no, is_closed, open_time, close_time,
  valid_from, valid_to, source_kind
) values
(
  'e7600000-0000-0000-0000-000000000001',
  'e7500000-0000-0000-0000-000000000001',
  1, 1, false, '09:00', '17:00', '2026-01-01', '2026-12-31', 'manual'
),
(
  'e7600000-0000-0000-0000-000000000002',
  'e7500000-0000-0000-0000-000000000001',
  1, 2, true, null, null, '2026-06-01', null, 'manual'
);
