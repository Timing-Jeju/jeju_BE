-- Issue #11: 확정 일정과 날짜 기반 계산 결과의 교차 행 무결성을 강화한다.

-- 일정 봉인과 Day/달력 변경은 아직 커밋되지 않은 schedule version의 가시성에
-- 의존하지 않고 같은 여행 행에 MVCC 쓰기 펜스를 먼저 세운다. READ COMMITTED는
-- 같은 trip_plan의 최신 상태를 다시 읽고, 오래된 REPEATABLE READ 스냅샷은
-- 대기 후 40001로 중단되어 확정 일정에 stale 쓰기를 남길 수 없다.
create function public.lock_trip_plan_schedule_mutex(target_trip_plan_id uuid)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  update public.trip_plans p
  set updated_at = p.updated_at
  where p.id = target_trip_plan_id;

  if not found then
    raise exception 'trip plan % does not exist', target_trip_plan_id;
  end if;
end;
$$;

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

  perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);

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
    and parent.trip_plan_id = new.trip_plan_id;

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

-- 자식 생성과 여행 날짜 변경이 서로의 부재를 동시에 관찰하지 못하도록
-- 두 경로가 같은 trip_plans 행 잠금을 공유한다.
create or replace function public.validate_trip_calendar_child()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  plan_start_date date;
  plan_end_date date;
  event_local_date date;
begin
  perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);

  select p.start_date, p.end_date
    into plan_start_date, plan_end_date
  from public.trip_plans p
  where p.id = new.trip_plan_id;

  if tg_table_name = 'trip_days' then
    if new.trip_date <> plan_start_date + (new.day_no - 1)
       or new.trip_date > plan_end_date then
      raise exception 'trip day % date % is inconsistent with trip range %..%',
        new.day_no, new.trip_date, plan_start_date, plan_end_date;
    end if;
  elsif tg_table_name = 'trip_transport_events' then
    event_local_date := timezone('Asia/Seoul', new.scheduled_at)::date;
    if event_local_date < plan_start_date or event_local_date > plan_end_date then
      raise exception 'transport event date % is outside trip range %..%',
        event_local_date, plan_start_date, plan_end_date;
    end if;
  elsif tg_table_name = 'trip_accommodations' then
    if new.check_in_date < plan_start_date
       or new.check_out_date > plan_end_date
       or new.check_out_date <= new.check_in_date then
      raise exception 'accommodation range %..% is outside trip range %..%',
        new.check_in_date, new.check_out_date, plan_start_date, plan_end_date;
    end if;
  end if;

  return new;
