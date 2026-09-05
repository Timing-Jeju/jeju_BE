\set ON_ERROR_STOP on

set time zone 'Asia/Seoul';

do $$
declare
  required_tables text[] := array[
    'data_import_runs', 'app_sessions', 'user_profiles', 'social_accounts',
    'legal_documents', 'user_consents', 'tour_places', 'place_details',
    'place_operating_hours', 'place_aliases', 'place_images', 'saved_places',
    'bus_stops', 'place_stop_links', 'weather_grid_points', 'weather_observations',
    'weather_forecasts', 'bus_routes', 'route_stops', 'timetable_entries',
    'bus_arrival_snapshots', 'mobility_route_snapshots', 'trip_plans',
    'trip_preferences', 'trip_transport_modes', 'trip_place_preferences',
    'trip_transport_events', 'trip_accommodations', 'trip_days',
    'trip_schedule_versions', 'trip_items', 'itinerary_generation_runs',
    'itinerary_generation_candidates', 'ai_conversations', 'ai_messages',
    'trip_legs', 'trip_item_progress', 'trip_execution_events',
    'compute_runs', 'risk_events', 'trip_weather_impacts',
    'recommendation_candidates', 'recovery_options', 'recovery_option_changes',
    'live_state_snapshots', 'mcp_compute_call_logs', 'api_idempotency_records'
  ];
  missing_tables text;
  rls_disabled_tables text;
  missing_fk_indexes text;
  invalid_count integer;
  sealed_version record;
