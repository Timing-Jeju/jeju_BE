-- Issue #109: command input의 due 축소 위치만 제한 함수로 원자 정리한다.
-- 기존 #108 snapshot migration은 공유 이력이므로 수정하지 않는다.

alter table public.compute_run_inputs
  drop constraint chk_compute_run_inputs_location;

alter table public.compute_run_inputs
  add constraint chk_compute_run_inputs_location
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
      and (
        (
          location_redacted_at is null
          and location_policy_version is not null
          and btrim(location_policy_version) <> ''
          and location_observed_at is not null
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
          and location_policy_version is null
          and location_observed_at is null
          and location_expires_at is null
        )
      )
    )
  );

create or replace function public.protect_compute_run_input_immutability()
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
     or old.created_at is distinct from new.created_at then
    raise exception using errcode = '23514', message = 'command input snapshot is immutable';
  end if;

  if old.location_redacted_at is null
     and new.location_redacted_at is not null
     and old.location_supplied
     and old.coarse_location is not null
     and new.coarse_location is null
     and new.location_precision_meters is null
     and new.location_policy_version is null
     and new.location_observed_at is null
     and new.location_expires_at is null then
    return new;
  end if;

  if old.location_redacted_at is distinct from new.location_redacted_at
     or old.coarse_location is distinct from new.coarse_location
     or old.location_precision_meters is distinct from new.location_precision_meters
     or old.location_policy_version is distinct from new.location_policy_version
     or old.location_observed_at is distinct from new.location_observed_at then
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

create function public.redact_due_compute_run_input_locations(
  evaluated_at timestamptz,
  batch_size integer
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  redacted_count integer;
begin
  if evaluated_at is null then
    raise exception using errcode = '22004', message = 'cleanup evaluation time is required';
  end if;
  if batch_size is null or batch_size < 1 or batch_size > 500 then
    raise exception using errcode = '22023', message = 'cleanup batch size must be between 1 and 500';
  end if;

  with candidates as materialized (
    select id
    from public.compute_run_inputs
    where location_supplied
      and location_redacted_at is null
      and location_expires_at is not null
      and location_expires_at <= evaluated_at
    order by location_expires_at, id
    limit batch_size
    for update skip locked
  )
  update public.compute_run_inputs input
  set coarse_location = null,
      location_precision_meters = null,
      location_policy_version = null,
      location_observed_at = null,
      location_expires_at = null,
      location_redacted_at = evaluated_at
  from candidates
  where input.id = candidates.id;

  get diagnostics redacted_count = row_count;
  return redacted_count;
end;
$$;

revoke all on function public.redact_due_compute_run_input_locations(timestamptz, integer) from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on function public.redact_due_compute_run_input_locations(timestamptz, integer) from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on function public.redact_due_compute_run_input_locations(timestamptz, integer) from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke update on public.compute_run_inputs from service_role';
    execute 'grant execute on function public.redact_due_compute_run_input_locations(timestamptz, integer) to service_role';
  end if;
end
$$;
