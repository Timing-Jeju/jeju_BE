-- Issue #224: explicit v1-to-v2 transition; no user-location runtime or implicit fallback.
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

do $$
begin
  if timing_jeju_planner_private.user_location_guard_purge_revision() is distinct from '20260918000018' then
    raise exception using errcode='23514', message='location schema predecessor mismatch';
  end if;
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count<>0) then
    raise exception using errcode='23514', message='user location residue requires audit';
  end if;
  if exists (select 1 from public.compute_runs where status='running')
     or exists (select 1 from public.itinerary_generation_runs where status='running')
     or exists (select 1 from public.schedule_revision_runs where status='running') then
    raise exception using errcode='23514', message='location schema transition requires drained workers';
  end if;
  if not exists (select 1 from pg_catalog.pg_trigger
      where tgrelid='public.compute_run_inputs'::regclass
        and tgname='trg_compute_run_inputs_immutable' and tgenabled='O') then
    raise exception using errcode='23514', message='command input immutability protection must be enabled';
  end if;
  if exists (
    select 1 from public.compute_run_inputs input
    where input.schema_version is distinct from 1
      or not coalesce(public.command_input_matches_schema(input.run_type,input.schema_version,input.structured_input),false)
      or input.command_input_hash is distinct from public.compute_command_input_hash(
        input.run_type::text,input.schema_version::smallint,input.contract_version::text,
        input.algorithm_version::text,input.base_schedule_version_id::uuid,input.structured_input::jsonb,
        false::boolean,null::jsonb)
  ) then
    raise exception using errcode='23514', message='legacy command input requires audit';
  end if;
  if exists (
    select 1 from public.compute_run_inputs input
    left join public.trip_plans trip on trip.id=input.trip_plan_id and trip.user_id=input.owner_user_id
    left join public.compute_runs run on run.id=input.compute_run_id
    left join public.itinerary_generation_runs generation on generation.id=input.generation_run_id
    left join public.schedule_revision_runs revision on revision.id=input.schedule_revision_run_id
    where trip.id is null or not coalesce(
      (input.compute_run_id is not null and run.trip_plan_id=input.trip_plan_id
        and run.schedule_version_id is not distinct from input.base_schedule_version_id
        and run.run_type=input.run_type and run.contract_version=input.contract_version
        and run.algorithm_version=input.algorithm_version and run.input_hash=input.command_input_hash)
      or (input.generation_run_id is not null and generation.trip_plan_id=input.trip_plan_id
        and generation.requested_by_user_id=input.owner_user_id
        and generation.base_schedule_version_id is not distinct from input.base_schedule_version_id
        and input.run_type='itinerary_generation' and generation.contract_version=input.contract_version
        and generation.algorithm_version=input.algorithm_version and generation.structured_input=input.structured_input)
      or (input.schedule_revision_run_id is not null and revision.trip_plan_id=input.trip_plan_id
        and revision.owner_user_id=input.owner_user_id and revision.base_schedule_version_id=input.base_schedule_version_id
        and input.run_type='schedule_revision' and revision.contract_version=input.contract_version
        and revision.algorithm_version=input.algorithm_version),false)
  ) then
    raise exception using errcode='23514', message='legacy command input lineage requires audit';
  end if;
end;
$$;

-- Keep the closed projection and timestamp rules; only the explicit version changes.


