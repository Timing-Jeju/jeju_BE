-- Issue #108: retry/restart가 접수 시점의 동일한 축소 command input만 읽도록 고정한다.
-- HTTP intake, MCP call log와 due location redaction 실행은 각각 #69, #52/#109가 소유한다.

alter table public.trip_plans
  add column trip_ended_at timestamptz;

-- 배포 전에 이미 완료된 legacy 여행은 과거 종료시각을 안전하게 복원할 근거가 없으므로
-- migration DB 시각을 보수적인 one-shot anchor로 기록한다.
update public.trip_plans
set trip_ended_at = statement_timestamp()
where status = 'completed';

create function public.record_trip_ended_at()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if new.trip_ended_at is not null then
      raise exception using errcode = '23514', message = 'trip ended timestamp is database managed';
    end if;
    if new.status = 'completed' then
      new.trip_ended_at := statement_timestamp();
    end if;
    return new;
  end if;

  if old.trip_ended_at is distinct from new.trip_ended_at then
    raise exception using errcode = '23514', message = 'trip ended timestamp is immutable';
  end if;
  if old.trip_ended_at is null and old.status <> 'completed' and new.status = 'completed' then
    new.trip_ended_at := statement_timestamp();
  else
    new.trip_ended_at := old.trip_ended_at;
  end if;
  return new;
end;
$$;

create trigger trg_trip_plans_record_trip_ended_at
before insert or update on public.trip_plans
for each row execute function public.record_trip_ended_at();

create function public.canonicalize_command_jsonb(value jsonb)
returns text
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  canonical text;
begin
  case jsonb_typeof(value)
    when 'object' then
      select '{' || coalesce(
        string_agg(
          to_jsonb(property.key)::text || ':' || public.canonicalize_command_jsonb(property.value),
          ',' order by convert_to(property.key, 'UTF8')
        ),
        ''
      ) || '}'
      into canonical
      from jsonb_each(value) property;
      return canonical;
    when 'array' then
      select '[' || coalesce(
        string_agg(public.canonicalize_command_jsonb(element.value), ',' order by element.ordinality),
        ''
      ) || ']'
      into canonical
      from jsonb_array_elements(value) with ordinality element(value, ordinality);
      return canonical;
    when 'string' then
      return value::text;
    when 'number' then
      return value::text;
    when 'boolean' then
      return value::text;
    when 'null' then
      return 'null';
    else
      raise exception using errcode = '22023', message = 'unsupported command json value';
  end case;
end;
$$;

create function public.command_jsonb_object_size(value jsonb)
returns integer
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  object_size integer;
begin
  if jsonb_typeof(value) <> 'object' then
    return null;
  end if;
  select count(*)::integer into object_size
  from jsonb_object_keys(value);
  return object_size;
end;
$$;

create function public.command_input_rfc3339_timestamp_is_valid(value text)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  parts text[];
  input_year integer;
  input_month integer;
  input_day integer;
  input_hour integer;
  input_minute integer;
  input_second integer;
  offset_hour integer;
  offset_minute integer;
  validated_date date;
begin
  parts := regexp_match(
    value,
    '^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\.([0-9]{1,9}))?(Z|([+-])([0-9]{2}):([0-9]{2}))$'
  );
  if parts is null then
    return false;
  end if;
  input_year := parts[1]::integer;
  input_month := parts[2]::integer;
  input_day := parts[3]::integer;
  input_hour := parts[4]::integer;
  input_minute := parts[5]::integer;
  input_second := parts[6]::integer;
  offset_hour := coalesce(parts[11]::integer, 0);
  offset_minute := coalesce(parts[12]::integer, 0);
  if input_year not between 1 and 9999
     or input_hour not between 0 and 23
     or input_minute not between 0 and 59
     or input_second not between 0 and 59
     or offset_hour not between 0 and 18
     or offset_minute not between 0 and 59
     or (offset_hour = 18 and offset_minute <> 0) then
    return false;
  end if;
  validated_date := make_date(input_year, input_month, input_day);
  return validated_date is not null;
exception
  when invalid_text_representation or numeric_value_out_of_range or datetime_field_overflow then
    return false;
end;
$$;

