begin;

-- Fail closed before installing any new object. Existing rows must already satisfy
-- the same closed preference and aggregate-mode contract used by the replace API.
do $$
declare
  conflicting_trip_plan_id uuid;
begin
  select preference.trip_plan_id
  into conflicting_trip_plan_id
  from public.trip_preferences preference
  where preference.preferred_categories is null
     or coalesce(array_ndims(preference.preferred_categories), 1) <> 1
     or cardinality(preference.preferred_categories) > 8
     or array_position(preference.preferred_categories, null) is not null
     or exists (
       select 1
       from unnest(preference.preferred_categories) category
       where category not in (
         'tourist_attraction', 'cultural_facility', 'festival', 'travel_course',
         'leisure', 'restaurant', 'cafe', 'shopping'
       )
     )
     or cardinality(preference.preferred_categories) <> (
       select count(distinct category)
       from unnest(preference.preferred_categories) category
     )
     or preference.arrival_region_code is null
     or preference.arrival_region_code
          <> btrim(preference.arrival_region_code, E' \t\n\r\f\013')
     or preference.arrival_region_code <> normalize(preference.arrival_region_code, NFC)
     or char_length(preference.arrival_region_code) not between 1 and 50
     or preference.departure_region_code is null
     or preference.departure_region_code
          <> btrim(preference.departure_region_code, E' \t\n\r\f\013')
     or preference.departure_region_code <> normalize(preference.departure_region_code, NFC)
     or char_length(preference.departure_region_code) not between 1 and 50
     or preference.preferred_region_codes is null
     or coalesce(array_ndims(preference.preferred_region_codes), 1) <> 1
     or cardinality(preference.preferred_region_codes) > 20
     or array_position(preference.preferred_region_codes, null) is not null
     or exists (
       select 1
       from generate_subscripts(preference.preferred_region_codes, 1) region_index
       where preference.preferred_region_codes[region_index]
               <> btrim(
                    preference.preferred_region_codes[region_index],
                    E' \t\n\r\f\013'
                  )
          or preference.preferred_region_codes[region_index]
               <> normalize(preference.preferred_region_codes[region_index], NFC)
          or char_length(preference.preferred_region_codes[region_index]) not between 1 and 50
     )
     or cardinality(preference.preferred_region_codes) <> (
       select count(distinct region_code)
       from unnest(preference.preferred_region_codes) region_code
     )
  order by preference.trip_plan_id
  limit 1;

  if conflicting_trip_plan_id is not null then
    raise exception 'legacy trip preference failed replace-contract audit: trip_plan_id=%',
      conflicting_trip_plan_id
      using errcode = '23514',
            constraint = 'trip_preferences_replace_contract_check';
  end if;

  with aggregate_state as (
    select affected.trip_plan_id,
           (select count(*)
            from public.trip_preferences preference
            where preference.trip_plan_id = affected.trip_plan_id) as preference_count,
           (select count(*)
            from public.trip_transport_modes mode
            where mode.trip_plan_id = affected.trip_plan_id) as mode_count,
           (select count(*)
            from public.trip_transport_modes mode
            where mode.trip_plan_id = affected.trip_plan_id
              and mode.transport_mode not in ('public_transit', 'rental_car', 'taxi'))
             as invalid_mode_count,
           (select count(*)
            from public.trip_transport_modes mode
            where mode.trip_plan_id = affected.trip_plan_id
              and mode.is_primary) as primary_count,
           (select min(mode.priority)
            from public.trip_transport_modes mode
            where mode.trip_plan_id = affected.trip_plan_id) as min_priority,
           (select max(mode.priority)
            from public.trip_transport_modes mode
            where mode.trip_plan_id = affected.trip_plan_id) as max_priority,
           (select min(mode.priority)
            from public.trip_transport_modes mode
            where mode.trip_plan_id = affected.trip_plan_id
              and mode.is_primary) as primary_priority
    from (
      select preference.trip_plan_id from public.trip_preferences preference
      union
      select mode.trip_plan_id from public.trip_transport_modes mode
    ) affected
  )
  select aggregate_state.trip_plan_id
  into conflicting_trip_plan_id
  from aggregate_state
  where (aggregate_state.mode_count = 0 and aggregate_state.preference_count <> 0)
     or (
       aggregate_state.mode_count > 0
       and (
         aggregate_state.mode_count not between 1 and 3
         or aggregate_state.invalid_mode_count <> 0
         or aggregate_state.min_priority <> 1
         or aggregate_state.max_priority <> aggregate_state.mode_count
         or aggregate_state.primary_count <> 1
         or aggregate_state.primary_priority <> 1
       )
     )
  order by aggregate_state.trip_plan_id
  limit 1;

  if conflicting_trip_plan_id is not null then
    raise exception 'legacy trip transport modes failed aggregate audit: trip_plan_id=%',
      conflicting_trip_plan_id
      using errcode = '23514',
            constraint = 'trip_transport_modes_aggregate_check';
  end if;
