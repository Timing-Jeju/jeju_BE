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

-- Generic user JSON is not a provenance contract. Recognize normalized aliases
-- and fail closed for the common GeoJSON-style numeric coordinate tuple at any depth.
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
    if jsonb_array_length(input_value) = 2 and not exists (
      select 1 from jsonb_array_elements(input_value) element
      where jsonb_typeof(element) <> 'number'
    ) then
      return true;
    end if;
    for entry in select value from jsonb_array_elements(input_value) loop
      if timing_jeju_private.user_json_contains_location(entry.value) then return true; end if;
    end loop;
  end if;
  return false;
end;
$$;
revoke all on function timing_jeju_private.user_json_contains_location(jsonb)
  from public, anon, authenticated, service_role;

-- The verifier and every generic-JSON trigger call the same helper. Audit before
-- adding more guards so an unknown legacy tuple rolls back this whole migration.
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
  if current_user <> 'service_role' then return new; end if;
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
  if current_user <> 'service_role' then return new; end if;
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