create or replace function public.command_input_matches_schema(
  input_run_type text,
  input_schema_version smallint,
  value jsonb
)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  uuid_pattern constant text := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
begin
  if input_schema_version <> 2 or jsonb_typeof(value) <> 'object' then
    return false;
  end if;
  return case input_run_type
    when 'itinerary_generation' then
      public.command_jsonb_object_size(value) = 3
      and value ?& array['targetDayId', 'candidateCount', 'refreshExternalFacts']
      and jsonb_typeof(value -> 'targetDayId') = 'string'
      and value ->> 'targetDayId' ~* uuid_pattern
      and jsonb_typeof(value -> 'candidateCount') = 'number'
      and value ->> 'candidateCount' ~ '^[0-9]+$'
      and (value ->> 'candidateCount')::integer between 1 and 10
      and jsonb_typeof(value -> 'refreshExternalFacts') = 'boolean'
    when 'schedule_revision' then
      public.command_jsonb_object_size(value) = 3
      and value ?& array['targetDayId', 'affectedItemIds', 'instructionCodes']
      and jsonb_typeof(value -> 'targetDayId') = 'string'
      and value ->> 'targetDayId' ~* uuid_pattern
      and jsonb_typeof(value -> 'affectedItemIds') = 'array'
      and jsonb_array_length(value -> 'affectedItemIds') <= 100
      and not exists (
        select 1 from jsonb_array_elements(value -> 'affectedItemIds') item
        where jsonb_typeof(item) <> 'string' or item #>> '{}' !~* uuid_pattern
      )
      and jsonb_typeof(value -> 'instructionCodes') = 'array'
      and jsonb_array_length(value -> 'instructionCodes') <= 32
      and not exists (
        select 1 from jsonb_array_elements(value -> 'instructionCodes') code
        where jsonb_typeof(code) <> 'string' or code #>> '{}' !~ '^[A-Z][A-Z0-9_]{0,63}$'
      )
    when 'itinerary_validate' then
      public.command_jsonb_object_size(value) = 1
      and value ? 'targetDayId'
      and jsonb_typeof(value -> 'targetDayId') = 'string'
      and value ->> 'targetDayId' ~* uuid_pattern
    when 'feasibility' then
      public.command_jsonb_object_size(value) = 1
      and value ? 'refreshExternalFacts'
      and jsonb_typeof(value -> 'refreshExternalFacts') = 'boolean'
    when 'spare_time' then
      public.command_jsonb_object_size(value) = 3
      and value ?& array['targetDayId', 'windowStart', 'windowEnd']
      and jsonb_typeof(value -> 'targetDayId') = 'string'
      and value ->> 'targetDayId' ~* uuid_pattern
      and jsonb_typeof(value -> 'windowStart') = 'string'
      and public.command_input_rfc3339_timestamp_is_valid(value ->> 'windowStart')
      and jsonb_typeof(value -> 'windowEnd') = 'string'
      and public.command_input_rfc3339_timestamp_is_valid(value ->> 'windowEnd')
      and public.command_input_rfc3339_timestamp_sort_key(value ->> 'windowEnd')
          >= public.command_input_rfc3339_timestamp_sort_key(value ->> 'windowStart')
    when 'recovery' then
      public.command_jsonb_object_size(value) = 2
      and value ?& array['riskEventId', 'optionCount']
      and jsonb_typeof(value -> 'riskEventId') = 'string'
      and value ->> 'riskEventId' ~* uuid_pattern
      and jsonb_typeof(value -> 'optionCount') = 'number'
      and value ->> 'optionCount' ~ '^[0-9]+$'
      and (value ->> 'optionCount')::integer between 1 and 10
    when 'live_recalculate' then
      public.command_jsonb_object_size(value) = 2
      and value ?& array['executionEventId', 'refreshExternalFacts']
      and jsonb_typeof(value -> 'executionEventId') = 'string'
      and value ->> 'executionEventId' ~* uuid_pattern
      and jsonb_typeof(value -> 'refreshExternalFacts') = 'boolean'
    else false
  end;
exception
  when invalid_text_representation or numeric_value_out_of_range or datetime_field_overflow then
    return false;
end;
$$;


create function public.compute_command_input_hash(
  input_run_type text,
  input_schema_version smallint,
  input_contract_version text,
  input_algorithm_version text,
  input_base_schedule_version_id uuid,
  input_structured_input jsonb
)
returns text language plpgsql immutable security invoker set search_path=''
as $$
begin
  if input_schema_version is distinct from 2
     or not coalesce(public.command_input_matches_schema(input_run_type,input_schema_version,input_structured_input),false)
     or input_contract_version is null or btrim(input_contract_version)='' or length(input_contract_version)>64
     or input_algorithm_version is null or btrim(input_algorithm_version)='' or length(input_algorithm_version)>64 then
    raise exception using errcode='23514', message='command input does not match closed schema';
  end if;
  return pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(public.canonicalize_command_jsonb(jsonb_build_object(
    'algorithmVersion',input_algorithm_version,
    'baseScheduleVersionId',input_base_schedule_version_id,
    'contractVersion',input_contract_version,
    'runType',input_run_type,
    'schemaVersion',input_schema_version,
    'structuredInput',input_structured_input
  )),'UTF8')),'hex');
end;
$$;
revoke all on function public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb)
  from public,anon,authenticated,service_role;

create or replace function public.validate_compute_run_input_hash()
returns trigger language plpgsql security definer set search_path=''
as $$
begin
  if new.command_input_hash is distinct from public.compute_command_input_hash(
    new.run_type::text,new.schema_version::smallint,new.contract_version::text,
    new.algorithm_version::text,new.base_schedule_version_id::uuid,new.structured_input::jsonb) then
    raise exception using errcode='23514', message='command input hash mismatch';
  end if;
  return new;
