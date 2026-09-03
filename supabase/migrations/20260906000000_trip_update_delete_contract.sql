-- Issue #45: trip aggregate revision, PATCH CAS, and DELETE contract.
alter table public.trip_plans
  add column revision bigint;

update public.trip_plans set revision = 1 where revision is null;

alter table public.trip_plans
  alter column revision set default 1,
  alter column revision set not null,
  add constraint trip_plans_revision_positive check (revision > 0);

comment on column public.trip_plans.revision is
  'strong ETag와 compare-and-set 수정에 사용하는 단조 증가 aggregate revision';

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
    where version.trip_plan_id = old.id
  ) then
    raise exception 'trip date range cannot change after a schedule version exists';
  end if;

  if exists (
    select 1
    from public.trip_transport_events event
    where event.trip_plan_id = old.id
      and timezone('Asia/Seoul', event.scheduled_at)::date
          not between new.start_date and new.end_date
  ) then
    raise exception 'trip transport event is outside the requested date range';
  end if;

  if exists (
    select 1
    from public.trip_accommodations accommodation
    where accommodation.trip_plan_id = old.id
      and (
        accommodation.check_in_date < new.start_date
        or accommodation.check_out_date > new.end_date
      )
  ) then
    raise exception 'trip accommodation is outside the requested date range';
  end if;

  return new;
end;
$$;

create or replace function public.protect_sealed_trip_plan_dates()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.start_date is not distinct from old.start_date
     and new.end_date is not distinct from old.end_date then
    return new;
  end if;

  if exists (
    select 1
    from public.trip_schedule_versions version
    where version.trip_plan_id = old.id
  ) then
    raise exception 'trip dates cannot change after a schedule version exists';
  end if;

  return new;
end;
$$;
