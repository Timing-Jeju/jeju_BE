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

-- Legacy audit stays semantic: recognize normalized aliases without guessing that
-- every numeric array is a coordinate. New writes use a separate typed contract below.
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
) returns boolean language sql immutable strict security invoker set search_path = ''
as $$
  select case input_surface
    when 'trip_preferences.raw_answers' then
      jsonb_typeof(input_value) = 'object'
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('pace', 'partySize', 'childAges')
      )
      and (not input_value ? 'pace' or jsonb_typeof(input_value -> 'pace') = 'string')
      and (not input_value ? 'partySize' or jsonb_typeof(input_value -> 'partySize') = 'number')
      and (not input_value ? 'childAges' or (
        jsonb_typeof(input_value -> 'childAges') = 'array'
        and not exists (
          select 1 from jsonb_array_elements(input_value -> 'childAges') element
          where jsonb_typeof(element) <> 'number'
        )
      ))
    when 'trip_legs.facts' then
      input_value = '{}'::jsonb or (
        jsonb_typeof(input_value) = 'object'
        and not exists (
          select 1 from jsonb_object_keys(input_value) key where key <> 'derivation'
        )
        and jsonb_typeof(input_value -> 'derivation') = 'string'
      )
    when 'itinerary_generation_runs.structured_input' then
      jsonb_typeof(input_value) = 'object'
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('targetDayId', 'candidateCount', 'refreshExternalFacts')
      )
      and jsonb_typeof(input_value -> 'targetDayId') = 'string'
      and jsonb_typeof(input_value -> 'candidateCount') = 'number'
      and jsonb_typeof(input_value -> 'refreshExternalFacts') = 'boolean'
    when 'compute_runs.result_summary' then
      jsonb_typeof(input_value) = 'object'
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('score', 'observedAt', 'expiresAt')
      )
      and (not input_value ? 'score' or jsonb_typeof(input_value -> 'score') = 'number')
      and (not input_value ? 'observedAt' or jsonb_typeof(input_value -> 'observedAt') = 'string')
      and (not input_value ? 'expiresAt' or jsonb_typeof(input_value -> 'expiresAt') = 'string')
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

create or replace function timing_jeju_private.reject_user_json_location()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare field_name text; row_value jsonb := to_jsonb(new); surface text;
begin
  foreach field_name in array tg_argv loop
    surface := tg_table_name || '.' || field_name;
    if not timing_jeju_private.user_json_matches_write_contract(surface, row_value -> field_name) then
      raise exception using errcode = '23514', message = 'user location storage is disabled';
    end if;
  end loop;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_user_json_location()
  from public, anon, authenticated, service_role;

-- The legacy verifier uses the semantic alias helper. Audit before installing
-- the separate typed write contracts so legacy residue rolls back this migration.
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
