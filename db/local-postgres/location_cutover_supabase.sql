begin;
lock table supabase_migrations.schema_migrations in access exclusive mode;
do $history$
begin
  if (select array_agg(version::text order by version) from supabase_migrations.schema_migrations)
      is distinct from array['20260728000000','20260730000000','20260730010000','20260730020000','20260730030000','20260730040000','20260810000000','20260811000000','20260813000000','20260813010000','20260814000000','20260816000000','20260817000000','20260818000000','20260819000000','20260820000000','20260820000001','20260822000000','20260823000000','20260824000000','20260825000000','20260826000000','20260827000000','20260828000000','20260829000000','20260830000000','20260831000000','20260901000000','20260902000000','20260903000000','20260904000000','20260904000001','20260905000000','20260906000000','20260907000000','20260918000000','20260918000001','20260918000002','20260918000003','20260918000004','20260918000005','20260918000006','20260918000007','20260918000008','20260918000009','20260918000010','20260918000011','20260918000012','20260918000013','20260918000014','20260918000015','20260918000016']::text[]
     or to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is not null then
    raise exception using errcode = '23514', message = 'location cutover migration history mismatch';
  end if;
end;
$history$;
-- Issue #223: reject new location writes, audit legacy lineage, and require zero residue atomically.
lock table public.trip_plans, public.trip_schedule_versions,
  public.compute_runs, public.itinerary_generation_runs, public.schedule_revision_runs,
  public.compute_run_inputs, public.trip_execution_events, public.live_state_snapshots,
  public.mcp_compute_call_logs, public.itinerary_generation_candidates,
  public.recovery_options, public.recovery_option_changes, public.trip_items, public.trip_legs,
  public.mobility_route_snapshots, public.trip_preferences, public.ai_conversations, public.ai_messages,
  public.risk_events, public.trip_weather_impacts, public.recommendation_candidates,
  public.trip_item_progress, public.api_idempotency_records
  in access exclusive mode;

create function timing_jeju_private.reject_command_input_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.location_supplied is distinct from false
     or new.coarse_location is not null or new.location_precision_meters is not null
     or new.location_policy_version is not null or new.location_observed_at is not null
     or new.location_expires_at is not null or new.location_redacted_at is not null then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_command_input_location()
  from public, anon, authenticated, service_role;
create trigger aaa_command_input_no_location
before insert or update on public.compute_run_inputs
for each row execute function timing_jeju_private.reject_command_input_location();

create function timing_jeju_private.reject_execution_event_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.location is not null or not coalesce(
    new.metadata = '{}'::jsonb or (
      jsonb_typeof(new.metadata) = 'object'
      and new.metadata - 'source' = '{}'::jsonb
      and new.metadata ->> 'source' in ('manual', 'time', 'system', 'mobile')
    ), false
  ) then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_execution_event_location()
  from public, anon, authenticated, service_role;
create trigger aaa_execution_event_no_location
before insert or update on public.trip_execution_events
for each row execute function timing_jeju_private.reject_execution_event_location();

create function timing_jeju_private.reject_live_state_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.current_location is not null or new.current_place_id is not null
     or new.facts is distinct from '{}'::jsonb or new.next_action is not null then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_live_state_location()
  from public, anon, authenticated, service_role;
create trigger aaa_live_state_no_location
before insert or update on public.live_state_snapshots
for each row execute function timing_jeju_private.reject_live_state_location();

-- The audit helpers only inspect user-owned generic JSON, never public geodata.
create function timing_jeju_private.is_user_location_key(input_key text)
returns boolean language sql immutable strict security invoker set search_path = ''
as $$
  select lower(regexp_replace(input_key, '[^a-zA-Z0-9]', '', 'g')) = any(array[
    'location', 'lat', 'lng', 'lon', 'latitude', 'longitude', 'coordinates',
    'accuracymeters', 'currentlocation', 'currentplaceid', 'currentstopid',
    'currentregion', 'currentregioncode', 'nearestplaceid', 'neareststopid',
    'nearestregion', 'nearestregioncode', 'gridx', 'gridy', 'grid100m', 'geohash',
    'coarselocation', 'locationdigest', 'locationhash', 'locationprecisionmeters',
    'locationobservedat', 'locationexpiresat', 'locationredactedat', 'locationsupplied'
  ]);
$$;
revoke all on function timing_jeju_private.is_user_location_key(text)
  from public, anon, authenticated, service_role;

create function timing_jeju_private.user_json_contains_location(input_value jsonb)
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

-- Deny known current/derived location keys before PostgreSQL constraint DETAIL
-- could reflect the submitted JSON. The function only examines NEW; it performs
-- no reads/writes with its definer privileges and has a closed search_path.
create function timing_jeju_private.reject_user_json_location()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare field_name text; row_value jsonb := to_jsonb(new);
begin
  foreach field_name in array tg_argv loop
    if timing_jeju_private.user_json_contains_location(row_value -> field_name) then
      raise exception using errcode = '23514', message = 'user location storage is disabled';
    end if;
  end loop;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_user_json_location()
  from public, anon, authenticated, service_role;

create trigger aaa_user_json_no_location before insert or update on public.trip_preferences
  for each row execute function timing_jeju_private.reject_user_json_location('raw_answers');
create trigger aaa_user_json_no_location before insert or update on public.trip_legs
  for each row execute function timing_jeju_private.reject_user_json_location('facts');
create trigger aaa_user_json_no_location before insert or update on public.itinerary_generation_runs
  for each row execute function timing_jeju_private.reject_user_json_location('structured_input');
create trigger aaa_user_json_no_location before insert or update on public.ai_messages
  for each row execute function timing_jeju_private.reject_user_json_location('structured_payload');
create trigger aaa_user_json_no_location before insert or update on public.compute_runs
  for each row execute function timing_jeju_private.reject_user_json_location('result_summary');
create trigger aaa_user_json_no_location before insert or update on public.risk_events
  for each row execute function timing_jeju_private.reject_user_json_location('computed_facts');
create trigger aaa_user_json_no_location before insert or update on public.trip_weather_impacts
  for each row execute function timing_jeju_private.reject_user_json_location('computed_facts');
create trigger aaa_user_json_no_location before insert or update on public.recommendation_candidates
  for each row execute function timing_jeju_private.reject_user_json_location('facts');
create trigger aaa_user_json_no_location before insert or update on public.recovery_options
  for each row execute function timing_jeju_private.reject_user_json_location('change_summary');
create trigger aaa_user_json_no_location before insert or update on public.recovery_option_changes
  for each row execute function timing_jeju_private.reject_user_json_location('before_value', 'after_value');

-- Preserve unknown non-location leaves, including empty containers, for audit failure.
-- Only wrappers emptied by removing an identified location field may be discarded.
create function timing_jeju_private.remove_event_location_fields(input_value jsonb)
returns jsonb language plpgsql immutable strict security invoker set search_path = ''
as $$
declare entry record; cleaned jsonb; result jsonb;
begin
  if jsonb_typeof(input_value) = 'object' then
    result := '{}'::jsonb;
    for entry in select key, value from jsonb_each(input_value) loop
      if timing_jeju_private.is_user_location_key(entry.key)
         or (entry.key = 'type' and entry.value = '"GRID_100M"'::jsonb) then continue; end if;
      cleaned := timing_jeju_private.remove_event_location_fields(entry.value);
      if cleaned in ('{}'::jsonb, '[]'::jsonb)
         and timing_jeju_private.user_json_contains_location(entry.value) then continue; end if;
      result := result || jsonb_build_object(entry.key, cleaned);
    end loop;
    return result;
  elsif jsonb_typeof(input_value) = 'array' then
    result := '[]'::jsonb;
    for entry in select value from jsonb_array_elements(input_value) loop
      cleaned := timing_jeju_private.remove_event_location_fields(entry.value);
      if cleaned in ('{}'::jsonb, '[]'::jsonb)
         and timing_jeju_private.user_json_contains_location(entry.value) then continue; end if;
      result := result || jsonb_build_array(cleaned);
    end loop;
    return result;
  end if;
  return input_value;
end;
$$;
revoke all on function timing_jeju_private.remove_event_location_fields(jsonb)
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (
    select 1 from public.trip_execution_events event
    cross join lateral (
      select timing_jeju_private.remove_event_location_fields(event.metadata) as metadata
    ) cleaned
    where not coalesce(cleaned.metadata = '{}'::jsonb or (
      jsonb_typeof(cleaned.metadata) = 'object' and cleaned.metadata - 'source' = '{}'::jsonb
      and cleaned.metadata ->> 'source' in ('manual', 'time', 'system', 'mobile')
    ), false)
  ) then
    raise exception using errcode = '23514', message = 'legacy non-location metadata requires audit';
  end if;
end;
$$;

