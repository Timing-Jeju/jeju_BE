-- #53/#79/#54: 기존 full-day 계약을 유지하며 명시적 순차 AI 버전만 prefix coverage를 허용한다.
begin;

alter table public.trip_schedule_versions
  add column coverage_through_day_no integer,
  add constraint ck_schedule_sequential_coverage check (
    coverage_through_day_no is null or
    (coverage_through_day_no between 1 and 5 and source_type in ('ai_generation','user_edit','recovery','live_recalculation'))
  );

create or replace function public.assert_schedule_day_coverage(
  target_schedule_version_id uuid,
  target_trip_plan_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
  plan_start_date date;
  plan_end_date date;
  expected_day_count integer;
  actual_day_count integer;
  covered_day_count integer;
begin
  if not exists (
    select 1
    from public.trip_schedule_versions v
    where v.id = target_schedule_version_id
      and v.trip_plan_id = target_trip_plan_id
  ) then
    raise exception 'schedule version % does not belong to trip %',
      target_schedule_version_id, target_trip_plan_id;
  end if;

  select p.start_date, p.end_date
    into plan_start_date, plan_end_date
  from public.trip_plans p
  where p.id = target_trip_plan_id;

  if not found then
    raise exception 'trip plan % does not exist', target_trip_plan_id;
  end if;

  expected_day_count := plan_end_date - plan_start_date + 1;
  select coalesce(v.coverage_through_day_no, expected_day_count) into covered_day_count
    from public.trip_schedule_versions v where v.id=target_schedule_version_id;
  if covered_day_count > expected_day_count or exists (
    select 1 from public.trip_schedule_versions v where v.id=target_schedule_version_id
      and v.coverage_through_day_no is not null and expected_day_count > 5
  ) then
    raise exception 'invalid sequential schedule coverage' using errcode='23514';
  end if;

  select count(*)::integer
    into actual_day_count
  from public.trip_days d
  where d.trip_plan_id = target_trip_plan_id;

  if actual_day_count <> expected_day_count then
    raise exception 'trip % requires % days but has %',
      target_trip_plan_id, expected_day_count, actual_day_count;
  end if;

  if exists (
    select 1
    from generate_series(1, expected_day_count) expected(day_no)
    left join public.trip_days d
      on d.trip_plan_id = target_trip_plan_id
     and d.day_no = expected.day_no
     and d.trip_date = plan_start_date + (expected.day_no - 1)
    where d.id is null
  ) then
    raise exception 'trip % days must use contiguous numbers and dates from % through %',
      target_trip_plan_id, plan_start_date, plan_end_date;
  end if;

  if exists (
    select 1
    from public.trip_days d
    where d.trip_plan_id = target_trip_plan_id
      and d.day_no <= covered_day_count
      and not exists (
        select 1
        from public.trip_items i
        where i.trip_plan_id = target_trip_plan_id
          and i.schedule_version_id = target_schedule_version_id
          and i.trip_day_id = d.id
      )
  ) then
    raise exception 'schedule version % requires at least one item for every trip day',
      target_schedule_version_id;
  end if;
  if exists (
    select 1 from public.trip_items i join public.trip_days d
      on d.id=i.trip_day_id and d.trip_plan_id=i.trip_plan_id
    where i.schedule_version_id=target_schedule_version_id and d.day_no > covered_day_count
  ) then
    raise exception 'item exceeds sequential schedule coverage' using errcode='23514';
  end if;
end;
$$;

create function public.guard_schedule_coverage_immutable()
returns trigger language plpgsql security invoker set search_path=''
as $$
begin
  if old.status <> 'draft' and new.coverage_through_day_no is distinct from old.coverage_through_day_no then
    raise exception 'sealed schedule coverage is immutable' using errcode='23514';
  end if;
  return new;
end;
$$;
revoke all on function public.guard_schedule_coverage_immutable() from public, anon, authenticated;
grant execute on function public.guard_schedule_coverage_immutable() to service_role;
create trigger schedule_coverage_immutable before update on public.trip_schedule_versions
  for each row execute function public.guard_schedule_coverage_immutable();

comment on column public.trip_schedule_versions.coverage_through_day_no is
  'NULL은 기존 전체 여행 coverage. 양수는 Day1부터 해당 Day까지 빠짐없이 채운 순차 AI 또는 파생 편집 버전.';
commit;