begin
  if not exists (select 1 from pg_extension where extname = 'postgis')
     or not exists (select 1 from pg_extension where extname = 'pgcrypto')
     or not exists (select 1 from pg_extension where extname = 'btree_gist') then
    raise exception 'required extensions postgis/pgcrypto/btree_gist are missing';
  end if;

  select string_agg(table_name, ', ' order by table_name)
    into missing_tables
  from unnest(required_tables) as table_name
  where to_regclass('public.' || table_name) is null;

  if missing_tables is not null then
    raise exception 'required tables are missing: %', missing_tables;
  end if;

  select string_agg(c.relname, ', ' order by c.relname)
    into rls_disabled_tables
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public'
    and c.relname = any(required_tables)
    and not c.relrowsecurity;

  if rls_disabled_tables is not null then
    raise exception 'RLS is disabled: %', rls_disabled_tables;
  end if;

  select string_agg(format('%s.%s', c.conrelid::regclass, c.conname), ', ' order by c.conrelid::regclass::text, c.conname)
    into missing_fk_indexes
  from pg_constraint c
  where c.contype = 'f'
    and c.connamespace = 'public'::regnamespace
    and not exists (
      select 1
      from pg_index i
      where i.indrelid = c.conrelid
        and (i.indkey::smallint[])[0:cardinality(c.conkey)-1] = c.conkey
    );

  if missing_fk_indexes is not null then
    raise exception 'foreign keys without a leading index: %', missing_fk_indexes;
  end if;

  select count(*) into invalid_count
  from pg_policies
  where schemaname = 'public'
    and cmd <> 'SELECT';

  if invalid_count <> 0 then
    raise exception 'client-write RLS policies must not exist; found %', invalid_count;
  end if;

  select count(*) into invalid_count
  from information_schema.role_table_grants
  where table_schema = 'public'
    and grantee in ('anon', 'authenticated')
    and not (
      (
        grantee = 'authenticated'
        and table_name = 'notification_preferences'
        and privilege_type = 'SELECT'
      )
      or (
        grantee = 'authenticated'
        and table_name in ('trip_preferences', 'trip_transport_modes')
        and privilege_type = 'SELECT'
      )
    );

  if invalid_count <> 0 then
    raise exception 'unexpected anon/authenticated table grants must not exist; found %',
      invalid_count;
  end if;

  select count(*) into invalid_count
  from auth.users u
  join auth.identities i on i.user_id = u.id
  join user_profiles p on p.id = u.id
  join social_accounts s
    on s.user_id = u.id
   and s.provider = i.provider
   and s.provider_user_id = i.provider_id
  where u.id = '09000000-0000-0000-0000-000000000001'
    and i.id = '09200000-0000-0000-0000-000000000001'
    and i.provider = 'kakao'
    and i.provider_id = 'demo-kakao-user-001'
    and i.identity_data ->> 'sub' = i.provider_id
    and i.identity_data ->> 'email' = u.email
    and (i.identity_data ->> 'email_verified')::boolean
    and i.identity_data ->> 'nickname' = p.nickname;

  if invalid_count <> 1 then
    raise exception 'demo auth identity fixture parity failed';
  end if;

  select count(*) into invalid_count
  from trip_plans p
  left join lateral (
    select count(*) as active_count,
           coalesce(bool_or(v.id = p.active_schedule_version_id), false) as pointer_is_active
    from trip_schedule_versions v
    where v.trip_plan_id = p.id
      and v.status = 'active'
  ) version_state on true
  where (p.active_schedule_version_id is null and version_state.active_count <> 0)
     or (p.active_schedule_version_id is not null
         and (version_state.active_count <> 1 or not version_state.pointer_is_active))
     or (p.status in ('planned', 'live', 'completed') and p.active_schedule_version_id is null);

  if invalid_count <> 0 then
    raise exception 'active schedule invariants failed for % trip(s)', invalid_count;
  end if;

  select count(*) into invalid_count
  from trip_days d
  join trip_plans p on p.id = d.trip_plan_id
  where d.trip_date <> p.start_date + (d.day_no - 1)
     or d.trip_date < p.start_date
     or d.trip_date > p.end_date;

  if invalid_count <> 0 then
    raise exception 'trip day/date invariants failed for % row(s)', invalid_count;
  end if;

  select count(*) into invalid_count
  from trip_items i
  join trip_days d on d.id = i.trip_day_id
  where (i.planned_start_at is not null and i.planned_start_at::date <> d.trip_date)
     or (i.planned_end_at is not null and i.planned_end_at::date <> d.trip_date);

  if invalid_count <> 0 then
    raise exception 'trip item timestamps cross their trip day for % row(s)', invalid_count;
  end if;

  select count(*) into invalid_count
  from trip_legs l
  join trip_items from_item on from_item.id = l.from_item_id
  join trip_items to_item on to_item.id = l.to_item_id
  where from_item.trip_day_id <> l.trip_day_id
     or to_item.trip_day_id <> l.trip_day_id
     or from_item.schedule_version_id <> l.schedule_version_id
     or to_item.schedule_version_id <> l.schedule_version_id;

  if invalid_count <> 0 then
    raise exception 'trip leg endpoint invariants failed for % row(s)', invalid_count;
  end if;

  select count(*) into invalid_count
  from trip_schedule_versions v
  left join trip_schedule_versions base on base.id = v.base_schedule_version_id
  where v.base_schedule_version_id is not null
    and base.trip_plan_id <> v.trip_plan_id;

  if invalid_count <> 0 then
    raise exception 'cross-trip schedule version lineage found for % row(s)', invalid_count;
  end if;

  if (select count(*) from trip_days where trip_plan_id = '50000000-0000-0000-0000-000000000001') <> 3 then
    raise exception 'fixture must contain three trip days';
  end if;

  if (select count(*) from trip_accommodations where trip_plan_id = '50000000-0000-0000-0000-000000000001') <> 2 then
    raise exception 'fixture must contain two accommodations';
  end if;

  if (select count(*) from trip_transport_modes where trip_plan_id = '50000000-0000-0000-0000-000000000001') <> 3 then
    raise exception 'fixture must contain all three primary transport choices';
  end if;

  if (select count(*) from trip_transport_events where trip_plan_id = '50000000-0000-0000-0000-000000000001') <> 2 then
    raise exception 'fixture must contain arrival and departure events';
  end if;

  if (
    select count(distinct trip_day_id)
    from trip_items
    where schedule_version_id = '60000000-0000-0000-0000-000000000001'
      and sequence_no = 1
  ) <> 3 then
    raise exception 'sequence numbers must restart for every trip day';
  end if;

  if (
    select count(*)
    from trip_items
    where schedule_version_id = '60000000-0000-0000-0000-000000000002'
  ) <> (
    select count(*)
    from trip_items
    where schedule_version_id = '60000000-0000-0000-0000-000000000001'
  ) then
    raise exception 'AI candidate must be a complete apply-ready schedule version';
  end if;

  if (
    select count(*)
    from trip_legs
    where trip_plan_id = '50000000-0000-0000-0000-000000000001'
  ) <> 18 then
    raise exception 'fixture must contain complete movement chains for all three schedule versions';
  end if;

  for sealed_version in
    select id, trip_plan_id
    from trip_schedule_versions
    where status in ('candidate', 'active')
  loop
    perform public.assert_schedule_version_sealable(
      sealed_version.id,
      sealed_version.trip_plan_id
    );
  end loop;

  if not exists (
    select 1
    from recovery_options ro
    join recovery_option_changes rc on rc.recovery_option_id = ro.id
    where ro.trip_plan_id = '50000000-0000-0000-0000-000000000001'
      and ro.base_schedule_version_id <> ro.proposed_schedule_version_id
      and rc.action = 'move_day'
  ) then
    raise exception 'fixture recovery option must include a normalized move_day diff';
  end if;

  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.recovery_options'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%option_type%'
      and pg_get_constraintdef(oid) not ilike '%reorder%'
  ) or not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.recovery_option_changes'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%action%'
      and pg_get_constraintdef(oid) not ilike '%reorder%'
  ) then
    raise exception 'automatic reorder must be excluded from recovery enums';
  end if;

  if (
    select count(*)
    from trip_item_progress progress
    join trip_plans trip on trip.id = progress.trip_plan_id
    where progress.schedule_version_id = trip.active_schedule_version_id
  ) <> (
    select count(*)
    from trip_items item
    join trip_plans trip on trip.id = item.trip_plan_id
    where item.schedule_version_id = trip.active_schedule_version_id
  ) then
    raise exception 'every active schedule item must have a progress row';
  end if;

  if not exists (
    select 1
    from trip_execution_events
    where trip_plan_id = '50000000-0000-0000-0000-000000000001'
      and event_type = 'arrived'
  ) then
    raise exception 'fixture must contain an append-only arrival event';
  end if;

  if (
    select count(*)
    from pg_trigger
    where not tgisinternal
      and tgname in (
        'trg_trip_schedule_versions_mutation',
        'trg_trip_schedule_versions_sealing',
        'trg_trip_items_require_draft_version',
        'trg_trip_legs_require_draft_version',
        'trg_trip_plans_active_schedule_consistency',
        'trg_trip_schedule_versions_active_consistency'
      )
  ) <> 6 then
    raise exception 'schedule sealing/immutability/active-version triggers are missing';
  end if;

  if to_regprocedure('public.assert_schedule_version_sealable(uuid,uuid)') is null then
    raise exception 'schedule sealability function is missing';
  end if;

  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.trip_accommodations'::regclass
      and conname = 'ex_trip_accommodations_no_date_overlap'
      and contype = 'x'
  ) then
    raise exception 'accommodation overlap exclusion constraint is missing';
  end if;