-- Audit every direction before deleting anything. Store identifiers only; raw
-- location and command hashes are compared in-place and never copied or printed.
create temporary table audited_command_input_scope on commit drop as
select input.id, input.compute_run_id, input.generation_run_id, input.schedule_revision_run_id
from public.compute_run_inputs input
join public.trip_plans trip on trip.id = input.trip_plan_id and trip.user_id = input.owner_user_id
left join public.compute_runs run on run.id = input.compute_run_id
left join public.itinerary_generation_runs generation on generation.id = input.generation_run_id
left join public.schedule_revision_runs revision on revision.id = input.schedule_revision_run_id
where num_nonnulls(input.compute_run_id, input.generation_run_id, input.schedule_revision_run_id) = 1
  and input.schema_version = 1
  and public.command_input_matches_schema(input.run_type, input.schema_version, input.structured_input)
  and (
    (input.location_redacted_at is not null and input.location_supplied
      and input.coarse_location is null and input.location_precision_meters is null
      and input.location_policy_version is null and input.location_observed_at is null
      and input.location_expires_at is null)
    or (input.location_redacted_at is null and input.command_input_hash =
      public.compute_command_input_hash(input.run_type::text,input.schema_version::smallint,
        input.contract_version::text,input.algorithm_version::text,input.base_schedule_version_id::uuid,
        input.structured_input::jsonb,input.location_supplied::boolean,input.coarse_location::jsonb))
  )
  and (
    (run.id is not null and run.trip_plan_id = input.trip_plan_id
      and run.schedule_version_id is not distinct from input.base_schedule_version_id
      and run.run_type = input.run_type and run.contract_version = input.contract_version
      and run.algorithm_version = input.algorithm_version and run.input_hash = input.command_input_hash)
    or (generation.id is not null and generation.trip_plan_id = input.trip_plan_id
      and generation.requested_by_user_id = input.owner_user_id
      and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
      and input.run_type = 'itinerary_generation' and generation.contract_version = input.contract_version
      and generation.algorithm_version = input.algorithm_version
      and generation.structured_input = input.structured_input)
    or (revision.id is not null and revision.trip_plan_id = input.trip_plan_id
      and revision.owner_user_id = input.owner_user_id
      and revision.base_schedule_version_id = input.base_schedule_version_id
      and input.run_type = 'schedule_revision' and revision.contract_version = input.contract_version
      and revision.algorithm_version = input.algorithm_version)
  );
alter table audited_command_input_scope add primary key (id);

do $$
begin
  if exists (
    select 1 from public.compute_run_inputs input
    where not exists (select 1 from pg_temp.audited_command_input_scope scope where scope.id = input.id)
    union all
    select 1 from public.compute_runs run
    where (select count(*) from pg_temp.audited_command_input_scope scope where scope.compute_run_id = run.id) <> 1
    union all
    select 1 from public.itinerary_generation_runs run
    where (select count(*) from pg_temp.audited_command_input_scope scope where scope.generation_run_id = run.id) <> 1
    union all
    select 1 from public.schedule_revision_runs run
    where (select count(*) from pg_temp.audited_command_input_scope scope where scope.schedule_revision_run_id = run.id) <> 1
    union all
    select 1 from public.mcp_compute_call_logs log
    where log.legacy_contract
      or num_nonnulls(log.compute_run_id, log.generation_run_id, log.schedule_revision_run_id) <> 1
      or not exists (
        select 1 from public.compute_run_inputs input
        join pg_temp.audited_command_input_scope scope on scope.id = input.id
        where input.command_input_hash = log.command_input_hash and (
          (log.compute_run_id is not null and scope.compute_run_id = log.compute_run_id)
          or (log.generation_run_id is not null and scope.generation_run_id = log.generation_run_id)
          or (log.schedule_revision_run_id is not null and scope.schedule_revision_run_id = log.schedule_revision_run_id)
        )
      )
  ) then
    raise exception using errcode = '23514', message = 'legacy compute hash lineage requires audit';
  end if;
end;
$$;

-- Freeze only identifiers; never copy a location or a derived hash into the audit scope.
create temporary table location_input_scope on commit drop as
select id, trip_plan_id, compute_run_id, generation_run_id, schedule_revision_run_id
from public.compute_run_inputs
where location_supplied or coarse_location is not null
   or location_precision_meters is not null or location_policy_version is not null
   or location_observed_at is not null or location_expires_at is not null
   or location_redacted_at is not null;
alter table location_input_scope add primary key (id);

do $$
begin
  if exists (
    select 1 from pg_temp.location_input_scope scope
    join public.compute_runs run on run.id = scope.compute_run_id
    where run.status in ('queued', 'running')
    union all
    select 1 from pg_temp.location_input_scope scope
    join public.itinerary_generation_runs run on run.id = scope.generation_run_id
    where run.status in ('queued', 'running')
    union all
    select 1 from pg_temp.location_input_scope scope
    join public.schedule_revision_runs run on run.id = scope.schedule_revision_run_id
    where run.status in ('queued', 'running')
  ) then
    raise exception using errcode = '23514', message = 'active location lineage requires audit';
  end if;
end;
$$;

-- Capture output identities before any parent CASCADE can erase their lineage.
create temporary table location_version_scope on commit drop as
with recursive versions(id, trip_plan_id) as (
  (
    select candidate.schedule_version_id, candidate.trip_plan_id
    from public.itinerary_generation_candidates candidate
    join pg_temp.location_input_scope input on input.generation_run_id = candidate.generation_run_id
    union
    select option.proposed_schedule_version_id, option.trip_plan_id
    from public.recovery_options option
    join pg_temp.location_input_scope input on input.compute_run_id = option.compute_run_id
  )
  union
  select child.id, child.trip_plan_id
  from public.trip_schedule_versions child
  join versions parent on child.base_schedule_version_id = parent.id and child.trip_plan_id = parent.trip_plan_id
)
select id, trip_plan_id from versions;
alter table location_version_scope add primary key (id);

create temporary table location_route_scope on commit drop as
select route.id from public.mobility_route_snapshots route
join pg_temp.location_version_scope version on version.id = route.schedule_version_id;
alter table location_route_scope add primary key (id);

do $$
begin
  if exists (
    select 1 from public.trip_schedule_versions version
    join pg_temp.location_version_scope scope on scope.id = version.id
    where version.status in ('active', 'superseded') or version.applied_at is not null
    union all
    select 1 from public.trip_plans trip
    join pg_temp.location_version_scope scope on scope.id = trip.active_schedule_version_id
    union all
    select 1 from public.itinerary_generation_candidates candidate
    join pg_temp.location_version_scope scope on scope.id = candidate.schedule_version_id
    where candidate.selected_at is not null
    union all
    select 1 from public.recovery_options option
    join pg_temp.location_input_scope input on input.compute_run_id = option.compute_run_id
    where option.status = 'applied' or option.applied_at is not null or option.selected_at is not null
  ) then
    raise exception using errcode = '23514', message = 'active location lineage requires audit';
  end if;

  -- An external command, a manual execution record, or a cross-run output needs
  -- a separate provenance decision. Never let a CASCADE silently decide for us.
  if exists (
    select 1 from public.compute_run_inputs input
    join pg_temp.location_version_scope scope on scope.id = input.base_schedule_version_id
    union all
    select 1 from public.compute_runs run
    join pg_temp.location_version_scope scope on scope.id = run.schedule_version_id
    union all
    select 1 from public.itinerary_generation_runs run
    join pg_temp.location_version_scope scope on scope.id = run.base_schedule_version_id
    union all
    select 1 from public.schedule_revision_runs run
    join pg_temp.location_version_scope scope on scope.id = run.base_schedule_version_id
    union all
    select 1 from public.itinerary_generation_candidates candidate
    join pg_temp.location_version_scope scope on scope.id = candidate.schedule_version_id
    where not exists (select 1 from pg_temp.location_input_scope input
      where input.generation_run_id = candidate.generation_run_id)
    union all
    select 1 from public.recovery_options option
    where (
      exists (select 1 from pg_temp.location_version_scope scope
        where scope.id in (option.base_schedule_version_id, option.proposed_schedule_version_id))
      or exists (select 1 from public.risk_events risk
        join pg_temp.location_input_scope input on input.compute_run_id = risk.compute_run_id
        where risk.id = option.trigger_risk_event_id)
    ) and not exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = option.compute_run_id)
    union all
    select 1 from public.trip_execution_events event
    join pg_temp.location_version_scope scope on scope.id = event.schedule_version_id
    union all
    select 1 from public.trip_item_progress progress
    join pg_temp.location_version_scope scope on scope.id = progress.schedule_version_id
    union all
    select 1 from public.ai_messages message
    join pg_temp.location_input_scope scope on scope.generation_run_id = message.generation_run_id
    join public.compute_run_inputs input on input.id = scope.id
    join public.ai_conversations conversation on conversation.id = message.conversation_id
    where message.role not in ('assistant', 'tool')
      or conversation.user_id is distinct from input.owner_user_id
      or conversation.trip_plan_id is distinct from input.trip_plan_id
    union all
    select 1 from public.trip_legs leg
    join pg_temp.location_route_scope route on route.id = leg.mobility_route_snapshot_id
    where not exists (select 1 from pg_temp.location_version_scope scope where scope.id = leg.schedule_version_id)
  ) then
    raise exception using errcode = '23514', message = 'external location lineage requires audit';
  end if;
