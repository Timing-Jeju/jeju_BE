-- Issue #223 post-merge remediation: close opaque hash and generic JSON provenance gaps.
begin;
lock table public.schedule_revision_runs, public.mcp_compute_call_logs,
  public.trip_preferences, public.trip_items, public.trip_legs,
  public.itinerary_generation_runs, public.ai_messages, public.compute_runs,
  public.risk_events, public.trip_weather_impacts, public.recommendation_candidates,
  public.recovery_options, public.recovery_option_changes
  in access exclusive mode;

do $$
begin
  if timing_jeju_planner_private.user_location_guard_purge_revision() <> '20260918000018' then
    raise exception using errcode = '23514', message = 'location cutover predecessor mismatch';
  end if;
end;
$$;

-- Alias recognition remains available for the already completed 017 event cleanup.
-- The 020 legacy audit below does not infer provenance from coordinate-like values.
create or replace function timing_jeju_private.is_user_location_key(input_key text)
returns boolean language sql immutable strict security invoker set search_path = ''
as $$
  select lower(regexp_replace(input_key, '[^a-zA-Z0-9]', '', 'g')) = any(array[
    'location', 'position', 'point', 'lat', 'lng', 'lon', 'latitude', 'longitude',
    'coordinate', 'coordinates', 'currentlocation', 'currentposition',
    'currentcoordinate', 'currentcoordinates', 'accuracymeters', 'currentplaceid',
    'currentstopid', 'currentregion', 'currentregioncode', 'nearestplaceid',
    'neareststopid', 'nearestregion', 'nearestregioncode', 'gridx', 'gridy',
    'grid100m', 'geohash', 'coarselocation', 'locationdigest', 'locationhash',
    'locationprecisionmeters', 'locationobservedat', 'locationexpiresat',
    'locationredactedat', 'locationsupplied'
  ]);
$$;
revoke all on function timing_jeju_private.is_user_location_key(text)
  from public, anon, authenticated, service_role;

create or replace function timing_jeju_private.user_json_contains_location(input_value jsonb)
returns boolean language plpgsql immutable strict security invoker set search_path = ''
as $$
declare entry record;
begin
  if jsonb_typeof(input_value) = 'object' then
    for entry in select key, value from jsonb_each(input_value) loop
      if timing_jeju_private.is_user_location_key(entry.key)
         or (entry.key = 'type' and entry.value = '"GRID_100M"'::jsonb)
         or timing_jeju_private.user_json_contains_location(entry.value) then
        return true;
      end if;
    end loop;
  elsif jsonb_typeof(input_value) = 'array' then
    for entry in select value from jsonb_array_elements(input_value) loop
      if timing_jeju_private.user_json_contains_location(entry.value) then return true; end if;
    end loop;
  end if;
  return false;
end;
$$;
revoke all on function timing_jeju_private.user_json_contains_location(jsonb)
  from public, anon, authenticated, service_role;

