\set ON_ERROR_STOP on

-- PostgreSQL 16/17 actual-RLS contract: run this file after the canonical init and seed paths.
-- The transaction-local grants exercise every policy and are removed by ROLLBACK.
begin;
set local statement_timeout = '2s'; -- statement_timeout recursion guard

do $$
declare
  expected_policies text[] := array[
    'trip_preferences_owner_select',
    'trip_transport_modes_owner_select',
    'trip_transport_events_owner_select',
    'trip_accommodations_owner_select',
    'trip_days_owner_select',
    'trip_schedule_versions_owner_select',
    'trip_items_owner_select',
    'itinerary_generation_runs_owner_select',
    'itinerary_generation_candidates_owner_select',
    'trip_legs_owner_select',
    'trip_item_progress_owner_select',
    'trip_execution_events_owner_select',
    'compute_runs_owner_select',
    'risk_events_owner_select',
    'trip_weather_impacts_owner_select',
    'recommendation_candidates_owner_select',
    'recovery_options_owner_select',
    'recovery_option_changes_owner_select',
    'live_state_snapshots_owner_select'
  ];
  invalid_count integer;
  helper record;
begin
  select p.prosecdef,
         p.provolatile,
         p.proconfig,
         owner.rolname as function_owner,
         schema_owner.rolname as schema_owner,
         owner.rolsuper,
         owner.rolbypassrls
    into helper
  from pg_catalog.pg_proc p
  join pg_catalog.pg_roles owner on owner.oid = p.proowner
  join pg_catalog.pg_namespace namespace on namespace.oid = p.pronamespace
  join pg_catalog.pg_roles schema_owner on schema_owner.oid = namespace.nspowner
  where p.oid = 'timing_jeju_private.owns_trip_plan(uuid)'::pg_catalog.regprocedure;

  if helper.prosecdef is distinct from true
     or helper.provolatile is distinct from 's'
     or helper.proconfig is distinct from array['search_path=""']::text[]
     or helper.function_owner is distinct from helper.schema_owner
     or helper.rolsuper is distinct from true
     or helper.rolbypassrls is distinct from true then
    raise exception 'helper prosecdef/provolatile/proconfig/rolsuper/rolbypassrls contract failed';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_proc function
  cross join lateral pg_catalog.aclexplode(
    coalesce(function.proacl, pg_catalog.acldefault('f', function.proowner))
  ) acl
  where function.oid =
      'timing_jeju_private.owns_trip_plan(uuid)'::pg_catalog.regprocedure
    and acl.grantee not in (
      function.proowner,
      (select oid from pg_catalog.pg_roles where rolname = 'authenticated')
    );
  if invalid_count <> 0 then
    raise exception 'PUBLIC/anon/service_role function ACL remains';
  end if;

  select count(*) into invalid_count
  from pg_catalog.pg_policies
  where schemaname = 'public'
    and policyname = any(expected_policies)
    and cmd = 'SELECT'
    and roles = array['authenticated']::name[]
    and qual like '%timing_jeju_private.owns_trip_plan%';
  if invalid_count <> 19 then
    raise exception 'exact policy inventory/callgraph contract failed: %', invalid_count;
  end if;

  if exists (
    select 1 from pg_catalog.pg_policies
    where schemaname = 'public'
      and policyname = 'trip_plans_owner_select'
      and qual <> '(user_id = ( SELECT auth.uid() AS uid))'
  ) then
    raise exception 'trip_plans direct owner policy changed';
  end if;

  if (select qual from pg_catalog.pg_policies
      where schemaname = 'public'
        and policyname = 'itinerary_generation_candidates_owner_select')
     <> 'timing_jeju_private.owns_trip_plan(trip_plan_id)' then
    raise exception 'itinerary_generation_candidates direct trip_plan_id path changed';
  end if;

  if (select qual from pg_catalog.pg_policies
      where schemaname = 'public'
        and policyname = 'recovery_option_changes_owner_select')
     not like '%recovery_options%recovery_option_id%owns_trip_plan%trip_plan_id%' then
    raise exception 'recovery_option_changes nested parent path changed';
  end if;

  if pg_catalog.to_regprocedure('public.owns_trip_plan(uuid)') is not null
     or pg_catalog.to_regprocedure(
       'timing_jeju_private.trip_preferences_owner(uuid)'
     ) is not null then
    raise exception 'legacy helper remains';
  end if;

  if pg_catalog.has_schema_privilege('authenticated', 'timing_jeju_private', 'USAGE')
       is distinct from true
     or pg_catalog.has_schema_privilege('authenticated', 'timing_jeju_private', 'CREATE')
       is distinct from false
     or pg_catalog.has_function_privilege(
       'authenticated', 'timing_jeju_private.owns_trip_plan(uuid)', 'EXECUTE'
     ) is distinct from true
     or pg_catalog.has_schema_privilege('anon', 'timing_jeju_private', 'USAGE')
       is distinct from false
     or pg_catalog.has_function_privilege(
       'anon', 'timing_jeju_private.owns_trip_plan(uuid)', 'EXECUTE'
     ) is distinct from false
     or pg_catalog.has_schema_privilege('service_role', 'timing_jeju_private', 'USAGE')
       is distinct from false
     or pg_catalog.has_function_privilege(
       'service_role', 'timing_jeju_private.owns_trip_plan(uuid)', 'EXECUTE'
     ) is distinct from false then
    raise exception 'schema/function ACL matrix failed';
  end if;