end;
$$;

-- Only the audited location fields change. The append-only trigger is restored in
-- this same transaction; an exception rolls back both the data and trigger state.
alter table public.trip_execution_events disable trigger trg_trip_execution_events_append_only;
update public.trip_execution_events
set location = null,
    metadata = timing_jeju_private.remove_event_location_fields(metadata)
where location is not null or timing_jeju_private.user_json_contains_location(metadata);
alter table public.trip_execution_events enable trigger trg_trip_execution_events_append_only;

-- Discard the derived live projection, preserving its trip, schedule and manual events.
delete from public.live_state_snapshots
where current_location is not null or current_place_id is not null
   or facts is distinct from '{}'::jsonb or next_action is not null;

-- Non-location manual events remain append-only. Only computed live projections
-- and proven generated messages belonging to the audited parent are discarded.
delete from public.live_state_snapshots live
where exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = live.compute_run_id)
   or exists (select 1 from pg_temp.location_version_scope scope where scope.id = live.schedule_version_id);
delete from public.ai_messages message
where exists (select 1 from pg_temp.location_input_scope input where input.generation_run_id = message.generation_run_id);

-- Remove changes' item references before removing proposed versions. Parent
-- deletion then removes inputs, MCP metadata and result children in the same TX.
delete from public.recovery_options option
where exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = option.compute_run_id);
delete from public.compute_runs run
where exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = run.id);
delete from public.itinerary_generation_runs run
where exists (select 1 from pg_temp.location_input_scope input where input.generation_run_id = run.id);
delete from public.schedule_revision_runs run
where exists (select 1 from pg_temp.location_input_scope input where input.schedule_revision_run_id = run.id);

-- Break only the audited version/item/route reference cycle. Keep all FK objects
-- and row guards, validate outstanding references, then restore original timing.
create temporary table route_constraint_audit on commit drop as
select constraint_record.oid, conname, condeferrable, condeferred, convalidated,
  pg_get_constraintdef(constraint_record.oid) as definition
from pg_constraint constraint_record
where connamespace = 'public'::regnamespace
  and conname in ('fk_route_planned_version', 'fk_route_planned_origin_item', 'fk_route_planned_destination_item');
create temporary table route_trigger_audit on commit drop as
select trigger_record.oid, tgconstraint, tgname, tgenabled, tgdeferrable, tginitdeferred
from pg_trigger trigger_record
join pg_temp.route_constraint_audit constraint_record on constraint_record.oid = trigger_record.tgconstraint;

do $$
declare removed integer;
begin
  if (select count(*) from pg_temp.route_constraint_audit) <> 3 or exists (
    select 1 from pg_constraint constraint_record
    join pg_temp.route_constraint_audit expected on expected.oid = constraint_record.oid
    where constraint_record.conrelid <> 'public.mobility_route_snapshots'::regclass
      or constraint_record.contype <> 'f' or constraint_record.condeferrable
      or constraint_record.condeferred or not constraint_record.convalidated
  ) then
    raise exception using errcode = '23514', message = 'planned route constraint state requires audit';
  end if;

  alter table public.mobility_route_snapshots alter constraint fk_route_planned_version deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_origin_item deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_destination_item deferrable initially immediate;
  set constraints public.fk_route_planned_version, public.fk_route_planned_origin_item,
    public.fk_route_planned_destination_item deferred;

  update public.trip_schedule_versions version set status = 'rejected'
  where version.status = 'candidate' and exists (
    select 1 from pg_temp.location_version_scope scope where scope.id = version.id);
  loop
    delete from public.trip_schedule_versions version
    where exists (select 1 from pg_temp.location_version_scope scope where scope.id = version.id)
      and not exists (select 1 from public.trip_schedule_versions child where child.base_schedule_version_id = version.id);
    get diagnostics removed = row_count;
    exit when removed = 0;
  end loop;
  if exists (select 1 from public.trip_schedule_versions version
    join pg_temp.location_version_scope scope on scope.id = version.id) then
    raise exception using errcode = '23514', message = 'cyclic location lineage requires audit';
  end if;
  delete from public.mobility_route_snapshots route
  where exists (select 1 from pg_temp.location_route_scope scope where scope.id = route.id);

  set constraints public.fk_route_planned_version, public.fk_route_planned_origin_item,
    public.fk_route_planned_destination_item immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_version not deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_origin_item not deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_destination_item not deferrable initially immediate;

  if exists (
    select 1 from pg_temp.route_constraint_audit expected
    left join pg_constraint actual on actual.oid = expected.oid
    where actual.oid is null or actual.conname is distinct from expected.conname
      or actual.condeferrable is distinct from expected.condeferrable
      or actual.condeferred is distinct from expected.condeferred
      or actual.convalidated is distinct from expected.convalidated
      or pg_get_constraintdef(actual.oid) is distinct from expected.definition
    union all
    select 1 from pg_temp.route_trigger_audit expected
    left join pg_trigger actual on actual.oid = expected.oid
    where actual.oid is null or actual.tgconstraint is distinct from expected.tgconstraint
      or actual.tgname is distinct from expected.tgname or actual.tgenabled is distinct from expected.tgenabled
      or actual.tgdeferrable is distinct from expected.tgdeferrable
      or actual.tginitdeferred is distinct from expected.tginitdeferred
  ) then
    raise exception using errcode = '23514', message = 'planned route constraint restoration failed';
  end if;
exception
  when integrity_constraint_violation then
    raise exception using errcode = '23514', message = 'location purge integrity requires audit';
end;
$$;

