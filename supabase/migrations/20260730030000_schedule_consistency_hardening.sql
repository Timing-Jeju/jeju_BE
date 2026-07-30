-- Issue #11: 확정 일정과 날짜 기반 계산 결과의 교차 행 무결성을 강화한다.

create or replace function public.validate_schedule_version_base_lineage()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  parent_version_no integer;
begin
  if tg_op = 'UPDATE'
     and new.version_no is distinct from old.version_no then
    raise exception 'schedule version number is immutable';
  end if;

  if new.base_schedule_version_id is null then
    return new;
  end if;

  if new.base_schedule_version_id = new.id then
    -- 기존 CHECK 제약이 23514로 일관되게 거부하도록 맡긴다.
    return new;
  end if;

  select parent.version_no
    into parent_version_no
  from public.trip_schedule_versions parent
  where parent.id = new.base_schedule_version_id
    and parent.trip_plan_id = new.trip_plan_id
  for share;

  if not found then
    raise exception 'base schedule version % does not belong to trip %',
      new.base_schedule_version_id, new.trip_plan_id;
  end if;

  if parent_version_no >= new.version_no then
    raise exception 'base schedule version must have an earlier version number';
  end if;

  return new;
end;
$$;

create trigger trg_schedule_version_base_lineage
before insert or update of base_schedule_version_id, version_no, trip_plan_id
on public.trip_schedule_versions
for each row execute function public.validate_schedule_version_base_lineage();

create or replace function public.protect_sealed_schedule_day()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  target_day_id uuid;
  target_trip_plan_id uuid;
begin
  if tg_op = 'INSERT' then
    target_day_id := new.id;
    target_trip_plan_id := new.trip_plan_id;
  else
    target_day_id := old.id;
    target_trip_plan_id := old.trip_plan_id;
  end if;

  if tg_op = 'UPDATE'
     and new.day_no is not distinct from old.day_no
     and new.trip_date is not distinct from old.trip_date
     and new.start_time is not distinct from old.start_time
     and new.end_time is not distinct from old.end_time then
    return new;
  end if;

  if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
    return old;
  end if;

  perform version.id
  from public.trip_schedule_versions version
  where version.trip_plan_id = target_trip_plan_id
  order by version.id
  for share;

  if tg_op = 'INSERT'
     and exists (
       select 1
       from public.trip_schedule_versions version
       where version.trip_plan_id = target_trip_plan_id
         and version.status in ('candidate', 'active')
     ) then
    raise exception 'trip day cannot be added after a candidate or active schedule exists';
  end if;

  if exists (
    select 1
    from public.trip_schedule_versions version
    join public.trip_items item
      on item.schedule_version_id = version.id
     and item.trip_plan_id = version.trip_plan_id
    where version.trip_plan_id = target_trip_plan_id
      and version.status in ('candidate', 'active')
      and item.trip_day_id = target_day_id
  ) then
    raise exception 'trip day cannot change while a candidate or active schedule uses it';
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create trigger trg_protect_sealed_schedule_day
before insert or update of day_no, trip_date, start_time, end_time or delete
on public.trip_days
for each row execute function public.protect_sealed_schedule_day();

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

  perform version.id
  from public.trip_schedule_versions version
  where version.trip_plan_id = old.id
  order by version.id
  for share;

  if exists (
    select 1
    from public.trip_schedule_versions version
    where version.trip_plan_id = old.id
      and version.status in ('candidate', 'active')
  ) then
    raise exception 'trip dates cannot change after a candidate or active schedule exists';
  end if;

  return new;
end;
$$;

create trigger trg_protect_sealed_trip_plan_dates
before update of start_date, end_date
on public.trip_plans
for each row execute function public.protect_sealed_trip_plan_dates();