end $$;

do $$
declare
  blocked boolean;
begin
  insert into trip_schedule_versions (
    id, trip_plan_id, version_no, base_schedule_version_id, status,
    source_type, summary, created_by_user_id
  ) values (
    '69000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    99,
    '60000000-0000-0000-0000-000000000001',
    'draft',
    'user_edit',
    'missing-leg negative fixture',
    '09000000-0000-0000-0000-000000000001'
  );

  insert into trip_items (
    id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
    item_type, place_id, title, planned_start_at, planned_end_at,
    stay_minutes, required, source
  ) values
  (
    '69100000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '51000000-0000-0000-0000-000000000001',
    '69000000-0000-0000-0000-000000000001',
    1, 'place_visit', '20000000-0000-0000-0000-000000000002',
    'negative item 1', current_date + time '09:00', current_date + time '09:30',
    30, true, 'user_input'
  ),
  (
    '69100000-0000-0000-0000-000000000002',
    '50000000-0000-0000-0000-000000000001',
    '51000000-0000-0000-0000-000000000001',
    '69000000-0000-0000-0000-000000000001',
    2, 'place_visit', '20000000-0000-0000-0000-000000000003',
    'negative item 2', current_date + time '10:00', current_date + time '10:30',
    30, false, 'user_input'
  );

  blocked := false;
  begin
    update trip_schedule_versions
    set status = 'candidate'
    where id = '69000000-0000-0000-0000-000000000001';
  exception
    when raise_exception then
      blocked := true;
  end;
  if not blocked then
    raise exception 'schedule without a consecutive leg was sealed';
  end if;

  insert into trip_legs (
    id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
    from_item_id, to_item_id, transport_mode,
    planned_departure_at, planned_arrival_at,
    walk_minutes, wait_minutes, ride_minutes, transfer_minutes, duration_minutes
  ) values (
    '69200000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '51000000-0000-0000-0000-000000000001',
    '69000000-0000-0000-0000-000000000001',
    1,
    '69100000-0000-0000-0000-000000000001',
    '69100000-0000-0000-0000-000000000002',
    'walk',
    current_date + time '09:35',
    current_date + time '09:55',
    10, 0, 0, 0, 10
  );

  blocked := false;
  begin
    update trip_schedule_versions
    set status = 'candidate'
    where id = '69000000-0000-0000-0000-000000000001';
  exception
    when raise_exception then
      blocked := true;
  end;
  if not blocked then
    raise exception 'schedule with an inconsistent leg duration was sealed';
  end if;

  delete from trip_schedule_versions
  where id = '69000000-0000-0000-0000-000000000001';

  blocked := false;
  begin
    update trip_items
    set memo = 'must-not-change'
    where id = '61000000-0000-0000-0000-000000000002';
  exception
    when raise_exception then
      blocked := true;
  end;
  if not blocked then
    raise exception 'sealed schedule item mutation was not blocked';
  end if;

  blocked := false;
  begin
    insert into trip_accommodations (
      trip_plan_id, place_id, check_in_date, check_out_date,
      check_in_time, check_out_time, sequence_no
    ) values (
      '50000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000004',
      current_date,
      current_date + 1,
      '15:00',
      '11:00',
      99
    );
  exception
    when exclusion_violation then
      blocked := true;
  end;
  if not blocked then
    raise exception 'overlapping accommodation was not blocked';
  end if;

  blocked := false;
  begin
    update trip_item_progress
    set status = 'planned'
    where trip_item_id = '61000000-0000-0000-0000-000000000001';
  exception
    when raise_exception then
      blocked := true;
  end;
  if not blocked then
    raise exception 'terminal progress status regression was not blocked';
  end if;

  blocked := false;
  begin
    update trip_execution_events
    set metadata = '{"mutated":true}'::jsonb
    where id = '62500000-0000-0000-0000-000000000001';
  exception
    when raise_exception then
      blocked := true;
  end;
  if not blocked then
    raise exception 'append-only execution event mutation was not blocked';
  end if;

  blocked := false;
  begin
    insert into trip_days (
      trip_plan_id, day_no, trip_date, title
    ) values (
      '50000000-0000-0000-0000-000000000001',
      4,
      current_date + 4,
      'invalid day'
    );
  exception
    when raise_exception then
      blocked := true;
  end;
  if not blocked then
    raise exception 'out-of-range trip day was not blocked';
  end if;