end;
$$;

create or replace function public.protect_compute_run_input_immutability()
returns trigger language plpgsql security definer set search_path=''
as $$
begin
  raise exception using errcode='23514', message='command input snapshot is immutable';
end;
$$;

create or replace function timing_jeju_private.reject_command_input_location()
returns trigger language plpgsql security invoker set search_path=''
as $$
begin
  if new.schema_version is distinct from 2
     or not coalesce(public.command_input_matches_schema(new.run_type,new.schema_version,new.structured_input),false) then
    raise exception using errcode='23514', message='user location storage is disabled';
  end if;
  return new;
end;
$$;


create or replace function timing_jeju_private.require_compute_input_lineage()
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
      and input.schema_version = 2
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return null;
end;
$$;

create or replace function timing_jeju_private.require_planner_input_lineage()
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
    where input.schema_version = 2
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
          and revision.algorithm_version = input.algorithm_version
          and revision.request_hash = input.command_input_hash)
      )
  ) then
    raise exception using errcode = '23514', message = 'compute input lineage required';
  end if;
  return null;
end;
$$;

create or replace function timing_jeju_private.require_mcp_command_lineage()
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
      and input.schema_version = 2
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

create or replace function timing_jeju_private.reject_execution_event_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if not coalesce(
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

create or replace function timing_jeju_private.reject_live_state_location()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.facts is distinct from '{}'::jsonb or new.next_action is not null then
    raise exception using errcode = '23514', message = 'user location storage is disabled';
  end if;
  return new;
end;
$$;


-- No independent revision request hash, MCP wire hash or replay receipt is rewritten.
-- Their unclassified preflight counters must already be zero.
alter table public.compute_run_inputs disable trigger trg_compute_run_inputs_immutable;
update public.compute_run_inputs input
set schema_version=2,
    command_input_hash=public.compute_command_input_hash(
      input.run_type::text,2::smallint,input.contract_version::text,input.algorithm_version::text,
      input.base_schedule_version_id::uuid,input.structured_input::jsonb);
update public.compute_runs run
set input_hash=input.command_input_hash
from public.compute_run_inputs input
where input.compute_run_id=run.id and run.input_hash is distinct from input.command_input_hash;
-- Flush deferred lineage events before ALTER TABLE and fail the entire transaction on mismatch.
set constraints all immediate;
alter table public.compute_run_inputs enable trigger trg_compute_run_inputs_immutable;

-- The wire input hash is used only for the MCP protocol exchange. It is not
-- reconstructible from retained facts and must not be persisted as an opaque digest.
alter table public.mcp_compute_call_logs
  drop constraint mcp_compute_call_logs_current_metadata_check,
  drop constraint mcp_compute_call_logs_mcp_hash_check,
  drop column mcp_input_hash;
alter table public.mcp_compute_call_logs
  add constraint mcp_compute_call_logs_current_metadata_check check (
    legacy_contract
    or (
      command_input_hash is not null
      and schema_checksum is not null
      and request_fact_count is not null
      and response_fact_count is not null
      and latency_ms is not null
    )
  );

-- The final audit is rewritten before removing its old column dependencies.


create or replace function timing_jeju_planner_private.user_location_residue_counts()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select 'trip_execution_events'::text, count(*) from public.trip_execution_events
  where timing_jeju_private.user_json_contains_location(metadata)
  union all
  select 'live_state_snapshots', count(*) from public.live_state_snapshots
  where facts <> '{}'::jsonb or next_action is not null
  union all
  select 'compute_run_inputs', count(*) from public.compute_run_inputs
  where schema_version<>2 or not coalesce(public.command_input_matches_schema(run_type,schema_version,structured_input),false)
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
  select 'unclassified_mcp_compute_call_logs', count(*) from public.mcp_compute_call_logs log
  where not exists (
    select 1 from public.compute_run_inputs input
    where input.command_input_hash=log.command_input_hash
      and (input.compute_run_id=log.compute_run_id
        or input.generation_run_id=log.generation_run_id
        or input.schedule_revision_run_id=log.schedule_revision_run_id)
  )
  union all
  select 'unclassified_schedule_revision_request_hashes',count(*)
  from public.schedule_revision_runs revision
  where not exists (
    select 1 from public.compute_run_inputs input
    where input.schedule_revision_run_id=revision.id
      and input.command_input_hash=revision.request_hash
  );
$$;


drop function timing_jeju_planner_private.user_location_residue_counts_v17();
drop function public.redact_due_compute_run_input_locations(timestamptz,integer);
drop function public.shorten_compute_run_input_location_expiry(uuid,timestamptz);
drop function public.compute_run_input_known_expiry(uuid,uuid,uuid,uuid,timestamptz);
drop function public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb,boolean,jsonb);