-- Each user-owned JSON surface has a closed field/type contract. Empty objects
-- remain valid where the application has not defined a typed payload yet.
create function timing_jeju_private.user_json_matches_write_contract(
  input_surface text, input_value jsonb
) returns boolean language sql stable strict security invoker set search_path = ''
as $$
  select case input_surface
    when 'trip_preferences.raw_answers' then
      jsonb_typeof(input_value) = 'object'
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('pace', 'partySize', 'childAges')
      )
      and (not input_value ? 'pace' or input_value ->> 'pace' in ('slow', 'normal', 'fast'))
      and (not input_value ? 'partySize' or (
        jsonb_typeof(input_value -> 'partySize') = 'number'
        and input_value ->> 'partySize' ~ '^([1-9]|1[0-9]|20)$'
      ))
      and (not input_value ? 'childAges' or (
        jsonb_typeof(input_value -> 'childAges') = 'array'
        and jsonb_array_length(input_value -> 'childAges') <= 20
        and not exists (
          select 1 from jsonb_array_elements(input_value -> 'childAges') element
          where jsonb_typeof(element) <> 'number'
             or element #>> '{}' !~ '^(0|[1-9]|1[0-7])$'
        )
      ))
    when 'trip_legs.facts' then
      input_value = '{}'::jsonb or (
        jsonb_typeof(input_value) = 'object'
        and not exists (
          select 1 from jsonb_object_keys(input_value) key where key <> 'derivation'
        )
        and input_value ->> 'derivation' = 'conservative_walk_v1'
      )
    when 'itinerary_generation_runs.structured_input' then
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['targetDayId', 'candidateCount', 'refreshExternalFacts']
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('targetDayId', 'candidateCount', 'refreshExternalFacts')
      )
      and jsonb_typeof(input_value -> 'targetDayId') = 'string'
      and input_value ->> 'targetDayId'
        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and jsonb_typeof(input_value -> 'candidateCount') = 'number'
      and input_value ->> 'candidateCount' ~ '^([1-9]|10)$'
      and jsonb_typeof(input_value -> 'refreshExternalFacts') = 'boolean'
    when 'compute_runs.result_summary' then
      jsonb_typeof(input_value) = 'object'
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('score', 'observedAt', 'expiresAt')
      )
      and (not input_value ? 'score' or (
        jsonb_typeof(input_value -> 'score') = 'number'
        and input_value ->> 'score' ~ '^([0-9]|[1-9][0-9]|100)$'
      ))
      and (not input_value ? 'observedAt' or (
        jsonb_typeof(input_value -> 'observedAt') = 'string'
        and input_value ->> 'observedAt'
          ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$'
        and pg_catalog.pg_input_is_valid(input_value ->> 'observedAt', 'pg_catalog.timestamptz')
      ))
      and (not input_value ? 'expiresAt' or (
        jsonb_typeof(input_value -> 'expiresAt') = 'string'
        and input_value ->> 'expiresAt'
          ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$'
        and pg_catalog.pg_input_is_valid(input_value ->> 'expiresAt', 'pg_catalog.timestamptz')
      ))
    when 'ai_messages.structured_payload' then input_value = '{}'::jsonb
    when 'risk_events.computed_facts' then input_value = '{}'::jsonb
    when 'trip_weather_impacts.computed_facts' then input_value = '{}'::jsonb
    when 'recommendation_candidates.facts' then input_value = '{}'::jsonb
    when 'recovery_options.change_summary' then input_value = '{}'::jsonb
    when 'recovery_option_changes.before_value' then input_value = '{}'::jsonb
    when 'recovery_option_changes.after_value' then input_value = '{}'::jsonb
    else false
  end;
$$;
revoke all on function timing_jeju_private.user_json_matches_write_contract(text, jsonb)
  from public, anon, authenticated, service_role;

-- Replace the 017 legacy verifier used by the 018 wrapper. Every generic JSON
-- surface is accepted only when its exact non-location shape and types are known.
-- Unknown keys, aliases and nested containers remain in place for manual audit.
create or replace function timing_jeju_planner_private.user_location_residue_counts_v17()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select 'trip_execution_events'::text, count(*) from public.trip_execution_events
  where location is not null
    or not coalesce(metadata = '{}'::jsonb or (
      jsonb_typeof(metadata) = 'object' and metadata - 'source' = '{}'::jsonb
      and metadata ->> 'source' in ('manual', 'time', 'system', 'mobile')
    ), false)
  union all
  select 'live_state_snapshots', count(*) from public.live_state_snapshots
  where current_location is not null or current_place_id is not null
    or facts <> '{}'::jsonb or next_action is not null
  union all
  select 'compute_run_inputs', count(*) from public.compute_run_inputs
  where location_supplied or coarse_location is not null or location_precision_meters is not null
    or location_policy_version is not null or location_observed_at is not null
    or location_expires_at is not null or location_redacted_at is not null
  union all
  select 'trip_preferences.raw_answers', count(*) from public.trip_preferences
  where timing_jeju_private.user_json_matches_write_contract(
    'trip_preferences.raw_answers', raw_answers) is not true
  union all
  select 'trip_items.facts', count(*) from public.trip_items
  where facts is distinct from '{}'::jsonb
  union all
  select 'trip_legs.facts', count(*) from public.trip_legs
  where timing_jeju_private.user_json_matches_write_contract('trip_legs.facts', facts) is not true
  union all
  select 'itinerary_generation_runs.structured_input', count(*)
  from public.itinerary_generation_runs
  where timing_jeju_private.user_json_matches_write_contract(
    'itinerary_generation_runs.structured_input', structured_input) is not true
  union all
  select 'ai_messages.structured_payload', count(*) from public.ai_messages
  where timing_jeju_private.user_json_matches_write_contract(
    'ai_messages.structured_payload', structured_payload) is not true
  union all
  select 'compute_runs.result_summary', count(*) from public.compute_runs
  where timing_jeju_private.user_json_matches_write_contract(
    'compute_runs.result_summary', result_summary) is not true
  union all
  select 'risk_events.computed_facts', count(*) from public.risk_events
  where timing_jeju_private.user_json_matches_write_contract(
    'risk_events.computed_facts', computed_facts) is not true
  union all
  select 'trip_weather_impacts.computed_facts', count(*) from public.trip_weather_impacts
  where timing_jeju_private.user_json_matches_write_contract(
    'trip_weather_impacts.computed_facts', computed_facts) is not true
  union all
  select 'recommendation_candidates.facts', count(*) from public.recommendation_candidates
  where timing_jeju_private.user_json_matches_write_contract(
    'recommendation_candidates.facts', facts) is not true
  union all
  select 'recovery_options.change_summary', count(*) from public.recovery_options
  where timing_jeju_private.user_json_matches_write_contract(
    'recovery_options.change_summary', change_summary) is not true
  union all
  select 'recovery_option_changes', count(*) from public.recovery_option_changes
  where timing_jeju_private.user_json_matches_write_contract(
    'recovery_option_changes.before_value', before_value) is not true
    or timing_jeju_private.user_json_matches_write_contract(
      'recovery_option_changes.after_value', after_value) is not true
  union all
  select 'compute_runs.missing_input', count(*) from public.compute_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.compute_run_id = run.id)
  union all
  select 'itinerary_generation_runs.missing_input', count(*)
  from public.itinerary_generation_runs run
  where not exists (
    select 1 from public.compute_run_inputs input where input.generation_run_id = run.id)
  union all
  select 'schedule_revision_runs.missing_input', count(*) from public.schedule_revision_runs run
  where not exists (
    select 1 from public.compute_run_inputs input where input.schedule_revision_run_id = run.id)
  union all
  select 'unclassified_api_idempotency_records', count(*) from public.api_idempotency_records
  union all
  select 'unclassified_mcp_compute_call_logs', count(*) from public.mcp_compute_call_logs;