end $$;

do $$
declare
  revision_run_id constant uuid := '51700000-0000-0000-0000-000000000001';
  immutable_blocked boolean := false;
  rollback_blocked boolean := false;
begin
  insert into public.schedule_revision_runs (
    id, owner_user_id, trip_plan_id, base_schedule_version_id,
    target_trip_day_id, status, contract_version, algorithm_version,
    idempotency_key, request_hash, next_attempt_at
  ) values (
    revision_run_id,
    '09000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000001',
    '51000000-0000-0000-0000-000000000001',
    'queued', 'revision-v1', 'fixture-algorithm-v1',
    '51710000-0000-0000-0000-000000000001', repeat('a', 64), now()
  );

  begin
    update public.schedule_revision_runs
    set request_hash = repeat('b', 64)
    where id = revision_run_id;
  exception
    when check_violation then
      immutable_blocked := true;
  end;
  if not immutable_blocked then
    raise exception 'schedule revision run immutable identity update was accepted';
  end if;

  update public.schedule_revision_runs
  set status = 'cancelled', next_attempt_at = null, completed_at = now(),
      failure_code = 'SMOKE_CANCELLED'
  where id = revision_run_id;

  begin
    update public.schedule_revision_runs
    set status = 'running', attempt_count = 1, fencing_token = 1,
        lease_owner = 'smoke-worker', heartbeat_at = now(),
        lease_expires_at = now() + interval '30 seconds', started_at = now()
    where id = revision_run_id;
  exception
    when check_violation then
      rollback_blocked := true;
  end;
  if not rollback_blocked then
    raise exception 'schedule revision run terminal rollback was accepted';
  end if;

  delete from public.schedule_revision_runs where id = revision_run_id;
