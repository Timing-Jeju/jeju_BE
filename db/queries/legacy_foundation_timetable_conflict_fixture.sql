\set ON_ERROR_STOP on

insert into public.bus_stops (
  id, external_stop_id, node_id, node_name, location,
  source_provider, source_service, city_code
) values (
  'e7200000-0000-0000-0000-000000000001',
  'legacy-overlap-stop', 'LEGACY-OVERLAP-STOP', '겹침 정류장',
  st_setsrid(st_makepoint(126.50, 33.50), 4326)::geography,
  'TAGO', 'legacy', '39'
);

insert into public.bus_routes (
  id, external_route_id, route_no, direction_name,
  source_provider, source_service, city_code
) values (
  'e7300000-0000-0000-0000-000000000001',
  'legacy-overlap-route', 'L-OVERLAP', 'outbound',
  'TAGO', 'legacy', '39'
);

insert into public.route_stops (
  route_id, stop_id, direction_key, stop_sequence
) values (
  'e7300000-0000-0000-0000-000000000001',
  'e7200000-0000-0000-0000-000000000001',
  'outbound', 1
);

-- .100의 UNIQUE(provider, record, valid_from)는 통과하지만 두 기간은 겹친다.
insert into public.timetable_entries (
  id, route_id, stop_id, direction_key, service_day_type, departure_time,
  valid_from, valid_to, source_provider, source_record_key
) values
(
  'e7400000-0000-0000-0000-000000000001',
  'e7300000-0000-0000-0000-000000000001',
  'e7200000-0000-0000-0000-000000000001',
  'outbound', 'daily', '09:00', '2026-01-01', '2026-12-31',
  'TAGO', 'legacy-overlap-record'
),
(
  'e7400000-0000-0000-0000-000000000002',
  'e7300000-0000-0000-0000-000000000001',
  'e7200000-0000-0000-0000-000000000001',
  'outbound', 'daily', '10:00', '2026-06-01', null,
  'TAGO', 'legacy-overlap-record'
);