end;
$$;

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

  perform public.lock_trip_plan_schedule_mutex(target_trip_plan_id);

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

  -- 이 함수는 trip_plans 자체의 BEFORE UPDATE trigger다. 바깥 UPDATE가 이미
  -- 같은 부모 행의 MVCC 쓰기 펜스이므로 동일 행을 재귀 UPDATE하지 않는다.

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
    perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);
    perform public.assert_schedule_version_sealable(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_coverage(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_item_windows(new.id, new.trip_plan_id);
  end if;

  return new;
end;
$$;

-- 새 trigger는 과거 행에 자동 실행되지 않으므로 이미 봉인된 일정도 한 번 감사한다.
-- 잘못된 봉인 상태를 조용히 유지하거나 임의로 상태를 바꾸지 않고 명확히 중단한다.
do $$
declare
  sealed_version record;
  invalid_base record;
begin
  select
    child.id as child_id,
    parent.id as parent_id,
    child.version_no as child_version_no,
    parent.version_no as parent_version_no
    into invalid_base
  from public.trip_schedule_versions child
  join public.trip_schedule_versions parent
    on parent.id = child.base_schedule_version_id
   and parent.trip_plan_id = child.trip_plan_id
  where child.base_schedule_version_id is not null
    and parent.version_no >= child.version_no
  order by child.id
  limit 1;

  if found then
    raise exception using
      errcode = '23514',
      message = 'legacy schedule base lineage is invalid',
      detail = pg_catalog.format(
        'child_id=%s, parent_id=%s, child_version_no=%s, parent_version_no=%s',
        invalid_base.child_id,
        invalid_base.parent_id,
        invalid_base.child_version_no,
        invalid_base.parent_version_no
      );
  end if;

  for sealed_version in
    select version.id, version.trip_plan_id
    from public.trip_schedule_versions version
    where version.status in ('candidate', 'active')
    order by version.trip_plan_id, version.version_no
  loop
    begin
      perform public.assert_schedule_version_sealable(
        sealed_version.id,
        sealed_version.trip_plan_id
      );
      perform public.assert_schedule_day_coverage(
        sealed_version.id,
        sealed_version.trip_plan_id
      );
      perform public.assert_schedule_day_item_windows(
        sealed_version.id,
        sealed_version.trip_plan_id
      );
    exception when others then
      raise exception 'legacy sealed schedule failed integrity audit: %',
        sealed_version.id
        using detail = sqlerrm;
    end;
  end loop;
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

create function public.require_new_day_scoped_result()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  old_result jsonb;
  new_result jsonb;
begin
  new_result := to_jsonb(new);

  if new.trip_day_id is null
     and (
       tg_op = 'INSERT'
       or old.trip_day_id is not null
     ) then
    raise exception using
      errcode = '23502',
      message = 'new day-scoped result requires trip_day_id';
  end if;

  if tg_op = 'UPDATE' and old.trip_day_id is null and new.trip_day_id is null then
    old_result := to_jsonb(old);

    if tg_table_name = 'trip_weather_impacts'
       and (
         old_result ->> 'trip_plan_id' is distinct from new_result ->> 'trip_plan_id'
         or old_result ->> 'schedule_version_id'
            is distinct from new_result ->> 'schedule_version_id'
         or old_result ->> 'compute_run_id'
            is distinct from new_result ->> 'compute_run_id'
         or old_result ->> 'trip_item_id'
            is distinct from new_result ->> 'trip_item_id'
         or old_result ->> 'trip_leg_id'
            is distinct from new_result ->> 'trip_leg_id'
       ) then
      raise exception using
        errcode = '23514',
        message = 'legacy null-day weather lineage is immutable until day repair';
    end if;

    if tg_table_name = 'recommendation_candidates'
       and (
         old_result ->> 'trip_plan_id' is distinct from new_result ->> 'trip_plan_id'
         or old_result ->> 'schedule_version_id'
            is distinct from new_result ->> 'schedule_version_id'
         or old_result ->> 'compute_run_id'
            is distinct from new_result ->> 'compute_run_id'
         or old_result ->> 'base_item_id'
            is distinct from new_result ->> 'base_item_id'
       ) then
      raise exception using
        errcode = '23514',
        message = 'legacy null-day recommendation lineage is immutable until day repair';
    end if;
  end if;

  return new;
end;
$$;

create trigger trg_trip_weather_impacts_require_day
before insert or update on public.trip_weather_impacts
for each row execute function public.require_new_day_scoped_result();

create trigger trg_recommendation_candidates_require_day
before insert or update on public.recommendation_candidates
for each row execute function public.require_new_day_scoped_result();

create function public.protect_legacy_null_day_result_parent()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.trip_day_id is not distinct from new.trip_day_id then
    return new;
  end if;

  if tg_table_name = 'compute_runs'
     and (
       exists (
         select 1
         from public.trip_weather_impacts impact
         where impact.trip_day_id is null
           and impact.compute_run_id = old.id
       )
       or exists (
         select 1
         from public.recommendation_candidates candidate
         where candidate.trip_day_id is null
           and candidate.compute_run_id = old.id
       )
     ) then
    raise exception using
      errcode = '23514',
      message = 'compute run day is immutable while legacy null-day results reference it';
  end if;

  if tg_table_name = 'trip_items'
     and (
       exists (
         select 1
         from public.trip_weather_impacts impact
         where impact.trip_day_id is null
           and impact.trip_item_id = old.id
       )
       or exists (
         select 1
         from public.recommendation_candidates candidate
         where candidate.trip_day_id is null
           and candidate.base_item_id = old.id
       )
     ) then
    raise exception using
      errcode = '23514',
      message = 'trip item day is immutable while legacy null-day results reference it';
  end if;

  if tg_table_name = 'trip_legs'
     and exists (
       select 1
       from public.trip_weather_impacts impact
       where impact.trip_day_id is null
         and impact.trip_leg_id = old.id
     ) then
    raise exception using
      errcode = '23514',
      message = 'trip leg day is immutable while legacy null-day results reference it';
  end if;

  return new;
end;
$$;

create trigger trg_compute_runs_legacy_null_day_parent
before update of trip_day_id on public.compute_runs
for each row execute function public.protect_legacy_null_day_result_parent();

create trigger trg_trip_items_legacy_null_day_parent
before update of trip_day_id on public.trip_items
for each row execute function public.protect_legacy_null_day_result_parent();

create trigger trg_trip_legs_legacy_null_day_parent
before update of trip_day_id on public.trip_legs
for each row execute function public.protect_legacy_null_day_result_parent();

-- NULL day인 v1 결과는 grandfathering하지만, 이미 day가 있던 결과의 부모가
-- 서로 다른 day를 가리키는 상태는 새 FK를 설치하기 전에 명확히 중단한다.
do $$
declare
  invalid_result record;
begin
  select 'trip_weather_impacts'::text as result_kind, impact.id as result_id
    into invalid_result
  from public.trip_weather_impacts impact
  where impact.trip_day_id is not null
    and (
      not exists (
        select 1
        from public.compute_runs compute_run
        where compute_run.id = impact.compute_run_id
          and compute_run.trip_plan_id = impact.trip_plan_id
          and compute_run.schedule_version_id = impact.schedule_version_id
          and compute_run.trip_day_id = impact.trip_day_id
      )
      or (
        impact.trip_item_id is not null
        and not exists (
          select 1
          from public.trip_items item
          where item.id = impact.trip_item_id
            and item.schedule_version_id = impact.schedule_version_id
            and item.trip_plan_id = impact.trip_plan_id
            and item.trip_day_id = impact.trip_day_id
        )
      )
      or (
        impact.trip_leg_id is not null
        and not exists (
          select 1
          from public.trip_legs leg
          where leg.id = impact.trip_leg_id
            and leg.schedule_version_id = impact.schedule_version_id
            and leg.trip_plan_id = impact.trip_plan_id
            and leg.trip_day_id = impact.trip_day_id
        )
      )
    )
  order by impact.id
  limit 1;

  if not found then
    select 'recommendation_candidates'::text as result_kind,
           candidate.id as result_id
      into invalid_result
    from public.recommendation_candidates candidate
    where candidate.trip_day_id is not null
      and (
        not exists (
          select 1
          from public.compute_runs compute_run
          where compute_run.id = candidate.compute_run_id
            and compute_run.trip_plan_id = candidate.trip_plan_id
            and compute_run.schedule_version_id = candidate.schedule_version_id
            and compute_run.trip_day_id = candidate.trip_day_id
        )
        or (
          candidate.base_item_id is not null
          and not exists (
            select 1
            from public.trip_items item
            where item.id = candidate.base_item_id
              and item.schedule_version_id = candidate.schedule_version_id
              and item.trip_plan_id = candidate.trip_plan_id
              and item.trip_day_id = candidate.trip_day_id
          )
        )
      )
    order by candidate.id
    limit 1;
  end if;

  if found then
    raise exception using
      errcode = '23514',
      message = 'legacy day-scoped result failed same-day lineage audit',
      detail = pg_catalog.format(
        'result_kind=%s, result_id=%s',
        invalid_result.result_kind,
        invalid_result.result_id
      );
  end if;
end;
$$;

alter table public.trip_weather_impacts
  add constraint fk_trip_weather_impacts_compute_day
    foreign key (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id)
    references public.compute_runs (id, trip_plan_id, schedule_version_id, trip_day_id)
    on delete cascade
    not valid,
  add constraint fk_trip_weather_impacts_item_day
    foreign key (trip_item_id, schedule_version_id, trip_plan_id, trip_day_id)
    references public.trip_items (id, schedule_version_id, trip_plan_id, trip_day_id)
    on delete cascade
    not valid,
  add constraint fk_trip_weather_impacts_leg_day
    foreign key (trip_leg_id, schedule_version_id, trip_plan_id, trip_day_id)
    references public.trip_legs (id, schedule_version_id, trip_plan_id, trip_day_id)
    on delete cascade
    not valid;

alter table public.trip_weather_impacts
  validate constraint fk_trip_weather_impacts_trip_day_plan,
  validate constraint fk_trip_weather_impacts_compute_day,
  validate constraint fk_trip_weather_impacts_item_day,
  validate constraint fk_trip_weather_impacts_leg_day;

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
  add constraint fk_recommendation_candidates_compute_day
    foreign key (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id)
    references public.compute_runs (id, trip_plan_id, schedule_version_id, trip_day_id)
    on delete cascade
    not valid,
  add constraint fk_recommendation_candidates_base_item_day
    foreign key (base_item_id, schedule_version_id, trip_plan_id, trip_day_id)
    references public.trip_items (id, schedule_version_id, trip_plan_id, trip_day_id)
    not valid;

alter table public.recommendation_candidates
  validate constraint fk_recommendation_candidates_trip_day_plan,
  validate constraint fk_recommendation_candidates_compute_day,
  validate constraint fk_recommendation_candidates_base_item_day;

create index idx_recommendation_candidates_compute_day
  on public.recommendation_candidates
    (compute_run_id, trip_plan_id, schedule_version_id, trip_day_id);
create index idx_recommendation_candidates_base_item_day
  on public.recommendation_candidates
    (base_item_id, schedule_version_id, trip_plan_id, trip_day_id)
  where base_item_id is not null;