drop index public.idx_compute_run_inputs_location_due;
alter table public.compute_run_inputs drop constraint chk_compute_run_inputs_location;
alter table public.compute_run_inputs
  drop column location_supplied, drop column coarse_location, drop column location_precision_meters,
  drop column location_policy_version, drop column location_observed_at,
  drop column location_expires_at, drop column location_redacted_at;
drop index public.idx_trip_execution_events_location;
alter table public.trip_execution_events drop column location;
drop index public.idx_live_state_snapshots_place;
alter table public.live_state_snapshots drop constraint live_state_snapshots_current_place_id_fkey;
alter table public.live_state_snapshots drop column current_location, drop column current_place_id;

-- PL/pgSQL bodies are not always represented by pg_depend. Unknown references must be audited.
do $$
begin
  if exists (
    select 1 from pg_catalog.pg_proc proc join pg_catalog.pg_namespace ns on ns.oid=proc.pronamespace
    where ns.nspname in ('public','timing_jeju_private','timing_jeju_planner_private')
      and case when proc.prokind in ('f','p','w') then pg_catalog.pg_get_functiondef(proc.oid) ~*
        '\m(location_supplied|coarse_location|location_precision_meters|location_policy_version|location_observed_at|location_expires_at|location_redacted_at|current_location|current_place_id)\M'
        else false end
  ) then
    raise exception using errcode='23514', message='location runtime dependency requires audit';
  end if;
  -- PL/pgSQL has no complete column dependency catalog. Only the reviewed event
  -- functions are accepted, with exact body fingerprints. Unknown event readers
  -- require a separate audit, including readers that only join public places.
  -- Functions reading public places without event dependencies remain valid.
  if exists (
    select 1 from pg_catalog.pg_proc proc
    join pg_catalog.pg_namespace ns on ns.oid=proc.pronamespace
    where ns.nspname in ('public','timing_jeju_private','timing_jeju_planner_private')
      and proc.prokind in ('f','p','w')
      and (proc.prosrc ~* '\mtrip_execution_events\M'
        or exists (select 1 from pg_catalog.pg_trigger trg
            where trg.tgfoid=proc.oid
              and trg.tgrelid='public.trip_execution_events'::regclass))
      and not exists (
        select 1 from (values
          ('public.prevent_execution_event_mutation()',
           '3dc82d4ed28cd34ceb142f83d4119f775eccd4ea91f92baa4b7e4dc073b51d56'),
          ('timing_jeju_private.reject_execution_event_location()',
           '3f09cdc118ffca331ad8663f4a7b17495a9b8d769117c711806b61bd536c5187'),
          ('timing_jeju_planner_private.user_location_residue_counts()',
           '7fa0c803a53380d0b86d2b1f48a3b71a8d2b537904891556be54fc041edaaef0')
        ) approved(signature, body_sha256)
        where proc.oid=pg_catalog.to_regprocedure(approved.signature)
          and pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(proc.prosrc,'UTF8')),'hex')
            = approved.body_sha256
      )
  ) then
    raise exception using errcode='23514', message='event location runtime dependency requires audit';
  end if;
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count<>0) then
    raise exception using errcode='23514', message='user location residue requires audit';
  end if;
  if exists (select 1 from public.compute_run_inputs input
      where input.schema_version<>2 or input.command_input_hash is distinct from public.compute_command_input_hash(
        input.run_type::text,2::smallint,input.contract_version::text,input.algorithm_version::text,
        input.base_schedule_version_id::uuid,input.structured_input::jsonb)) then
    raise exception using errcode='23514', message='command input v2 verification failed';
  end if;
  if not exists (select 1 from pg_catalog.pg_trigger
      where tgrelid='public.compute_run_inputs'::regclass
        and tgname='trg_compute_run_inputs_immutable' and tgenabled='O') then
    raise exception using errcode='23514', message='command input immutability protection must be enabled';
  end if;
end;
$$;

create function timing_jeju_planner_private.user_location_schema_revision()
returns text language sql immutable security invoker set search_path=''
as $$ select '20260918000020'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_schema_revision()
  from public,anon,authenticated,service_role;
commit;