end;
$$;

drop trigger if exists trg_trip_preferences_transport_mode_aggregate
  on public.trip_preferences;
drop trigger if exists trg_trip_transport_modes_aggregate
  on public.trip_transport_modes;
drop function if exists public.validate_trip_transport_mode_set();

create or replace function public.trip_preference_ascii_trim(value text)
returns text
language sql
immutable
strict
parallel safe
security invoker
set search_path = pg_catalog, public
as $$
  select btrim(value, E' \t\n\r\f\013');
$$;

create or replace function public.trip_preference_categories_valid(categories text[])
returns boolean
language sql
immutable
strict
security invoker
set search_path = pg_catalog, public
as $$
  select coalesce(array_ndims(categories), 1) = 1
     and cardinality(categories) <= 8
     and array_position(categories, null) is null
     and not exists (
       select 1
       from unnest(categories) category
       where category not in (
         'tourist_attraction', 'cultural_facility', 'festival', 'travel_course',
         'leisure', 'restaurant', 'cafe', 'shopping'
       )
     )
     and cardinality(categories) = (
       select count(distinct category)
       from unnest(categories) category
     );
$$;

create or replace function public.trip_preference_regions_valid(region_codes text[])
returns boolean
language sql
immutable
strict
security invoker
set search_path = pg_catalog, public
as $$
  select coalesce(array_ndims(region_codes), 1) = 1
     and cardinality(region_codes) <= 20
     and array_position(region_codes, null) is null
     and not exists (
       select 1
       from unnest(region_codes) region_code
       where region_code <> public.trip_preference_ascii_trim(region_code)
          or region_code <> normalize(region_code, NFC)
          or char_length(region_code) not between 1 and 50
     )
     and cardinality(region_codes) = (
       select count(distinct region_code)
       from unnest(region_codes) region_code
     );
$$;

alter table public.trip_preferences
  drop constraint if exists ck_trip_preferences_categories_valid,
  drop constraint if exists ck_trip_preferences_arrival_region_valid,
  drop constraint if exists ck_trip_preferences_departure_region_valid,
  drop constraint if exists ck_trip_preferences_regions_valid;

alter table public.trip_preferences
  alter column arrival_region_code set not null,
  alter column departure_region_code set not null,
  add constraint ck_trip_preferences_categories_valid
    check (public.trip_preference_categories_valid(preferred_categories)),
  add constraint ck_trip_preferences_arrival_region_valid
    check (
      arrival_region_code = public.trip_preference_ascii_trim(arrival_region_code)
      and arrival_region_code = normalize(arrival_region_code, NFC)
      and char_length(arrival_region_code) between 1 and 50
    ),
  add constraint ck_trip_preferences_departure_region_valid
    check (
      departure_region_code = public.trip_preference_ascii_trim(departure_region_code)
      and departure_region_code = normalize(departure_region_code, NFC)
      and char_length(departure_region_code) between 1 and 50
    ),
  add constraint ck_trip_preferences_regions_valid
    check (public.trip_preference_regions_valid(preferred_region_codes));

create or replace function public.validate_trip_transport_mode_set()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  target_trip_plan_id uuid;
  preference_count bigint;
  mode_count bigint;
  primary_count bigint;
  min_priority smallint;
  max_priority smallint;
  primary_priority smallint;