-- Parent and input must be created in one transaction. Check the final state,
-- including input deletion, rather than requiring an input before parent INSERT.
create function timing_jeju_private.require_compute_input_lineage()
returns trigger language plpgsql security invoker set search_path = ''
as $$
declare parent_id uuid;
begin
  if tg_table_name = 'compute_runs' then
    parent_id := new.id;
  elsif tg_op = 'DELETE' then
    parent_id := old.compute_run_id;
  else
    parent_id := new.compute_run_id;
  end if;
  if parent_id is null then return null; end if;
  perform 1 from public.compute_runs where id = parent_id for update;
  -- Cascading parent deletion leaves no hash to validate.
  if not found then return null; end if;
  if not exists (
    select 1 from public.compute_runs run
    join public.trip_plans trip on trip.id = run.trip_plan_id
    join public.compute_run_inputs input on input.compute_run_id = run.id
    where run.id = parent_id
      and input.owner_user_id = trip.user_id and input.trip_plan_id = run.trip_plan_id
      and input.base_schedule_version_id is not distinct from run.schedule_version_id
      and input.run_type = run.run_type and input.contract_version = run.contract_version
      and input.algorithm_version = run.algorithm_version
      and input.command_input_hash = run.input_hash
      and not input.location_supplied and input.coarse_location is null
      and input.location_precision_meters is null and input.location_policy_version is null
      and input.location_observed_at is null and input.location_expires_at is null
      and input.location_redacted_at is null
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return null;
end;
$$;
revoke all on function timing_jeju_private.require_compute_input_lineage()
  from public, anon, authenticated, service_role;
create constraint trigger compute_parent_input_lineage
  after insert or update on public.compute_runs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_compute_input_lineage();
create constraint trigger compute_input_parent_lineage
  after insert or update or delete on public.compute_run_inputs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_compute_input_lineage();

-- Generation/revision share the same closed command-input lifetime as compute.
-- Lock the parent before checking input deletion so concurrent writers serialize.
create function timing_jeju_private.require_planner_input_lineage()
returns trigger language plpgsql security invoker set search_path = ''
as $$
declare parent_id uuid; parent_kind text;
begin
  if tg_table_name = 'itinerary_generation_runs' then
    parent_id := new.id; parent_kind := 'generation';
  elsif tg_table_name = 'schedule_revision_runs' then
    parent_id := new.id; parent_kind := 'revision';
  elsif tg_op = 'DELETE' then
    parent_id := coalesce(old.generation_run_id, old.schedule_revision_run_id);
    parent_kind := case when old.generation_run_id is not null then 'generation' else 'revision' end;
  else
    parent_id := coalesce(new.generation_run_id, new.schedule_revision_run_id);
    parent_kind := case when new.generation_run_id is not null then 'generation' else 'revision' end;
  end if;
  if parent_id is null then return null; end if;
  if parent_kind = 'generation' then
    perform 1 from public.itinerary_generation_runs where id = parent_id for update;
  else
    perform 1 from public.schedule_revision_runs where id = parent_id for update;
  end if;
  if not found then return null; end if;
  if not exists (
    select 1 from public.compute_run_inputs input
    join public.trip_plans trip on trip.id = input.trip_plan_id and trip.user_id = input.owner_user_id
    left join public.itinerary_generation_runs generation on generation.id = input.generation_run_id
    left join public.schedule_revision_runs revision on revision.id = input.schedule_revision_run_id
    where not input.location_supplied and input.coarse_location is null
      and input.location_precision_meters is null and input.location_policy_version is null
      and input.location_observed_at is null and input.location_expires_at is null
      and input.location_redacted_at is null
      and (
        (parent_kind = 'generation' and input.generation_run_id = parent_id
          and generation.trip_plan_id = input.trip_plan_id
          and generation.requested_by_user_id = input.owner_user_id
          and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
          and input.run_type = 'itinerary_generation'
          and generation.contract_version = input.contract_version
          and generation.algorithm_version = input.algorithm_version
          and generation.structured_input = input.structured_input)
        or (parent_kind = 'revision' and input.schedule_revision_run_id = parent_id
          and revision.trip_plan_id = input.trip_plan_id
          and revision.owner_user_id = input.owner_user_id
          and revision.base_schedule_version_id = input.base_schedule_version_id
          and input.run_type = 'schedule_revision'
          and revision.contract_version = input.contract_version
          and revision.algorithm_version = input.algorithm_version)
      )
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return null;
end;
$$;
revoke all on function timing_jeju_private.require_planner_input_lineage()
  from public, anon, authenticated, service_role;
create constraint trigger generation_parent_input_lineage
  after insert or update on public.itinerary_generation_runs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_planner_input_lineage();
create constraint trigger revision_parent_input_lineage
  after insert or update on public.schedule_revision_runs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_planner_input_lineage();
create constraint trigger planner_input_parent_lineage
  after insert or update or delete on public.compute_run_inputs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_planner_input_lineage();

-- This establishes command lineage only. The distinct MCP wire hash still needs
-- the zero-location wire contract and validation in #224/MCP 0.8.
create function timing_jeju_private.require_mcp_command_lineage()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.legacy_contract or num_nonnulls(new.compute_run_id, new.generation_run_id,
      new.schedule_revision_run_id) <> 1 or not exists (
    select 1 from public.compute_run_inputs input
    join public.trip_plans trip on trip.id = input.trip_plan_id and trip.user_id = input.owner_user_id
    left join public.compute_runs run on run.id = input.compute_run_id
    left join public.itinerary_generation_runs generation on generation.id = input.generation_run_id
    left join public.schedule_revision_runs revision on revision.id = input.schedule_revision_run_id
    where input.command_input_hash = new.command_input_hash
      and not input.location_supplied and input.coarse_location is null
      and input.location_precision_meters is null and input.location_policy_version is null
      and input.location_observed_at is null and input.location_expires_at is null
      and input.location_redacted_at is null
      and (
        (new.compute_run_id = input.compute_run_id
          and run.trip_plan_id = input.trip_plan_id
          and run.schedule_version_id is not distinct from input.base_schedule_version_id
          and run.run_type = input.run_type and run.contract_version = input.contract_version
          and run.algorithm_version = input.algorithm_version
          and run.input_hash = input.command_input_hash)
        or (new.generation_run_id = input.generation_run_id
          and generation.trip_plan_id = input.trip_plan_id
          and generation.requested_by_user_id = input.owner_user_id
          and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
          and input.run_type = 'itinerary_generation'
          and generation.contract_version = input.contract_version
          and generation.algorithm_version = input.algorithm_version
          and generation.structured_input = input.structured_input)
        or (new.schedule_revision_run_id = input.schedule_revision_run_id
          and revision.trip_plan_id = input.trip_plan_id
          and revision.owner_user_id = input.owner_user_id
          and revision.base_schedule_version_id = input.base_schedule_version_id
          and input.run_type = 'schedule_revision'
          and revision.contract_version = input.contract_version
          and revision.algorithm_version = input.algorithm_version)
      )
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.require_mcp_command_lineage()
  from public, anon, authenticated, service_role;
create trigger aaa_mcp_command_lineage
  before insert or update on public.mcp_compute_call_logs
  for each row execute function timing_jeju_private.require_mcp_command_lineage();

-- Owner-only verification: expose fixed object names and counts, never values or
-- derived hashes. Opaque wire hashes and replay receipts prevent a zero verdict.
create function timing_jeju_planner_private.user_location_residue_counts()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select 'trip_execution_events'::text, count(*) from public.trip_execution_events
  where location is not null or timing_jeju_private.user_json_contains_location(metadata)
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
  where timing_jeju_private.user_json_contains_location(raw_answers)
  union all
  select 'trip_items.facts', count(*) from public.trip_items
  where timing_jeju_private.user_json_contains_location(facts)
  union all
  select 'trip_legs.facts', count(*) from public.trip_legs
  where timing_jeju_private.user_json_contains_location(facts)
  union all
  select 'itinerary_generation_runs.structured_input', count(*) from public.itinerary_generation_runs
  where timing_jeju_private.user_json_contains_location(structured_input)
  union all
  select 'ai_messages.structured_payload', count(*) from public.ai_messages
  where timing_jeju_private.user_json_contains_location(structured_payload)
  union all
  select 'compute_runs.result_summary', count(*) from public.compute_runs
  where timing_jeju_private.user_json_contains_location(result_summary)
  union all
  select 'risk_events.computed_facts', count(*) from public.risk_events
  where timing_jeju_private.user_json_contains_location(computed_facts)
  union all
  select 'trip_weather_impacts.computed_facts', count(*) from public.trip_weather_impacts
  where timing_jeju_private.user_json_contains_location(computed_facts)
  union all
  select 'recommendation_candidates.facts', count(*) from public.recommendation_candidates
  where timing_jeju_private.user_json_contains_location(facts)
  union all
  select 'recovery_options.change_summary', count(*) from public.recovery_options
  where timing_jeju_private.user_json_contains_location(change_summary)
  union all
  select 'recovery_option_changes', count(*) from public.recovery_option_changes
  where timing_jeju_private.user_json_contains_location(before_value)
    or timing_jeju_private.user_json_contains_location(after_value)
  union all
  select 'compute_runs.missing_input', count(*) from public.compute_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.compute_run_id = run.id)
  union all
  select 'itinerary_generation_runs.missing_input', count(*) from public.itinerary_generation_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.generation_run_id = run.id)
  union all
  select 'schedule_revision_runs.missing_input', count(*) from public.schedule_revision_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.schedule_revision_run_id = run.id)
  union all
  select 'unclassified_api_idempotency_records', count(*) from public.api_idempotency_records
  union all
  -- Non-location command lineage does not prove the independent MCP wire was location-free.
  -- Proven location-linked logs were already removed with their parents; do not guess-delete others.
  select 'unclassified_mcp_compute_call_logs', count(*) from public.mcp_compute_call_logs;
$$;
revoke all on function timing_jeju_planner_private.user_location_residue_counts()
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count <> 0) then
    raise exception using errcode = '23514', message = 'user location residue requires audit';
  end if;
end;
$$;

-- This identifies the successful DB guard/purge migration only. The next schema
-- migration must also re-run residue verification; this is not fleet readiness.
create function timing_jeju_planner_private.user_location_guard_purge_revision()
returns text language sql immutable security invoker set search_path = ''
as $$ select '20260918000017'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_guard_purge_revision()
  from public, anon, authenticated, service_role;


-- Issue #223: an opaque revision request hash is not a proven non-location command hash.
lock table public.schedule_revision_runs, public.compute_run_inputs in access exclusive mode;

do $$
begin
  if timing_jeju_planner_private.user_location_guard_purge_revision() <> '20260918000017' then
    raise exception using errcode = '23514', message = 'location cutover predecessor mismatch';
  end if;
end;
$$;

alter function timing_jeju_planner_private.user_location_residue_counts()
  rename to user_location_residue_counts_v17;
create function timing_jeju_planner_private.user_location_residue_counts()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select * from timing_jeju_planner_private.user_location_residue_counts_v17()
  union all
  select 'unclassified_schedule_revision_request_hashes', count(*)
  from public.schedule_revision_runs;
$$;
revoke all on function timing_jeju_planner_private.user_location_residue_counts()
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count <> 0) then
    raise exception using errcode = '23514', message = 'user location residue requires audit';
  end if;
end;
$$;

create or replace function timing_jeju_planner_private.user_location_guard_purge_revision()
returns text language sql immutable security invoker set search_path = ''
as $$ select '20260918000018'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_guard_purge_revision()
  from public, anon, authenticated, service_role;

