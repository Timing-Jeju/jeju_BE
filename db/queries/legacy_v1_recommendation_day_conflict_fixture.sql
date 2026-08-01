\set ON_ERROR_STOP on

-- v1의 개별 FK는 모두 만족하지만 추천 결과 day와 계산/item day가 다른 상태다.
insert into public.app_sessions (id, public_token, display_name)
values (
  'ed000000-0000-0000-0000-000000000001',
  'legacy-recommendation-day-conflict',
  'v1 추천 Day 충돌 계약'
);

insert into public.tour_places (
  id, name, normalized_name, category, location, source_provider
) values (
  'ed000000-0000-0000-0000-000000000010',
  'v1 추천 Day 충돌 장소', 'v1추천day충돌장소', 'tourist_attraction',
  st_setsrid(st_makepoint(126.5, 33.5), 4326)::geography,
  'fixture'
);

insert into public.trip_plans (
  id, session_id, public_token, title, start_date, end_date,
  source_mode, data_version
) values (
  'ed000000-0000-0000-0000-000000000020',
  'ed000000-0000-0000-0000-000000000001',
  'legacy-recommendation-day-conflict-trip', 'v1 추천 Day 충돌',
  '2026-08-01', '2026-08-02', 'fixture', 'v1'
);

insert into public.trip_days (
  id, trip_plan_id, day_no, trip_date, start_time, end_time
) values
(
  'ed000000-0000-0000-0000-000000000031',
  'ed000000-0000-0000-0000-000000000020',
  1, '2026-08-01', '09:00', '18:00'
),
(
  'ed000000-0000-0000-0000-000000000032',
  'ed000000-0000-0000-0000-000000000020',
  2, '2026-08-02', '09:00', '18:00'
);

insert into public.trip_schedule_versions (
  id, trip_plan_id, version_no, status, source_type
) values (
  'ed000000-0000-0000-0000-000000000040',
  'ed000000-0000-0000-0000-000000000020',
  1, 'draft', 'initial'
);

insert into public.trip_items (
  id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
  item_type, place_id, title, planned_start_at, planned_end_at,
  stay_minutes, source
) values (
  'ed000000-0000-0000-0000-000000000050',
  'ed000000-0000-0000-0000-000000000020',
  'ed000000-0000-0000-0000-000000000031',
  'ed000000-0000-0000-0000-000000000040',
  1, 'place_visit', 'ed000000-0000-0000-0000-000000000010',
  'Day 1 장소', '2026-08-01 09:00:00+09', '2026-08-01 10:00:00+09',
  60, 'system'
);

insert into public.compute_runs (
  id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
  input_hash, contract_version, algorithm_version, facts_snapshot_at,
  source_data_version
) values (
  'ed000000-0000-0000-0000-000000000060',
  'ed000000-0000-0000-0000-000000000020',
  'ed000000-0000-0000-0000-000000000031',
  'ed000000-0000-0000-0000-000000000040',
  'feasibility', 'succeeded', 'legacy-recommendation-day-conflict',
  'v1', 'v1', now(), 'v1'
);

insert into public.recommendation_candidates (
  id, trip_plan_id, trip_day_id, schedule_version_id, compute_run_id,
  base_item_id, candidate_place_id, recommendation_type
) values (
  'ed000000-0000-0000-0000-000000000080',
  'ed000000-0000-0000-0000-000000000020',
  'ed000000-0000-0000-0000-000000000032',
  'ed000000-0000-0000-0000-000000000040',
  'ed000000-0000-0000-0000-000000000060',
  'ed000000-0000-0000-0000-000000000050',
  'ed000000-0000-0000-0000-000000000010',
  'nearby'
);