end $$;

do $$
declare
  revision_default text;
  revision_nullable text;
begin
  select column_default, is_nullable
    into revision_default, revision_nullable
  from information_schema.columns
  where table_schema = 'public'
    and table_name = 'trip_plans'
    and column_name = 'revision';

  if revision_default is distinct from '1' or revision_nullable is distinct from 'NO' then
    raise exception 'trip_plans.revision contract is missing';
  end if;

  if exists (select 1 from public.trip_plans where revision <= 0) then
    raise exception 'trip_plans.revision must stay positive';
  end if;
end $$;

select 'schema_contract' as check_name, 'PASS' as result;
select 'negative_constraints' as check_name, 'PASS' as result;

select
  'fixture_counts' as check_name,
  jsonb_build_object(
    'trips', (select count(*) from trip_plans),
    'days', (select count(*) from trip_days),
    'scheduleVersions', (select count(*) from trip_schedule_versions),
    'items', (select count(*) from trip_items),
    'legs', (select count(*) from trip_legs),
    'progressRows', (select count(*) from trip_item_progress),
    'executionEvents', (select count(*) from trip_execution_events),
    'accommodations', (select count(*) from trip_accommodations),
    'computeRuns', (select count(*) from compute_runs),
    'recoveryOptions', (select count(*) from recovery_options)
  ) as result;

select
  'active_schedule' as check_name,
  jsonb_build_object(
    'tripId', p.id,
    'status', p.status,
    'activeScheduleVersionId', p.active_schedule_version_id,
    'activeVersionNo', v.version_no,
    'score', v.resulting_score
  ) as result
from trip_plans p
join trip_schedule_versions v on v.id = p.active_schedule_version_id
where p.id = '50000000-0000-0000-0000-000000000001';

select
  'postgis_nearest_stop' as check_name,
  jsonb_build_object(
    'place', p.name,
    'stop', s.node_name,
    'distanceMeters', round(st_distance(p.location, s.location))::integer
  ) as result
from tour_places p
join lateral (
  select stop.*
  from bus_stops stop
  order by stop.location <-> p.location
  limit 1
) s on true
where p.id = '20000000-0000-0000-0000-000000000002';

select
  'compute_run_inputs' as check_name,
  case when to_regclass('public.compute_run_inputs') is not null then 'PASS' else 'MISSING' end
  as result;