-- Issue #223 post-merge remediation: close opaque hash and generic JSON provenance gaps.
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


insert into supabase_migrations.schema_migrations(version, name, statements)
values
  ('20260918000017', 'user_location_write_guard_purge', array[$timing_jeju_migration_017$-- Issue #223: reject new location writes, audit legacy lineage, and require zero residue atomically.
begin;
lock table public.trip_plans, public.trip_schedule_versions,
  public.compute_runs, public.itinerary_generation_runs, public.schedule_revision_runs,
  public.compute_run_inputs, public.trip_execution_events, public.live_state_snapshots,
  public.mcp_compute_call_logs, public.itinerary_generation_candidates,
  public.recovery_options, public.recovery_option_changes, public.trip_items, public.trip_legs,
  public.mobility_route_snapshots, public.trip_preferences, public.ai_conversations, public.ai_messages,
  public.risk_events, public.trip_weather_impacts, public.recommendation_candidates,
  public.trip_item_progress, public.api_idempotency_records
  in access exclusive mode;

create function timing_jeju_private.reject_command_input_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.location_supplied is distinct from false
     or new.coarse_location is not null or new.location_precision_meters is not null
     or new.location_policy_version is not null or new.location_observed_at is not null
     or new.location_expires_at is not null or new.location_redacted_at is not null then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_command_input_location()
  from public, anon, authenticated, service_role;
create trigger aaa_command_input_no_location
before insert or update on public.compute_run_inputs
for each row execute function timing_jeju_private.reject_command_input_location();

create function timing_jeju_private.reject_execution_event_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.location is not null or not coalesce(
    new.metadata = '{}'::jsonb or (
      jsonb_typeof(new.metadata) = 'object'
      and new.metadata - 'source' = '{}'::jsonb
      and new.metadata ->> 'source' in ('manual', 'time', 'system', 'mobile')
    ), false
  ) then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_execution_event_location()
  from public, anon, authenticated, service_role;
create trigger aaa_execution_event_no_location
before insert or update on public.trip_execution_events
for each row execute function timing_jeju_private.reject_execution_event_location();

create function timing_jeju_private.reject_live_state_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.current_location is not null or new.current_place_id is not null
     or new.facts is distinct from '{}'::jsonb or new.next_action is not null then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_live_state_location()
  from public, anon, authenticated, service_role;
create trigger aaa_live_state_no_location
before insert or update on public.live_state_snapshots
for each row execute function timing_jeju_private.reject_live_state_location();

-- The audit helpers only inspect user-owned generic JSON, never public geodata.
create function timing_jeju_private.is_user_location_key(input_key text)
returns boolean language sql immutable strict security invoker set search_path = ''
as $$
  select lower(regexp_replace(input_key, '[^a-zA-Z0-9]', '', 'g')) = any(array[
    'location', 'lat', 'lng', 'lon', 'latitude', 'longitude', 'coordinates',
    'accuracymeters', 'currentlocation', 'currentplaceid', 'currentstopid',
    'currentregion', 'currentregioncode', 'nearestplaceid', 'neareststopid',
    'nearestregion', 'nearestregioncode', 'gridx', 'gridy', 'grid100m', 'geohash',
    'coarselocation', 'locationdigest', 'locationhash', 'locationprecisionmeters',
    'locationobservedat', 'locationexpiresat', 'locationredactedat', 'locationsupplied'
  ]);
$$;
revoke all on function timing_jeju_private.is_user_location_key(text)
  from public, anon, authenticated, service_role;

create function timing_jeju_private.user_json_contains_location(input_value jsonb)
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

-- Deny known current/derived location keys before PostgreSQL constraint DETAIL
-- could reflect the submitted JSON. The function only examines NEW; it performs
-- no reads/writes with its definer privileges and has a closed search_path.
create function timing_jeju_private.reject_user_json_location()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare field_name text; row_value jsonb := to_jsonb(new);
begin
  foreach field_name in array tg_argv loop
    if timing_jeju_private.user_json_contains_location(row_value -> field_name) then
      raise exception using errcode = '23514', message = 'user location storage is disabled';
    end if;
  end loop;
  return new;
end;
$$;
revoke all on function timing_jeju_private.reject_user_json_location()
  from public, anon, authenticated, service_role;

create trigger aaa_user_json_no_location before insert or update on public.trip_preferences
  for each row execute function timing_jeju_private.reject_user_json_location('raw_answers');
create trigger aaa_user_json_no_location before insert or update on public.trip_legs
  for each row execute function timing_jeju_private.reject_user_json_location('facts');
create trigger aaa_user_json_no_location before insert or update on public.itinerary_generation_runs
  for each row execute function timing_jeju_private.reject_user_json_location('structured_input');
create trigger aaa_user_json_no_location before insert or update on public.ai_messages
  for each row execute function timing_jeju_private.reject_user_json_location('structured_payload');
create trigger aaa_user_json_no_location before insert or update on public.compute_runs
  for each row execute function timing_jeju_private.reject_user_json_location('result_summary');
create trigger aaa_user_json_no_location before insert or update on public.risk_events
  for each row execute function timing_jeju_private.reject_user_json_location('computed_facts');
create trigger aaa_user_json_no_location before insert or update on public.trip_weather_impacts
  for each row execute function timing_jeju_private.reject_user_json_location('computed_facts');
create trigger aaa_user_json_no_location before insert or update on public.recommendation_candidates
  for each row execute function timing_jeju_private.reject_user_json_location('facts');
create trigger aaa_user_json_no_location before insert or update on public.recovery_options
  for each row execute function timing_jeju_private.reject_user_json_location('change_summary');
create trigger aaa_user_json_no_location before insert or update on public.recovery_option_changes
  for each row execute function timing_jeju_private.reject_user_json_location('before_value', 'after_value');

-- Preserve unknown non-location leaves, including empty containers, for audit failure.
-- Only wrappers emptied by removing an identified location field may be discarded.
create function timing_jeju_private.remove_event_location_fields(input_value jsonb)
returns jsonb language plpgsql immutable strict security invoker set search_path = ''
as $$
declare entry record; cleaned jsonb; result jsonb;
begin
  if jsonb_typeof(input_value) = 'object' then
    result := '{}'::jsonb;
    for entry in select key, value from jsonb_each(input_value) loop
      if timing_jeju_private.is_user_location_key(entry.key)
         or (entry.key = 'type' and entry.value = '"GRID_100M"'::jsonb) then continue; end if;
      cleaned := timing_jeju_private.remove_event_location_fields(entry.value);
      if cleaned in ('{}'::jsonb, '[]'::jsonb)
         and timing_jeju_private.user_json_contains_location(entry.value) then continue; end if;
      result := result || jsonb_build_object(entry.key, cleaned);
    end loop;
    return result;
  elsif jsonb_typeof(input_value) = 'array' then
    result := '[]'::jsonb;
    for entry in select value from jsonb_array_elements(input_value) loop
      cleaned := timing_jeju_private.remove_event_location_fields(entry.value);
      if cleaned in ('{}'::jsonb, '[]'::jsonb)
         and timing_jeju_private.user_json_contains_location(entry.value) then continue; end if;
      result := result || jsonb_build_array(cleaned);
    end loop;
    return result;
  end if;
  return input_value;
end;
$$;
revoke all on function timing_jeju_private.remove_event_location_fields(jsonb)
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (
    select 1 from public.trip_execution_events event
    cross join lateral (
      select timing_jeju_private.remove_event_location_fields(event.metadata) as metadata
    ) cleaned
    where not coalesce(cleaned.metadata = '{}'::jsonb or (
      jsonb_typeof(cleaned.metadata) = 'object' and cleaned.metadata - 'source' = '{}'::jsonb
      and cleaned.metadata ->> 'source' in ('manual', 'time', 'system', 'mobile')
    ), false)
  ) then
    raise exception using errcode = '23514', message = 'legacy non-location metadata requires audit';
  end if;
end;
$$;

-- Audit every direction before deleting anything. Store identifiers only; raw
-- location and command hashes are compared in-place and never copied or printed.
create temporary table audited_command_input_scope on commit drop as
select input.id, input.compute_run_id, input.generation_run_id, input.schedule_revision_run_id
from public.compute_run_inputs input
join public.trip_plans trip on trip.id = input.trip_plan_id and trip.user_id = input.owner_user_id
left join public.compute_runs run on run.id = input.compute_run_id
left join public.itinerary_generation_runs generation on generation.id = input.generation_run_id
left join public.schedule_revision_runs revision on revision.id = input.schedule_revision_run_id
where num_nonnulls(input.compute_run_id, input.generation_run_id, input.schedule_revision_run_id) = 1
  and input.schema_version = 1
  and public.command_input_matches_schema(input.run_type, input.schema_version, input.structured_input)
  and (
    (input.location_redacted_at is not null and input.location_supplied
      and input.coarse_location is null and input.location_precision_meters is null
      and input.location_policy_version is null and input.location_observed_at is null
      and input.location_expires_at is null)
    or (input.location_redacted_at is null and input.command_input_hash =
      public.compute_command_input_hash(input.run_type::text,input.schema_version::smallint,
        input.contract_version::text,input.algorithm_version::text,input.base_schedule_version_id::uuid,
        input.structured_input::jsonb,input.location_supplied::boolean,input.coarse_location::jsonb))
  )
  and (
    (run.id is not null and run.trip_plan_id = input.trip_plan_id
      and run.schedule_version_id is not distinct from input.base_schedule_version_id
      and run.run_type = input.run_type and run.contract_version = input.contract_version
      and run.algorithm_version = input.algorithm_version and run.input_hash = input.command_input_hash)
    or (generation.id is not null and generation.trip_plan_id = input.trip_plan_id
      and generation.requested_by_user_id = input.owner_user_id
      and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
      and input.run_type = 'itinerary_generation' and generation.contract_version = input.contract_version
      and generation.algorithm_version = input.algorithm_version
      and generation.structured_input = input.structured_input)
    or (revision.id is not null and revision.trip_plan_id = input.trip_plan_id
      and revision.owner_user_id = input.owner_user_id
      and revision.base_schedule_version_id = input.base_schedule_version_id
      and input.run_type = 'schedule_revision' and revision.contract_version = input.contract_version
      and revision.algorithm_version = input.algorithm_version)
  );
alter table audited_command_input_scope add primary key (id);

do $$
begin
  if exists (
    select 1 from public.compute_run_inputs input
    where not exists (select 1 from pg_temp.audited_command_input_scope scope where scope.id = input.id)
    union all
    select 1 from public.compute_runs run
    where (select count(*) from pg_temp.audited_command_input_scope scope where scope.compute_run_id = run.id) <> 1
    union all
    select 1 from public.itinerary_generation_runs run
    where (select count(*) from pg_temp.audited_command_input_scope scope where scope.generation_run_id = run.id) <> 1
    union all
    select 1 from public.schedule_revision_runs run
    where (select count(*) from pg_temp.audited_command_input_scope scope where scope.schedule_revision_run_id = run.id) <> 1
    union all
    select 1 from public.mcp_compute_call_logs log
    where log.legacy_contract
      or num_nonnulls(log.compute_run_id, log.generation_run_id, log.schedule_revision_run_id) <> 1
      or not exists (
        select 1 from public.compute_run_inputs input
        join pg_temp.audited_command_input_scope scope on scope.id = input.id
        where input.command_input_hash = log.command_input_hash and (
          (log.compute_run_id is not null and scope.compute_run_id = log.compute_run_id)
          or (log.generation_run_id is not null and scope.generation_run_id = log.generation_run_id)
          or (log.schedule_revision_run_id is not null and scope.schedule_revision_run_id = log.schedule_revision_run_id)
        )
      )
  ) then
    raise exception using errcode = '23514', message = 'legacy compute hash lineage requires audit';
  end if;
end;
$$;

-- Freeze only identifiers; never copy a location or a derived hash into the audit scope.
create temporary table location_input_scope on commit drop as
select id, trip_plan_id, compute_run_id, generation_run_id, schedule_revision_run_id
from public.compute_run_inputs
where location_supplied or coarse_location is not null
   or location_precision_meters is not null or location_policy_version is not null
   or location_observed_at is not null or location_expires_at is not null
   or location_redacted_at is not null;
alter table location_input_scope add primary key (id);

do $$
begin
  if exists (
    select 1 from pg_temp.location_input_scope scope
    join public.compute_runs run on run.id = scope.compute_run_id
    where run.status in ('queued', 'running')
    union all
    select 1 from pg_temp.location_input_scope scope
    join public.itinerary_generation_runs run on run.id = scope.generation_run_id
    where run.status in ('queued', 'running')
    union all
    select 1 from pg_temp.location_input_scope scope
    join public.schedule_revision_runs run on run.id = scope.schedule_revision_run_id
    where run.status in ('queued', 'running')
  ) then
    raise exception using errcode = '23514', message = 'active location lineage requires audit';
  end if;
end;
$$;

-- Capture output identities before any parent CASCADE can erase their lineage.
create temporary table location_version_scope on commit drop as
with recursive versions(id, trip_plan_id) as (
  (
    select candidate.schedule_version_id, candidate.trip_plan_id
    from public.itinerary_generation_candidates candidate
    join pg_temp.location_input_scope input on input.generation_run_id = candidate.generation_run_id
    union
    select option.proposed_schedule_version_id, option.trip_plan_id
    from public.recovery_options option
    join pg_temp.location_input_scope input on input.compute_run_id = option.compute_run_id
  )
  union
  select child.id, child.trip_plan_id
  from public.trip_schedule_versions child
  join versions parent on child.base_schedule_version_id = parent.id and child.trip_plan_id = parent.trip_plan_id
)
select id, trip_plan_id from versions;
alter table location_version_scope add primary key (id);

create temporary table location_route_scope on commit drop as
select route.id from public.mobility_route_snapshots route
join pg_temp.location_version_scope version on version.id = route.schedule_version_id;
alter table location_route_scope add primary key (id);

do $$
begin
  if exists (
    select 1 from public.trip_schedule_versions version
    join pg_temp.location_version_scope scope on scope.id = version.id
    where version.status in ('active', 'superseded') or version.applied_at is not null
    union all
    select 1 from public.trip_plans trip
    join pg_temp.location_version_scope scope on scope.id = trip.active_schedule_version_id
    union all
    select 1 from public.itinerary_generation_candidates candidate
    join pg_temp.location_version_scope scope on scope.id = candidate.schedule_version_id
    where candidate.selected_at is not null
    union all
    select 1 from public.recovery_options option
    join pg_temp.location_input_scope input on input.compute_run_id = option.compute_run_id
    where option.status = 'applied' or option.applied_at is not null or option.selected_at is not null
  ) then
    raise exception using errcode = '23514', message = 'active location lineage requires audit';
  end if;

  -- An external command, a manual execution record, or a cross-run output needs
  -- a separate provenance decision. Never let a CASCADE silently decide for us.
  if exists (
    select 1 from public.compute_run_inputs input
    join pg_temp.location_version_scope scope on scope.id = input.base_schedule_version_id
    union all
    select 1 from public.compute_runs run
    join pg_temp.location_version_scope scope on scope.id = run.schedule_version_id
    union all
    select 1 from public.itinerary_generation_runs run
    join pg_temp.location_version_scope scope on scope.id = run.base_schedule_version_id
    union all
    select 1 from public.schedule_revision_runs run
    join pg_temp.location_version_scope scope on scope.id = run.base_schedule_version_id
    union all
    select 1 from public.itinerary_generation_candidates candidate
    join pg_temp.location_version_scope scope on scope.id = candidate.schedule_version_id
    where not exists (select 1 from pg_temp.location_input_scope input
      where input.generation_run_id = candidate.generation_run_id)
    union all
    select 1 from public.recovery_options option
    where (
      exists (select 1 from pg_temp.location_version_scope scope
        where scope.id in (option.base_schedule_version_id, option.proposed_schedule_version_id))
      or exists (select 1 from public.risk_events risk
        join pg_temp.location_input_scope input on input.compute_run_id = risk.compute_run_id
        where risk.id = option.trigger_risk_event_id)
    ) and not exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = option.compute_run_id)
    union all
    select 1 from public.trip_execution_events event
    join pg_temp.location_version_scope scope on scope.id = event.schedule_version_id
    union all
    select 1 from public.trip_item_progress progress
    join pg_temp.location_version_scope scope on scope.id = progress.schedule_version_id
    union all
    select 1 from public.ai_messages message
    join pg_temp.location_input_scope scope on scope.generation_run_id = message.generation_run_id
    join public.compute_run_inputs input on input.id = scope.id
    join public.ai_conversations conversation on conversation.id = message.conversation_id
    where message.role not in ('assistant', 'tool')
      or conversation.user_id is distinct from input.owner_user_id
      or conversation.trip_plan_id is distinct from input.trip_plan_id
    union all
    select 1 from public.trip_legs leg
    join pg_temp.location_route_scope route on route.id = leg.mobility_route_snapshot_id
    where not exists (select 1 from pg_temp.location_version_scope scope where scope.id = leg.schedule_version_id)
  ) then
    raise exception using errcode = '23514', message = 'external location lineage requires audit';
  end if;
end;
$$;

-- Only the audited location fields change. The append-only trigger is restored in
-- this same transaction; an exception rolls back both the data and trigger state.
alter table public.trip_execution_events disable trigger trg_trip_execution_events_append_only;
update public.trip_execution_events
set location = null,
    metadata = timing_jeju_private.remove_event_location_fields(metadata)
where location is not null or timing_jeju_private.user_json_contains_location(metadata);
alter table public.trip_execution_events enable trigger trg_trip_execution_events_append_only;

-- Discard the derived live projection, preserving its trip, schedule and manual events.
delete from public.live_state_snapshots
where current_location is not null or current_place_id is not null
   or facts is distinct from '{}'::jsonb or next_action is not null;

-- Non-location manual events remain append-only. Only computed live projections
-- and proven generated messages belonging to the audited parent are discarded.
delete from public.live_state_snapshots live
where exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = live.compute_run_id)
   or exists (select 1 from pg_temp.location_version_scope scope where scope.id = live.schedule_version_id);
