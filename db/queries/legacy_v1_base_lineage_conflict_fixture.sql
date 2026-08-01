\set ON_ERROR_STOP on

insert into public.app_sessions (id, public_token, display_name)
values (
  'ee000000-0000-0000-0000-000000000001',
  'legacy-base-lineage-conflict',
  'v1 일정 base 계보 충돌 계약'
);

insert into public.trip_plans (
  id, session_id, public_token, title, start_date, end_date,
  source_mode, data_version
) values (
  'ee000000-0000-0000-0000-000000000010',
  'ee000000-0000-0000-0000-000000000001',
  'legacy-base-lineage-conflict-trip', 'v1 일정 base 계보 충돌',
  '2026-08-01', '2026-08-01', 'fixture', 'v1'
);

insert into public.trip_schedule_versions (
  id, trip_plan_id, version_no, status, source_type
) values (
  'ee000000-0000-0000-0000-000000000020',
  'ee000000-0000-0000-0000-000000000010',
  2, 'draft', 'user_edit'
);

insert into public.trip_schedule_versions (
  id, trip_plan_id, version_no, base_schedule_version_id, status, source_type
) values (
  'ee000000-0000-0000-0000-000000000021',
  'ee000000-0000-0000-0000-000000000010',
  1, 'ee000000-0000-0000-0000-000000000020', 'draft', 'user_edit'
);