$$;
revoke all on function timing_jeju_planner_private.user_location_residue_counts_v17()
  from public, anon, authenticated, service_role;

create or replace function timing_jeju_private.reject_user_json_location()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare field_name text; row_value jsonb := to_jsonb(new); surface text;
begin
  foreach field_name in array tg_argv loop
    surface := tg_table_name || '.' || field_name;
    if timing_jeju_private.user_json_matches_write_contract(surface, row_value -> field_name)
         is not true then
      raise exception using errcode = '23514', message = 'user location storage is disabled';
    end if;
  end loop;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_user_json_location()
  from public, anon, authenticated, service_role;

-- Audit after installing the closed contracts so unproven legacy payloads roll
-- back every 017-020 schema, data and ledger change without deleting their rows.
do $$
begin
  if exists (
    select 1 from timing_jeju_planner_private.user_location_residue_counts()
    where residue_count <> 0
  ) then
    raise exception using errcode = '23514', message = 'user location residue requires audit';
  end if;
end;
$$;

-- No typed DB provenance for either independent hash exists yet. service_role
-- cannot assert that an opaque digest is location-free merely by linking a clean command.
create function timing_jeju_private.reject_unproven_revision_hash()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    raise exception using errcode = '23514', message = 'independent hash provenance required';
  end if;
  if new.request_hash is distinct from old.request_hash then
    raise exception using errcode = '23514', message = 'independent hash provenance required';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_unproven_revision_hash()
  from public, anon, authenticated, service_role;

create function timing_jeju_private.reject_unproven_mcp_hash()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    raise exception using errcode = '23514', message = 'independent hash provenance required';
  end if;
  if new.mcp_input_hash is distinct from old.mcp_input_hash then
    raise exception using errcode = '23514', message = 'independent hash provenance required';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_unproven_mcp_hash()
  from public, anon, authenticated, service_role;

create trigger aaa_independent_hash_provenance
before insert or update on public.schedule_revision_runs
for each row execute function timing_jeju_private.reject_unproven_revision_hash();
create trigger aaa_independent_hash_provenance
before insert or update on public.mcp_compute_call_logs
for each row execute function timing_jeju_private.reject_unproven_mcp_hash();

create or replace function timing_jeju_planner_private.user_location_guard_purge_revision()
returns text language sql immutable security invoker set search_path = ''
as $$ select '20260918000020'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_guard_purge_revision()
  from public, anon, authenticated, service_role;

commit;