delete from public.ai_messages message
where exists (select 1 from pg_temp.location_input_scope input where input.generation_run_id = message.generation_run_id);

-- Remove changes' item references before removing proposed versions. Parent
-- deletion then removes inputs, MCP metadata and result children in the same TX.
delete from public.recovery_options option
where exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = option.compute_run_id);
delete from public.compute_runs run
where exists (select 1 from pg_temp.location_input_scope input where input.compute_run_id = run.id);
delete from public.itinerary_generation_runs run
where exists (select 1 from pg_temp.location_input_scope input where input.generation_run_id = run.id);
delete from public.schedule_revision_runs run
where exists (select 1 from pg_temp.location_input_scope input where input.schedule_revision_run_id = run.id);

-- Break only the audited version/item/route reference cycle. Keep all FK objects
-- and row guards, validate outstanding references, then restore original timing.
create temporary table route_constraint_audit on commit drop as
select constraint_record.oid, conname, condeferrable, condeferred, convalidated,
  pg_get_constraintdef(constraint_record.oid) as definition
from pg_constraint constraint_record
where connamespace = 'public'::regnamespace
  and conname in ('fk_route_planned_version', 'fk_route_planned_origin_item', 'fk_route_planned_destination_item');
create temporary table route_trigger_audit on commit drop as
select trigger_record.oid, tgconstraint, tgname, tgenabled, tgdeferrable, tginitdeferred
from pg_trigger trigger_record
join pg_temp.route_constraint_audit constraint_record on constraint_record.oid = trigger_record.tgconstraint;