begin
  if tg_op in ('UPDATE', 'DELETE') then
    target_trip_plan_id := old.trip_plan_id;

    select
      (select count(*) from public.trip_preferences preference
       where preference.trip_plan_id = target_trip_plan_id),
      count(*),
      count(*) filter (where mode.is_primary),
      min(mode.priority),
      max(mode.priority),
      min(mode.priority) filter (where mode.is_primary)
    into preference_count, mode_count, primary_count,
         min_priority, max_priority, primary_priority
    from public.trip_transport_modes mode
    where mode.trip_plan_id = target_trip_plan_id;

    if not (
      (preference_count = 0 and mode_count = 0)
      or (
        mode_count between 1 and 3
        and min_priority = 1
        and max_priority = mode_count
        and primary_count = 1
        and primary_priority = 1
      )
    ) then
      raise exception 'trip transport modes violate aggregate contract: trip_plan_id=%',
        target_trip_plan_id
        using errcode = '23514',
              constraint = 'trip_transport_modes_aggregate_check';
    end if;
  end if;

  if tg_op in ('INSERT', 'UPDATE')
     and (tg_op <> 'UPDATE' or new.trip_plan_id is distinct from old.trip_plan_id) then
    target_trip_plan_id := new.trip_plan_id;

    select
      (select count(*) from public.trip_preferences preference
       where preference.trip_plan_id = target_trip_plan_id),
      count(*),
      count(*) filter (where mode.is_primary),
      min(mode.priority),
      max(mode.priority),
      min(mode.priority) filter (where mode.is_primary)
    into preference_count, mode_count, primary_count,
         min_priority, max_priority, primary_priority
    from public.trip_transport_modes mode
    where mode.trip_plan_id = target_trip_plan_id;

    if not (
      (preference_count = 0 and mode_count = 0)
      or (
        mode_count between 1 and 3
        and min_priority = 1
        and max_priority = mode_count
        and primary_count = 1
        and primary_priority = 1
      )
    ) then
      raise exception 'trip transport modes violate aggregate contract: trip_plan_id=%',
        target_trip_plan_id
        using errcode = '23514',
              constraint = 'trip_transport_modes_aggregate_check';
    end if;
  end if;

  return null;
end;
$$;

create constraint trigger trg_trip_preferences_transport_mode_aggregate
after insert or update or delete on public.trip_preferences
deferrable initially deferred
for each row execute function public.validate_trip_transport_mode_set();

create constraint trigger trg_trip_transport_modes_aggregate
after insert or update or delete on public.trip_transport_modes
deferrable initially deferred
for each row execute function public.validate_trip_transport_mode_set();

alter table public.trip_preferences enable row level security;
alter table public.trip_transport_modes enable row level security;

revoke all on table public.trip_preferences from anon, authenticated;
revoke all on table public.trip_transport_modes from anon, authenticated;
grant select on public.trip_preferences to authenticated;
grant select on public.trip_transport_modes to authenticated;

revoke all on table public.trip_preferences from service_role;
revoke all on table public.trip_transport_modes from service_role;
grant select, insert, update, delete on table public.trip_preferences to service_role;
grant select, insert, update, delete on table public.trip_transport_modes to service_role;

revoke execute on function public.trip_preference_categories_valid(text[])
  from public, anon, authenticated;
revoke execute on function public.trip_preference_regions_valid(text[])
  from public, anon, authenticated;
revoke execute on function public.trip_preference_ascii_trim(text)
  from public, anon, authenticated;
revoke execute on function public.validate_trip_transport_mode_set()
  from public, anon, authenticated;
revoke execute on function public.trip_preference_categories_valid(text[]) from service_role;
revoke execute on function public.trip_preference_regions_valid(text[]) from service_role;
revoke execute on function public.trip_preference_ascii_trim(text) from service_role;
revoke execute on function public.validate_trip_transport_mode_set() from service_role;
grant execute on function public.trip_preference_categories_valid(text[]) to service_role;
grant execute on function public.trip_preference_regions_valid(text[]) to service_role;
grant execute on function public.trip_preference_ascii_trim(text) to service_role;

commit;
