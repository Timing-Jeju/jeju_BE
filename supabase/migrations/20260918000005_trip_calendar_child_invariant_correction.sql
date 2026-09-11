-- Issue #48 corrective: every calendar child must remain valid when the trip root changes.
create or replace function public.protect_trip_date_range()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.start_date is not distinct from old.start_date
     and new.end_date is not distinct from old.end_date
     and new.timezone is not distinct from old.timezone then
    return new;
  end if;

  if exists (
    select 1
    from public.trip_schedule_versions version
    where version.trip_plan_id = new.id
  ) then
    raise exception 'trip date range cannot change after a schedule version exists';
  end if;

  if exists (
    select 1
    from public.trip_transport_events event
    where event.trip_plan_id = new.id
      and (
        (event.event_type = 'arrival'
          and timezone('Asia/Seoul', event.scheduled_at)::date <> new.start_date)
        or (event.event_type = 'departure'
          and timezone('Asia/Seoul', event.scheduled_at)::date <> new.end_date)
      )
  ) or exists (
    select 1
    from public.trip_accommodations accommodation
    where accommodation.trip_plan_id = new.id
      and (
        accommodation.check_in_date < new.start_date
        or accommodation.check_out_date > new.end_date
      )
  ) or exists (
    select 1
    from public.trip_place_preferences preference
    where preference.trip_plan_id = new.id
      and preference.target_day_no is not null
      and preference.target_day_no > (new.end_date - new.start_date + 1)
  ) then
    raise exception using
      errcode = '23514',
      message = 'trip calendar excludes a child from the requested range',
      constraint = 'ck_trip_calendar_children_match_root';
  end if;

  return new;
end;
$$;

revoke all on function public.protect_trip_date_range() from public;

drop trigger if exists trg_trip_place_preference_calendar_change
  on public.trip_plans;
drop trigger if exists trg_trip_plans_protect_date_range
  on public.trip_plans;
create trigger trg_trip_plans_protect_date_range
before update of start_date, end_date, timezone
on public.trip_plans
for each row execute function public.protect_trip_date_range();

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on function public.protect_trip_date_range() from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on function public.protect_trip_date_range() from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on function public.protect_trip_date_range() from service_role';
  end if;
end;
$$;