do $$
declare removed integer;
begin
  if (select count(*) from pg_temp.route_constraint_audit) <> 3 or exists (
    select 1 from pg_constraint constraint_record
    join pg_temp.route_constraint_audit expected on expected.oid = constraint_record.oid
    where constraint_record.conrelid <> 'public.mobility_route_snapshots'::regclass
      or constraint_record.contype <> 'f' or constraint_record.condeferrable
      or constraint_record.condeferred or not constraint_record.convalidated
  ) then
    raise exception using errcode = '23514', message = 'planned route constraint state requires audit';
  end if;

  alter table public.mobility_route_snapshots alter constraint fk_route_planned_version deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_origin_item deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_destination_item deferrable initially immediate;
  set constraints public.fk_route_planned_version, public.fk_route_planned_origin_item,
    public.fk_route_planned_destination_item deferred;

  update public.trip_schedule_versions version set status = 'rejected'
  where version.status = 'candidate' and exists (
    select 1 from pg_temp.location_version_scope scope where scope.id = version.id);
  loop
    delete from public.trip_schedule_versions version
    where exists (select 1 from pg_temp.location_version_scope scope where scope.id = version.id)
      and not exists (select 1 from public.trip_schedule_versions child where child.base_schedule_version_id = version.id);
    get diagnostics removed = row_count;
    exit when removed = 0;
  end loop;
  if exists (select 1 from public.trip_schedule_versions version
    join pg_temp.location_version_scope scope on scope.id = version.id) then
    raise exception using errcode = '23514', message = 'cyclic location lineage requires audit';
  end if;
  delete from public.mobility_route_snapshots route
  where exists (select 1 from pg_temp.location_route_scope scope where scope.id = route.id);

  set constraints public.fk_route_planned_version, public.fk_route_planned_origin_item,
    public.fk_route_planned_destination_item immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_version not deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_origin_item not deferrable initially immediate;
  alter table public.mobility_route_snapshots alter constraint fk_route_planned_destination_item not deferrable initially immediate;

  if exists (
    select 1 from pg_temp.route_constraint_audit expected
    left join pg_constraint actual on actual.oid = expected.oid
    where actual.oid is null or actual.conname is distinct from expected.conname
      or actual.condeferrable is distinct from expected.condeferrable
      or actual.condeferred is distinct from expected.condeferred
      or actual.convalidated is distinct from expected.convalidated
      or pg_get_constraintdef(actual.oid) is distinct from expected.definition
    union all
    select 1 from pg_temp.route_trigger_audit expected
    left join pg_trigger actual on actual.oid = expected.oid
    where actual.oid is null or actual.tgconstraint is distinct from expected.tgconstraint
      or actual.tgname is distinct from expected.tgname or actual.tgenabled is distinct from expected.tgenabled
      or actual.tgdeferrable is distinct from expected.tgdeferrable
      or actual.tginitdeferred is distinct from expected.tginitdeferred
  ) then
    raise exception using errcode = '23514', message = 'planned route constraint restoration failed';
  end if;
exception
  when integrity_constraint_violation then
    raise exception using errcode = '23514', message = 'location purge integrity requires audit';
end;
$$;