create function public.command_input_rfc3339_timestamp_sort_key(value text)
returns numeric
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  parts text[];
  local_seconds numeric;
  offset_seconds integer;
begin
  if not public.command_input_rfc3339_timestamp_is_valid(value) then
    return null;
  end if;
  parts := regexp_match(
    value,
    '^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\.([0-9]{1,9}))?(Z|([+-])([0-9]{2}):([0-9]{2}))$'
  );
  local_seconds :=
      (make_date(parts[1]::integer, parts[2]::integer, parts[3]::integer)
        - make_date(1, 1, 1))::numeric * 86400
      + parts[4]::integer * 3600
      + parts[5]::integer * 60
      + parts[6]::integer
      + case when parts[8] is null then 0 else ('0.' || parts[8])::numeric end;
  offset_seconds := coalesce(parts[11]::integer, 0) * 3600 + coalesce(parts[12]::integer, 0) * 60;
  return local_seconds + case when parts[10] = '+' then -offset_seconds else offset_seconds end;
end;
$$;

create function public.command_input_matches_schema(
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
  if input_schema_version <> 1 or jsonb_typeof(value) <> 'object' then
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
  input_structured_input jsonb,
  input_location_supplied boolean,
  input_coarse_location jsonb
)
returns text
language sql
immutable
security invoker
set search_path = pg_catalog, extensions, public
as $$
  select encode(
    digest(
      convert_to(
        public.canonicalize_command_jsonb(
          jsonb_build_object(
            'algorithmVersion', input_algorithm_version,
            'baseScheduleVersionId', input_base_schedule_version_id,
            'contractVersion', input_contract_version,
            'locationDigest', case
              when input_location_supplied then encode(
                digest(
                  convert_to(public.canonicalize_command_jsonb(input_coarse_location), 'UTF8'),
                  'sha256'
                ),
                'hex'
              )
              else null
            end,
            'locationSupplied', input_location_supplied,
            'runType', input_run_type,
            'schemaVersion', input_schema_version,
            'structuredInput', input_structured_input
          )
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  )
$$;

create table public.compute_run_inputs (
  id uuid primary key default gen_random_uuid(),
  compute_run_id uuid,
  generation_run_id uuid,
  schedule_revision_run_id uuid,
  owner_user_id uuid not null,
  trip_plan_id uuid not null,
  base_schedule_version_id uuid,
  run_type varchar(32) not null,
  schema_version smallint not null,
  contract_version varchar(64) not null,
  algorithm_version varchar(64) not null,
  structured_input jsonb not null,
  command_input_hash char(64) not null,
  location_supplied boolean not null default false,
  coarse_location jsonb,
  location_precision_meters integer,
  location_policy_version varchar(64),
  location_observed_at timestamptz,
  location_expires_at timestamptz,
  location_redacted_at timestamptz,
  created_at timestamptz not null default now(),
  constraint fk_compute_run_inputs_compute_parent
    foreign key (compute_run_id)
    references public.compute_runs (id)
    on delete cascade,
  constraint fk_compute_run_inputs_generation_parent
    foreign key (generation_run_id)
    references public.itinerary_generation_runs (id)
    on delete cascade,
  constraint fk_compute_run_inputs_revision_parent
    foreign key (schedule_revision_run_id)
    references public.schedule_revision_runs (id)
    on delete cascade,
  constraint fk_compute_run_inputs_owner_trip
    foreign key (trip_plan_id, owner_user_id)
    references public.trip_plans (id, user_id)
    on delete cascade,
  constraint fk_compute_run_inputs_base_schedule
    foreign key (base_schedule_version_id, trip_plan_id)
    references public.trip_schedule_versions (id, trip_plan_id),
  constraint chk_compute_run_inputs_exact_parent
    check (num_nonnulls(compute_run_id, generation_run_id, schedule_revision_run_id) = 1),
  constraint chk_compute_run_inputs_run_type
    check (run_type in (
      'itinerary_generation', 'schedule_revision', 'itinerary_validate', 'feasibility',
      'spare_time', 'recovery', 'live_recalculate'
    )),
  constraint chk_compute_run_inputs_schema
    check (public.command_input_matches_schema(run_type, schema_version, structured_input)),
  constraint chk_compute_run_inputs_versions
    check (
      btrim(contract_version) <> '' and length(contract_version) <= 64
      and btrim(algorithm_version) <> '' and length(algorithm_version) <= 64
    ),
  constraint chk_compute_run_inputs_hash
    check (command_input_hash ~ '^[0-9a-f]{64}$'),
  constraint chk_compute_run_inputs_location
    check (
      (
        not location_supplied
        and coarse_location is null
        and location_precision_meters is null
        and location_policy_version is null
        and location_observed_at is null
        and location_expires_at is null
        and location_redacted_at is null
      )
      or
      (
        location_supplied
        and location_policy_version is not null
        and btrim(location_policy_version) <> ''
        and location_observed_at is not null
        and (
          (
            location_redacted_at is null
            and coarse_location is not null
            and jsonb_typeof(coarse_location) = 'object'
            and (
              (
                coarse_location ->> 'type' = 'GRID_100M'
                and public.command_jsonb_object_size(coarse_location) = 3
                and coarse_location ?& array['type', 'gridX', 'gridY']
                and jsonb_typeof(coarse_location -> 'gridX') = 'number'
                and jsonb_typeof(coarse_location -> 'gridY') = 'number'
                and coarse_location ->> 'gridX' ~ '^-?[0-9]+$'
                and coarse_location ->> 'gridY' ~ '^-?[0-9]+$'
                and location_precision_meters = 100
              )
              or
              (
                coarse_location ->> 'type' = 'PLACE'
                and public.command_jsonb_object_size(coarse_location) = 2
                and coarse_location ?& array['type', 'placeId']
                and coarse_location ->> 'placeId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                and location_precision_meters is null
              )
              or
              (
                coarse_location ->> 'type' = 'STOP'
                and public.command_jsonb_object_size(coarse_location) = 2
                and coarse_location ?& array['type', 'stopId']
                and coarse_location ->> 'stopId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                and location_precision_meters is null
              )
            )
          )
          or
          (
            location_redacted_at is not null
            and coarse_location is null
            and location_precision_meters is null
          )
        )
      )
    )
);

create unique index uq_compute_run_inputs_compute_parent
  on public.compute_run_inputs (compute_run_id)
  where compute_run_id is not null;

create unique index uq_compute_run_inputs_generation_parent
  on public.compute_run_inputs (generation_run_id)
  where generation_run_id is not null;

create unique index uq_compute_run_inputs_revision_parent
  on public.compute_run_inputs (schedule_revision_run_id)
  where schedule_revision_run_id is not null;

create index idx_compute_run_inputs_owner_trip
  on public.compute_run_inputs (trip_plan_id, owner_user_id);

create index idx_compute_run_inputs_base_schedule
  on public.compute_run_inputs (base_schedule_version_id, trip_plan_id)
  where base_schedule_version_id is not null;

create index idx_compute_run_inputs_location_due
  on public.compute_run_inputs (location_expires_at, id)
  where location_supplied and location_redacted_at is null;

-- #109 due selection contract: location_expires_at <= evaluated_at (equality is due).

create function public.validate_compute_run_input_parent()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  parent_trip_plan_id uuid;
  parent_base_schedule_version_id uuid;
  parent_owner_user_id uuid;
  parent_run_type text;
begin
  if new.compute_run_id is not null then
    select run.trip_plan_id, run.schedule_version_id, plan.user_id, run.run_type
      into parent_trip_plan_id, parent_base_schedule_version_id, parent_owner_user_id, parent_run_type
    from public.compute_runs run
    join public.trip_plans plan on plan.id = run.trip_plan_id
    where run.id = new.compute_run_id;
  elsif new.generation_run_id is not null then
    select run.trip_plan_id, run.base_schedule_version_id, run.requested_by_user_id, 'itinerary_generation'
      into parent_trip_plan_id, parent_base_schedule_version_id, parent_owner_user_id, parent_run_type
    from public.itinerary_generation_runs run
    join public.trip_plans plan on plan.id = run.trip_plan_id
    where run.id = new.generation_run_id;
  else
    select run.trip_plan_id, run.base_schedule_version_id, run.owner_user_id, 'schedule_revision'
      into parent_trip_plan_id, parent_base_schedule_version_id, parent_owner_user_id, parent_run_type
    from public.schedule_revision_runs run
    where run.id = new.schedule_revision_run_id;
  end if;

  if not found
     or parent_trip_plan_id is distinct from new.trip_plan_id
     or parent_base_schedule_version_id is distinct from new.base_schedule_version_id
     or parent_owner_user_id is distinct from new.owner_user_id
     or parent_run_type is distinct from new.run_type then
    raise exception using errcode = '23514', message = 'command input parent lineage or run type mismatch';
  end if;
  return new;
end;
$$;

create function public.compute_run_input_known_expiry(
  input_compute_run_id uuid,
  input_generation_run_id uuid,
  input_schedule_revision_run_id uuid,
  input_trip_plan_id uuid,
  evaluated_at timestamptz
)
returns timestamptz
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  terminal_at timestamptz;
  trip_ended_at timestamptz;
begin
  if evaluated_at is null then
    raise exception using errcode = '22004', message = 'expiry evaluation time is required';
  end if;
  if input_compute_run_id is not null then
    select completed_at into terminal_at
    from public.compute_runs
    where id = input_compute_run_id and status in ('succeeded', 'failed', 'cancelled');
  elsif input_generation_run_id is not null then
    select completed_at into terminal_at
    from public.itinerary_generation_runs
    where id = input_generation_run_id and status in ('succeeded', 'failed', 'cancelled');
  elsif input_schedule_revision_run_id is not null then
    select completed_at into terminal_at
    from public.schedule_revision_runs
    where id = input_schedule_revision_run_id and status in ('succeeded', 'failed', 'cancelled');
  end if;

  select plan.trip_ended_at into trip_ended_at
  from public.trip_plans plan
  where plan.id = input_trip_plan_id;

  return (
    select min(anchor_at + interval '24 hours')
    from unnest(array[terminal_at, trip_ended_at]) anchor(anchor_at)
    where anchor_at is not null and anchor_at <= evaluated_at
  );
end;
$$;

create function public.validate_compute_run_input_hash()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.location_redacted_at is not null then
    raise exception using errcode = '23514', message = 'command input location must be created unredacted';
  end if;
  if new.command_input_hash is distinct from public.compute_command_input_hash(
    new.run_type,
    new.schema_version,
    new.contract_version,
    new.algorithm_version,
    new.base_schedule_version_id,
    new.structured_input,
    new.location_supplied,
    new.coarse_location
  ) then
    raise exception using errcode = '23514', message = 'command input hash mismatch';
  end if;
  if not public.command_input_matches_schema(new.run_type, new.schema_version, new.structured_input) then
    raise exception using errcode = '23514', message = 'command input does not match closed schema';
  end if;
  if new.location_supplied and new.location_expires_at is distinct from public.compute_run_input_known_expiry(
    new.compute_run_id,
    new.generation_run_id,
    new.schedule_revision_run_id,
    new.trip_plan_id,
    new.created_at
  ) then
    raise exception using errcode = '23514', message = 'command input initial expiry does not match arrived anchors';
  end if;
  return new;
end;
$$;

create function public.protect_compute_run_input_immutability()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if old.id is distinct from new.id
     or old.compute_run_id is distinct from new.compute_run_id
     or old.generation_run_id is distinct from new.generation_run_id
     or old.schedule_revision_run_id is distinct from new.schedule_revision_run_id
     or old.owner_user_id is distinct from new.owner_user_id
     or old.trip_plan_id is distinct from new.trip_plan_id
     or old.base_schedule_version_id is distinct from new.base_schedule_version_id
     or old.run_type is distinct from new.run_type
     or old.schema_version is distinct from new.schema_version
     or old.contract_version is distinct from new.contract_version
     or old.algorithm_version is distinct from new.algorithm_version
     or old.structured_input is distinct from new.structured_input
     or old.command_input_hash is distinct from new.command_input_hash
     or old.location_supplied is distinct from new.location_supplied
     or old.coarse_location is distinct from new.coarse_location
     or old.location_precision_meters is distinct from new.location_precision_meters
     or old.location_policy_version is distinct from new.location_policy_version
     or old.location_observed_at is distinct from new.location_observed_at
     or old.location_redacted_at is distinct from new.location_redacted_at
     or old.created_at is distinct from new.created_at then
    raise exception using errcode = '23514', message = 'command input snapshot is immutable';
  end if;
  if not new.location_supplied
     or new.location_expires_at is null
     or (
       old.location_expires_at is not null
       and new.location_expires_at > old.location_expires_at
     ) then
    raise exception using errcode = '23514', message = 'command input expiry may only shorten';
  end if;
  return new;
end;
$$;

create trigger trg_compute_run_inputs_validate_parent
before insert on public.compute_run_inputs
for each row execute function public.validate_compute_run_input_parent();

create trigger trg_compute_run_inputs_validate_hash
before insert on public.compute_run_inputs
for each row execute function public.validate_compute_run_input_hash();

create trigger trg_compute_run_inputs_immutable
before update on public.compute_run_inputs
for each row execute function public.protect_compute_run_input_immutability();

create function public.shorten_compute_run_input_location_expiry(
  input_id uuid,
  evaluated_at timestamptz
)
returns timestamptz
language plpgsql
security definer
set search_path = ''
as $$
declare
  input_row public.compute_run_inputs%rowtype;
  candidate_expiry timestamptz;
  resulting_expiry timestamptz;
begin
  if evaluated_at is null then
    raise exception using errcode = '22004', message = 'expiry evaluation time is required';
  end if;
  select * into input_row
  from public.compute_run_inputs
  where id = input_id
  for update;
  if not found or not input_row.location_supplied or input_row.location_redacted_at is not null then
    raise exception using errcode = '22023', message = 'location-bearing command input is required';
  end if;

  candidate_expiry := public.compute_run_input_known_expiry(
    input_row.compute_run_id,
    input_row.generation_run_id,
    input_row.schedule_revision_run_id,
    input_row.trip_plan_id,
    evaluated_at
  );
  if candidate_expiry is null then
    return input_row.location_expires_at;
  end if;

  resulting_expiry := case
    when input_row.location_expires_at is null then candidate_expiry
    else least(input_row.location_expires_at, candidate_expiry)
  end;
  update public.compute_run_inputs
  set location_expires_at = resulting_expiry
  where id = input_id;
  return resulting_expiry;
end;
$$;

alter table public.compute_run_inputs enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.compute_run_inputs from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.compute_run_inputs from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'grant select, insert on public.compute_run_inputs to service_role';
    execute 'revoke update on public.compute_run_inputs from service_role';
    execute 'grant execute on function public.command_jsonb_object_size(jsonb) to service_role';
    execute 'grant execute on function public.command_input_rfc3339_timestamp_is_valid(text) to service_role';
    execute 'grant execute on function public.command_input_rfc3339_timestamp_sort_key(text) to service_role';
    execute 'grant execute on function public.command_input_matches_schema(text, smallint, jsonb) to service_role';
    execute 'grant execute on function public.shorten_compute_run_input_location_expiry(uuid, timestamptz) to service_role';
  end if;
end
$$;

revoke all on function public.canonicalize_command_jsonb(jsonb) from public;
revoke all on function public.record_trip_ended_at() from public;
revoke all on function public.command_jsonb_object_size(jsonb) from public;
revoke all on function public.command_input_rfc3339_timestamp_is_valid(text) from public;
revoke all on function public.command_input_rfc3339_timestamp_sort_key(text) from public;
revoke all on function public.command_input_matches_schema(text, smallint, jsonb) from public;
revoke all on function public.compute_command_input_hash(text, smallint, text, text, uuid, jsonb, boolean, jsonb) from public;
revoke all on function public.validate_compute_run_input_parent() from public;
revoke all on function public.compute_run_input_known_expiry(uuid, uuid, uuid, uuid, timestamptz) from public;
revoke all on function public.validate_compute_run_input_hash() from public;
revoke all on function public.protect_compute_run_input_immutability() from public;
revoke all on function public.shorten_compute_run_input_location_expiry(uuid, timestamptz) from public;