create function public.assert_schedule_day_item_windows(
  target_schedule_version_id uuid,
  target_trip_plan_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if exists (
    select 1
    from public.trip_items item
    join public.trip_days day
      on day.id = item.trip_day_id
     and day.trip_plan_id = item.trip_plan_id
    where item.schedule_version_id = target_schedule_version_id
      and item.trip_plan_id = target_trip_plan_id
      and (
        timezone('Asia/Seoul', item.planned_start_at)::date <> day.trip_date
        or timezone('Asia/Seoul', item.planned_end_at)::date <> day.trip_date
        or (
          day.start_time is not null
          and timezone('Asia/Seoul', item.planned_start_at)::time < day.start_time
        )
        or (
          day.end_time is not null
          and timezone('Asia/Seoul', item.planned_end_at)::date = day.trip_date
          and timezone('Asia/Seoul', item.planned_end_at)::time > day.end_time
        )
      )
  ) then
    raise exception 'sealed schedule items must fit their trip day date and time window';
  end if;
end;
$$;

create or replace function public.validate_schedule_version_sealing()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status in ('candidate', 'active')
     and (tg_op = 'INSERT' or old.status is distinct from new.status) then
    perform public.assert_schedule_version_sealable(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_coverage(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_item_windows(new.id, new.trip_plan_id);
  end if;

  return new;
end;
$$;

create or replace function public.require_draft_schedule_version()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  old_status text;
  new_status text;
  old_version_id uuid;
  new_version_id uuid;
begin
  if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
    return old;
  end if;

  if tg_op in ('UPDATE', 'DELETE') then
    old_version_id := old.schedule_version_id;
  end if;
  if tg_op in ('INSERT', 'UPDATE') then
    new_version_id := new.schedule_version_id;
  end if;

  perform version.id
  from public.trip_schedule_versions version
  where version.id = old_version_id or version.id = new_version_id
  order by version.id
  for share;

  if old_version_id is not null then
    select version.status
      into old_status
    from public.trip_schedule_versions version
    where version.id = old_version_id
    for share;

    if not found then
      if tg_op = 'DELETE' then
        return old;
      end if;
      raise exception 'schedule version % does not exist', old_version_id;
    end if;

    if old_status <> 'draft' then
      raise exception 'schedule content can only change while version % is draft',
        old_version_id;
    end if;
  end if;

  if new_version_id is not null then
    select version.status
      into new_status
    from public.trip_schedule_versions version
    where version.id = new_version_id
    for share;

    if not found then
      raise exception 'schedule version % does not exist', new_version_id;
    end if;

    if new_status <> 'draft' then
      raise exception 'schedule content can only change while version % is draft',
        new_version_id;
    end if;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

alter table public.trip_legs
  add constraint uq_trip_legs_day_identity
    unique (id, schedule_version_id, trip_plan_id, trip_day_id);

alter table public.compute_runs
  add constraint uq_compute_runs_day_identity
    unique (id, trip_plan_id, schedule_version_id, trip_day_id);

alter table public.trip_weather_impacts
  alter column trip_day_id set not null;

do $$
declare
  constraint_name text;
begin
  for constraint_name in
    select constraint_row.conname
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_weather_impacts'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.confrelid in (
        'public.compute_runs'::regclass,
        'public.trip_items'::regclass,
        'public.trip_legs'::regclass
      )
  loop
    execute format(
      'alter table public.trip_weather_impacts drop constraint %I',
      constraint_name
    );
  end loop;
end;
$$;

alter table public.trip_weather_impacts
  add constraint fk_trip_weather_impacts_compute_day
    foreign key (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id)
    references public.compute_runs (id, trip_plan_id, schedule_version_id, trip_day_id)
    on delete cascade,
  add constraint fk_trip_weather_impacts_item_day
    foreign key (trip_item_id, schedule_version_id, trip_plan_id, trip_day_id)
    references public.trip_items (id, schedule_version_id, trip_plan_id, trip_day_id)
    on delete cascade,
  add constraint fk_trip_weather_impacts_leg_day
    foreign key (trip_leg_id, schedule_version_id, trip_plan_id, trip_day_id)
    references public.trip_legs (id, schedule_version_id, trip_plan_id, trip_day_id)
    on delete cascade;

create index idx_trip_weather_impacts_compute_day
  on public.trip_weather_impacts
    (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id);
create index idx_trip_weather_impacts_item_day
  on public.trip_weather_impacts
    (trip_item_id, schedule_version_id, trip_plan_id, trip_day_id)
  where trip_item_id is not null;
create index idx_trip_weather_impacts_leg_day
  on public.trip_weather_impacts
    (trip_leg_id, schedule_version_id, trip_plan_id, trip_day_id)
  where trip_leg_id is not null;

alter table public.recommendation_candidates
  alter column trip_day_id set not null;

do $$
declare
  constraint_name text;
begin
  for constraint_name in
    select constraint_row.conname
    from pg_catalog.pg_constraint constraint_row
    where constraint_row.conrelid = 'public.recommendation_candidates'::regclass
      and constraint_row.contype = 'f'
      and constraint_row.confrelid in (
        'public.compute_runs'::regclass,
        'public.trip_items'::regclass
      )
  loop
    execute format(
      'alter table public.recommendation_candidates drop constraint %I',
      constraint_name
    );
  end loop;
end;
$$;

alter table public.recommendation_candidates
  add constraint fk_recommendation_candidates_compute_day
    foreign key (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id)
    references public.compute_runs (id, trip_plan_id, schedule_version_id, trip_day_id)
    on delete cascade,
  add constraint fk_recommendation_candidates_base_item_day
    foreign key (base_item_id, schedule_version_id, trip_plan_id, trip_day_id)
    references public.trip_items (id, schedule_version_id, trip_plan_id, trip_day_id);

create index idx_recommendation_candidates_compute_day
  on public.recommendation_candidates
    (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id);
create index idx_recommendation_candidates_base_item_day
  on public.recommendation_candidates
    (base_item_id, schedule_version_id, trip_plan_id, trip_day_id)
  where base_item_id is not null;