-- Parent and input must be created in one transaction. Check the final state,
-- including input deletion, rather than requiring an input before parent INSERT.
create function timing_jeju_private.require_compute_input_lineage()
returns trigger language plpgsql security invoker set search_path = ''
as $$
declare parent_id uuid;
begin
  if tg_table_name = 'compute_runs' then
    parent_id := new.id;
  elsif tg_op = 'DELETE' then
    parent_id := old.compute_run_id;
  else
    parent_id := new.compute_run_id;
  end if;
  if parent_id is null then return null; end if;
  perform 1 from public.compute_runs where id = parent_id for update;
  -- Cascading parent deletion leaves no hash to validate.
  if not found then return null; end if;
  if not exists (
    select 1 from public.compute_runs run
    join public.trip_plans trip on trip.id = run.trip_plan_id
    join public.compute_run_inputs input on input.compute_run_id = run.id
    where run.id = parent_id
      and input.owner_user_id = trip.user_id and input.trip_plan_id = run.trip_plan_id
      and input.base_schedule_version_id is not distinct from run.schedule_version_id
      and input.run_type = run.run_type and input.contract_version = run.contract_version
      and input.algorithm_version = run.algorithm_version
      and input.command_input_hash = run.input_hash
      and not input.location_supplied and input.coarse_location is null
      and input.location_precision_meters is null and input.location_policy_version is null
      and input.location_observed_at is null and input.location_expires_at is null
      and input.location_redacted_at is null
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return null;
end;
$$;
revoke all on function timing_jeju_private.require_compute_input_lineage()
  from public, anon, authenticated, service_role;
create constraint trigger compute_parent_input_lineage
  after insert or update on public.compute_runs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_compute_input_lineage();
create constraint trigger compute_input_parent_lineage
  after insert or update or delete on public.compute_run_inputs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_compute_input_lineage();

-- Generation/revision share the same closed command-input lifetime as compute.
-- Lock the parent before checking input deletion so concurrent writers serialize.
create function timing_jeju_private.require_planner_input_lineage()
returns trigger language plpgsql security invoker set search_path = ''
as $$
declare parent_id uuid; parent_kind text;
begin
  if tg_table_name = 'itinerary_generation_runs' then
    parent_id := new.id; parent_kind := 'generation';
  elsif tg_table_name = 'schedule_revision_runs' then
    parent_id := new.id; parent_kind := 'revision';
  elsif tg_op = 'DELETE' then
    parent_id := coalesce(old.generation_run_id, old.schedule_revision_run_id);
    parent_kind := case when old.generation_run_id is not null then 'generation' else 'revision' end;
  else
    parent_id := coalesce(new.generation_run_id, new.schedule_revision_run_id);
    parent_kind := case when new.generation_run_id is not null then 'generation' else 'revision' end;
  end if;
  if parent_id is null then return null; end if;
  if parent_kind = 'generation' then
    perform 1 from public.itinerary_generation_runs where id = parent_id for update;
  else
    perform 1 from public.schedule_revision_runs where id = parent_id for update;
  end if;
  if not found then return null; end if;
  if not exists (
    select 1 from public.compute_run_inputs input
    join public.trip_plans trip on trip.id = input.trip_plan_id and trip.user_id = input.owner_user_id
    left join public.itinerary_generation_runs generation on generation.id = input.generation_run_id
    left join public.schedule_revision_runs revision on revision.id = input.schedule_revision_run_id
    where not input.location_supplied and input.coarse_location is null
      and input.location_precision_meters is null and input.location_policy_version is null
      and input.location_observed_at is null and input.location_expires_at is null
      and input.location_redacted_at is null
      and (
        (parent_kind = 'generation' and input.generation_run_id = parent_id
          and generation.trip_plan_id = input.trip_plan_id
          and generation.requested_by_user_id = input.owner_user_id
          and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
          and input.run_type = 'itinerary_generation'
          and generation.contract_version = input.contract_version
          and generation.algorithm_version = input.algorithm_version
          and generation.structured_input = input.structured_input)
        or (parent_kind = 'revision' and input.schedule_revision_run_id = parent_id
          and revision.trip_plan_id = input.trip_plan_id
          and revision.owner_user_id = input.owner_user_id
          and revision.base_schedule_version_id = input.base_schedule_version_id
          and input.run_type = 'schedule_revision'
          and revision.contract_version = input.contract_version
          and revision.algorithm_version = input.algorithm_version)
      )
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return null;
end;
$$;
revoke all on function timing_jeju_private.require_planner_input_lineage()
  from public, anon, authenticated, service_role;
create constraint trigger generation_parent_input_lineage
  after insert or update on public.itinerary_generation_runs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_planner_input_lineage();
create constraint trigger revision_parent_input_lineage
  after insert or update on public.schedule_revision_runs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_planner_input_lineage();
create constraint trigger planner_input_parent_lineage
  after insert or update or delete on public.compute_run_inputs
  deferrable initially deferred for each row
  execute function timing_jeju_private.require_planner_input_lineage();

-- This establishes command lineage only. The distinct MCP wire hash still needs
-- the zero-location wire contract and validation in #224/MCP 0.8.
create function timing_jeju_private.require_mcp_command_lineage()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.legacy_contract or num_nonnulls(new.compute_run_id, new.generation_run_id,
      new.schedule_revision_run_id) <> 1 or not exists (
    select 1 from public.compute_run_inputs input
    join public.trip_plans trip on trip.id = input.trip_plan_id and trip.user_id = input.owner_user_id
    left join public.compute_runs run on run.id = input.compute_run_id
    left join public.itinerary_generation_runs generation on generation.id = input.generation_run_id
    left join public.schedule_revision_runs revision on revision.id = input.schedule_revision_run_id
    where input.command_input_hash = new.command_input_hash
      and not input.location_supplied and input.coarse_location is null
      and input.location_precision_meters is null and input.location_policy_version is null
      and input.location_observed_at is null and input.location_expires_at is null
      and input.location_redacted_at is null
      and (
        (new.compute_run_id = input.compute_run_id
          and run.trip_plan_id = input.trip_plan_id
          and run.schedule_version_id is not distinct from input.base_schedule_version_id
          and run.run_type = input.run_type and run.contract_version = input.contract_version
          and run.algorithm_version = input.algorithm_version
          and run.input_hash = input.command_input_hash)
        or (new.generation_run_id = input.generation_run_id
          and generation.trip_plan_id = input.trip_plan_id
          and generation.requested_by_user_id = input.owner_user_id
          and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
          and input.run_type = 'itinerary_generation'
          and generation.contract_version = input.contract_version
          and generation.algorithm_version = input.algorithm_version
          and generation.structured_input = input.structured_input)
        or (new.schedule_revision_run_id = input.schedule_revision_run_id
          and revision.trip_plan_id = input.trip_plan_id
          and revision.owner_user_id = input.owner_user_id
          and revision.base_schedule_version_id = input.base_schedule_version_id
          and input.run_type = 'schedule_revision'
          and revision.contract_version = input.contract_version
          and revision.algorithm_version = input.algorithm_version)
      )
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_private.require_mcp_command_lineage()
  from public, anon, authenticated, service_role;
create trigger aaa_mcp_command_lineage
  before insert or update on public.mcp_compute_call_logs
  for each row execute function timing_jeju_private.require_mcp_command_lineage();

-- Owner-only verification: expose fixed object names and counts, never values or
-- derived hashes. Opaque wire hashes and replay receipts prevent a zero verdict.
create function timing_jeju_planner_private.user_location_residue_counts()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select 'trip_execution_events'::text, count(*) from public.trip_execution_events
  where location is not null or timing_jeju_private.user_json_contains_location(metadata)
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
  where timing_jeju_private.user_json_contains_location(raw_answers)
  union all
  select 'trip_items.facts', count(*) from public.trip_items
  where timing_jeju_private.user_json_contains_location(facts)
  union all
  select 'trip_legs.facts', count(*) from public.trip_legs
  where timing_jeju_private.user_json_contains_location(facts)
  union all
  select 'itinerary_generation_runs.structured_input', count(*) from public.itinerary_generation_runs
  where timing_jeju_private.user_json_contains_location(structured_input)
  union all
  select 'ai_messages.structured_payload', count(*) from public.ai_messages
  where timing_jeju_private.user_json_contains_location(structured_payload)
  union all
  select 'compute_runs.result_summary', count(*) from public.compute_runs
  where timing_jeju_private.user_json_contains_location(result_summary)
  union all
  select 'risk_events.computed_facts', count(*) from public.risk_events
  where timing_jeju_private.user_json_contains_location(computed_facts)
  union all
  select 'trip_weather_impacts.computed_facts', count(*) from public.trip_weather_impacts
  where timing_jeju_private.user_json_contains_location(computed_facts)
  union all
  select 'recommendation_candidates.facts', count(*) from public.recommendation_candidates
  where timing_jeju_private.user_json_contains_location(facts)
  union all
  select 'recovery_options.change_summary', count(*) from public.recovery_options
  where timing_jeju_private.user_json_contains_location(change_summary)
  union all
  select 'recovery_option_changes', count(*) from public.recovery_option_changes
  where timing_jeju_private.user_json_contains_location(before_value)
    or timing_jeju_private.user_json_contains_location(after_value)
  union all
  select 'compute_runs.missing_input', count(*) from public.compute_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.compute_run_id = run.id)
  union all
  select 'itinerary_generation_runs.missing_input', count(*) from public.itinerary_generation_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.generation_run_id = run.id)
  union all
  select 'schedule_revision_runs.missing_input', count(*) from public.schedule_revision_runs run
  where not exists (select 1 from public.compute_run_inputs input where input.schedule_revision_run_id = run.id)
  union all
  select 'unclassified_api_idempotency_records', count(*) from public.api_idempotency_records
  union all
  -- Non-location command lineage does not prove the independent MCP wire was location-free.
  -- Proven location-linked logs were already removed with their parents; do not guess-delete others.
  select 'unclassified_mcp_compute_call_logs', count(*) from public.mcp_compute_call_logs;
$$;
revoke all on function timing_jeju_planner_private.user_location_residue_counts()
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count <> 0) then
    raise exception using errcode = '23514', message = 'user location residue requires audit';
  end if;
end;
$$;

-- This identifies the successful DB guard/purge migration only. The next schema
-- migration must also re-run residue verification; this is not fleet readiness.
create function timing_jeju_planner_private.user_location_guard_purge_revision()
returns text language sql immutable security invoker set search_path = ''
as $$ select '20260918000017'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_guard_purge_revision()
  from public, anon, authenticated, service_role;

commit$timing_jeju_migration_017$]::text[]),
  ('20260918000018', 'revision_request_hash_audit', array[$timing_jeju_migration_018$-- Issue #223: an opaque revision request hash is not a proven non-location command hash.
begin;
lock table public.schedule_revision_runs, public.compute_run_inputs in access exclusive mode;

do $$
begin
  if timing_jeju_planner_private.user_location_guard_purge_revision() <> '20260918000017' then
    raise exception using errcode = '23514', message = 'location cutover predecessor mismatch';
  end if;
end;
$$;

alter function timing_jeju_planner_private.user_location_residue_counts()
  rename to user_location_residue_counts_v17;
create function timing_jeju_planner_private.user_location_residue_counts()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select * from timing_jeju_planner_private.user_location_residue_counts_v17()
  union all
  select 'unclassified_schedule_revision_request_hashes', count(*)
  from public.schedule_revision_runs;
$$;
revoke all on function timing_jeju_planner_private.user_location_residue_counts()
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count <> 0) then
    raise exception using errcode = '23514', message = 'user location residue requires audit';
  end if;
end;
$$;

create or replace function timing_jeju_planner_private.user_location_guard_purge_revision()
returns text language sql immutable security invoker set search_path = ''
as $$ select '20260918000018'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_guard_purge_revision()
  from public, anon, authenticated, service_role;
commit$timing_jeju_migration_018$]::text[]),
  ('20260918000020', 'location_provenance_fail_closed', array[$timing_jeju_migration_020$-- Issue #223 post-merge remediation: close opaque hash and generic JSON provenance gaps.
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

commit$timing_jeju_migration_020$]::text[]);

commit;
