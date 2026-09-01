-- Issue #48: 여행 희망·회피 장소의 단일 role, Day 범위와 server-writer 경계를 강제한다.

do $$
begin
  if exists (
    select 1
    from public.trip_place_preferences preference
    join public.trip_plans trip on trip.id = preference.trip_plan_id
    where preference.priority not between 0 and 100
       or (
         preference.target_day_no is not null
         and preference.target_day_no > (trip.end_date - trip.start_date + 1)
       )
  ) or exists (
    select 1
    from public.trip_place_preferences
    group by trip_plan_id, place_id
    having count(*) > 1
  ) then
    raise exception 'legacy trip place preference contract conflict';
  end if;
end;
$$;

alter table public.trip_place_preferences
  drop constraint trip_place_preferences_pkey;

alter table public.trip_place_preferences
  add primary key (trip_plan_id, place_id),
  add constraint ck_trip_place_preferences_priority_range
    check (priority between 0 and 100);

create or replace function public.validate_trip_place_preference_contract()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  trip_start_date date;
  trip_end_date date;
begin
  if tg_op = 'UPDATE' and old.trip_plan_id is distinct from new.trip_plan_id then
    if old.trip_plan_id < new.trip_plan_id then
      perform public.lock_trip_plan_schedule_mutex(old.trip_plan_id);
      perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);
    else
      perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);
      perform public.lock_trip_plan_schedule_mutex(old.trip_plan_id);
    end if;
  else
    perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);
  end if;

  select trip.start_date, trip.end_date
    into trip_start_date, trip_end_date
  from public.trip_plans trip
  where trip.id = new.trip_plan_id;

  if not found then
    raise exception 'trip plan % does not exist', new.trip_plan_id;
  end if;

  if new.target_day_no is not null
     and new.target_day_no > (trip_end_date - trip_start_date + 1) then
    raise exception 'trip place preference target day is outside trip calendar';
  end if;

  return new;
end;
$$;

create trigger trg_trip_place_preference_contract
before insert or update of trip_plan_id, target_day_no
on public.trip_place_preferences
for each row execute function public.validate_trip_place_preference_contract();

create or replace function public.validate_trip_place_preference_calendar_change()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if exists (
    select 1
    from public.trip_place_preferences preference
    where preference.trip_plan_id = new.id
      and preference.target_day_no is not null
      and preference.target_day_no > (new.end_date - new.start_date + 1)
  ) then
    raise exception 'trip calendar excludes a place preference target day';
  end if;

  return new;
end;
$$;

create trigger trg_trip_place_preference_calendar_change
before update of start_date, end_date
on public.trip_plans
for each row
when (
  old.start_date is distinct from new.start_date
  or old.end_date is distinct from new.end_date
)
execute function public.validate_trip_place_preference_calendar_change();

revoke all on table public.trip_place_preferences from anon, authenticated;
grant select, insert, update, delete on table public.trip_place_preferences to service_role;
revoke truncate on table public.trip_place_preferences from service_role;