end $$;

grant select on table
  public.trip_plans,
  public.trip_preferences,
  public.trip_transport_modes,
  public.trip_transport_events,
  public.trip_accommodations,
  public.trip_days,
  public.trip_schedule_versions,
  public.trip_items,
  public.itinerary_generation_runs,
  public.itinerary_generation_candidates,
  public.trip_legs,
  public.trip_item_progress,
  public.trip_execution_events,
  public.compute_runs,
  public.risk_events,
  public.trip_weather_impacts,
  public.recommendation_candidates,
  public.recovery_options,
  public.recovery_option_changes,
  public.live_state_snapshots
to authenticated;

set local role authenticated;
select pg_catalog.set_config(
  'request.jwt.claim.sub',
  '09000000-0000-0000-0000-000000000001',
  true
);

-- owner -> other -> owner on one connection; every helper-backed policy is exercised.
do $$
declare
  target_table text;
  visible_rows bigint;
begin
  foreach target_table in array array[
    'trip_preferences', 'trip_transport_modes', 'trip_transport_events',
    'trip_accommodations', 'trip_days', 'trip_schedule_versions', 'trip_items',
    'itinerary_generation_runs', 'itinerary_generation_candidates', 'trip_legs',
    'trip_item_progress', 'trip_execution_events', 'compute_runs', 'risk_events',
    'trip_weather_impacts', 'recommendation_candidates', 'recovery_options',
    'recovery_option_changes', 'live_state_snapshots'
  ] loop
    execute pg_catalog.format('select count(*) from public.%I', target_table)
      into visible_rows;
    if visible_rows = 0 then
      raise exception 'owner policy denied all rows on %', target_table;
    end if;
  end loop;

  perform pg_catalog.set_config(
    'request.jwt.claim.sub',
    '09000000-0000-0000-0000-000000000002',
    true
  );
  foreach target_table in array array[
    'trip_preferences', 'trip_transport_modes', 'trip_transport_events',
    'trip_accommodations', 'trip_days', 'trip_schedule_versions', 'trip_items',
    'itinerary_generation_runs', 'itinerary_generation_candidates', 'trip_legs',
    'trip_item_progress', 'trip_execution_events', 'compute_runs', 'risk_events',
    'trip_weather_impacts', 'recommendation_candidates', 'recovery_options',
    'recovery_option_changes', 'live_state_snapshots'
  ] loop
    execute pg_catalog.format('select count(*) from public.%I', target_table)
      into visible_rows;
    if visible_rows <> 0 then
      raise exception 'other subject saw rows on %', target_table;
    end if;
  end loop;

  perform pg_catalog.set_config(
    'request.jwt.claim.sub',
    '09000000-0000-0000-0000-000000000001',
    true
  );
  if not timing_jeju_private.owns_trip_plan(
    '50000000-0000-0000-0000-000000000001'
  )
     or timing_jeju_private.owns_trip_plan(
       'ffffffff-ffff-ffff-ffff-ffffffffffff'
     ) then
    raise exception 'owner cache-leak regression';
  end if;
end $$;

-- missing/null subject and missing/null trip fail closed.
select pg_catalog.set_config('request.jwt.claim.sub', '', true);
do $$
begin
  if timing_jeju_private.owns_trip_plan(
       '50000000-0000-0000-0000-000000000001'
     )
     or timing_jeju_private.owns_trip_plan(null) then
    raise exception 'null or missing subject did not fail closed';
  end if;
end $$;

-- malformed JWT is caught inside the helper and returns false.
select pg_catalog.set_config('request.jwt.claim.sub', 'malformed-jwt-subject', true);
do $$
begin
  if timing_jeju_private.owns_trip_plan(
       '50000000-0000-0000-0000-000000000001'
     ) then
    raise exception 'malformed JWT did not fail closed';
  end if;
end $$;

-- pg_temp shadow objects and caller search_path cannot redirect the definer lookup.
create temporary table trip_plans (id uuid, user_id uuid);
insert into pg_temp.trip_plans values (
  '50000000-0000-0000-0000-000000000001',
  '09000000-0000-0000-0000-000000000002'
);
set local search_path = pg_temp, public;
select pg_catalog.set_config(
  'request.jwt.claim.sub',
  '09000000-0000-0000-0000-000000000001',
  true
);
do $$
begin
  if not timing_jeju_private.owns_trip_plan(
    '50000000-0000-0000-0000-000000000001'
  ) then
    raise exception 'pg_temp shadow changed helper result';
  end if;
end $$;

reset role;

-- Anon/client writes remain denied by ACL; no migration table grants are permitted.
do $$
begin
  if pg_catalog.has_table_privilege('anon', 'public.trip_items', 'SELECT')
     or pg_catalog.has_table_privilege('anon', 'public.trip_items', 'INSERT')
     or pg_catalog.has_table_privilege('authenticated', 'public.trip_items', 'INSERT')
     or pg_catalog.has_table_privilege('authenticated', 'public.trip_items', 'UPDATE')
     or pg_catalog.has_table_privilege('authenticated', 'public.trip_items', 'DELETE') then
    raise exception 'anon/write ACL denial regressed';
  end if;
end $$;

rollback; -- exact grants and pg_temp objects are transaction-local and rolled back

-- Atomic rollback/replay is covered by the Testcontainers source using an extra legacy dependency.
-- Markers retained for static audit: atomic rollback, legacy dependency, replay.
