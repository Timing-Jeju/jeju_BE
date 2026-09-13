-- #53/#79: 명시적인 하루 기준점만 체류 0분을 허용한다. 기존 facts 슬롯은 열지 않는다.
begin;

alter table public.trip_items
  add column boundary_role text,
  add constraint trip_item_boundary_role_check check (
    boundary_role is null or (
      boundary_role in ('day_start','day_end')
      and item_type='custom' and place_id is not null
      and planned_start_at is not null and planned_end_at is not null
      and planned_start_at=planned_end_at and stay_minutes is not null and stay_minutes=0
      and buffer_after_minutes=0
    )
  );

comment on column public.trip_items.boundary_role is
  '서버가 생성하는 체류시간 없는 계획 장소 경계. 일반 방문·사용자 원문이 아니다.';

create or replace function public.assert_schedule_version_core_sealable(
  target_schedule_version_id uuid,
  target_trip_plan_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
  item_count integer;
  expected_leg_count integer;
  actual_leg_count integer;
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


  if exists (
    select 1 from public.trip_items i
    join public.trip_schedule_versions v on v.id=i.schedule_version_id
    where i.schedule_version_id=target_schedule_version_id and i.boundary_role is not null
      and (v.coverage_through_day_no is null or v.source_type not in
        ('ai_generation','user_edit','recovery','live_recalculation'))
  ) then
    raise exception 'day boundaries require a sequential generated schedule' using errcode='23514';
  end if;

  if exists (
    select 1 from public.trip_items i where i.schedule_version_id=target_schedule_version_id
    group by i.trip_day_id
    having count(*) filter (where i.boundary_role is not null)>0
      and (count(*) filter (where i.boundary_role='day_start')<>1
        or count(*) filter (where i.boundary_role='day_end')<>1
        or min(i.sequence_no) filter (where i.boundary_role='day_start')<>1
        or max(i.sequence_no) filter (where i.boundary_role='day_end')<>max(i.sequence_no)
        or count(*) filter (where i.boundary_role is null)=0)
  ) then
    raise exception 'each bounded day requires a start, real activity, and end' using errcode='23514';
  end if;

  if exists (
    select 1 from public.trip_schedule_versions v
    where v.id=target_schedule_version_id and v.source_type in ('user_edit','recovery','live_recalculation')
      and exists (
        (select i.trip_day_id,i.boundary_role,i.place_id,i.planned_start_at,i.planned_end_at
          from public.trip_items i where i.schedule_version_id=v.base_schedule_version_id and i.boundary_role is not null
         except
         select i.trip_day_id,i.boundary_role,i.place_id,i.planned_start_at,i.planned_end_at
          from public.trip_items i where i.schedule_version_id=v.id and i.boundary_role is not null)
        union all
        (select i.trip_day_id,i.boundary_role,i.place_id,i.planned_start_at,i.planned_end_at
          from public.trip_items i where i.schedule_version_id=v.id and i.boundary_role is not null
         except
         select i.trip_day_id,i.boundary_role,i.place_id,i.planned_start_at,i.planned_end_at
          from public.trip_items i where i.schedule_version_id=v.base_schedule_version_id and i.boundary_role is not null)
      )
  ) then
    raise exception 'derived schedules must preserve day boundaries' using errcode='23514';
  end if;

  select count(*)
    into item_count
  from public.trip_items i
  where i.schedule_version_id = target_schedule_version_id
    and i.trip_plan_id = target_trip_plan_id;

  if item_count = 0 then
    raise exception 'schedule version % cannot be sealed without items', target_schedule_version_id;
  end if;

  if exists (
    select 1
    from public.trip_items i
    where i.schedule_version_id = target_schedule_version_id
      and i.trip_plan_id = target_trip_plan_id
      and i.boundary_role is null
      and (
        i.planned_start_at is null
        or i.planned_end_at is null
        or i.planned_end_at <= i.planned_start_at
        or i.stay_minutes is null
        or i.stay_minutes <= 0
        or i.stay_minutes <> floor(extract(epoch from (i.planned_end_at - i.planned_start_at)) / 60)::integer
      )
  ) then
    raise exception 'sealed schedule items require consistent start, end, and stay minutes';
  end if;

  if exists (
    select 1
    from public.trip_items i
    where i.schedule_version_id = target_schedule_version_id
      and i.trip_plan_id = target_trip_plan_id
      and (i.item_type not in ('meal', 'free_time', 'custom') or i.boundary_role is not null)
      and not exists (
        select 1 from timing_jeju_planner_private.resolve_planned_item_anchor(
          i.id, target_schedule_version_id, target_trip_plan_id
        )
      )
  ) then
    raise exception 'sealed schedule items require a canonical public planned anchor';
  end if;

  if exists (
    select 1
    from (
      select
        i.sequence_no,
        row_number() over (
          partition by i.trip_day_id
          order by i.sequence_no
        ) as expected_sequence_no
      from public.trip_items i
      where i.schedule_version_id = target_schedule_version_id
        and i.trip_plan_id = target_trip_plan_id
    ) ordered_items
    where ordered_items.sequence_no <> ordered_items.expected_sequence_no
  ) then
    raise exception 'sealed schedule item sequence numbers must be contiguous per day';
  end if;

  if exists (
    select 1
    from public.trip_items left_item
    join public.trip_items right_item
      on right_item.schedule_version_id = left_item.schedule_version_id
     and right_item.trip_plan_id = left_item.trip_plan_id
     and right_item.trip_day_id = left_item.trip_day_id
     and right_item.id > left_item.id
    where left_item.schedule_version_id = target_schedule_version_id
      and left_item.trip_plan_id = target_trip_plan_id
      and tstzrange(left_item.planned_start_at, left_item.planned_end_at, '[)')
          && tstzrange(right_item.planned_start_at, right_item.planned_end_at, '[)')
  ) then
    raise exception 'sealed schedule items cannot overlap within a day';
  end if;

  select coalesce(sum(greatest(day_item_count - 1, 0)), 0)::integer
    into expected_leg_count
  from (
    select count(*)::integer as day_item_count
    from public.trip_items i
    where i.schedule_version_id = target_schedule_version_id
      and i.trip_plan_id = target_trip_plan_id
    group by i.trip_day_id
  ) day_counts;

  select count(*)
    into actual_leg_count
  from public.trip_legs l
  where l.schedule_version_id = target_schedule_version_id
    and l.trip_plan_id = target_trip_plan_id;

  if actual_leg_count <> expected_leg_count then
    raise exception 'sealed schedule version % requires % consecutive legs but has %',
      target_schedule_version_id, expected_leg_count, actual_leg_count;
  end if;

  if exists (
    with ordered_items as (
      select
        i.trip_day_id,
        i.id as from_item_id,
        i.sequence_no,
        lead(i.id) over (
          partition by i.trip_day_id
          order by i.sequence_no
        ) as to_item_id
      from public.trip_items i
      where i.schedule_version_id = target_schedule_version_id
        and i.trip_plan_id = target_trip_plan_id
    )
    select 1
    from ordered_items o
    where o.to_item_id is not null
      and not exists (
        select 1
        from public.trip_legs l
        where l.schedule_version_id = target_schedule_version_id
          and l.trip_plan_id = target_trip_plan_id
          and l.trip_day_id = o.trip_day_id
          and l.sequence_no = o.sequence_no
          and l.from_item_id = o.from_item_id
          and l.to_item_id = o.to_item_id
      )
  ) then
    raise exception 'sealed schedule legs must connect every consecutive item';
  end if;

  if exists (
    select 1
    from public.trip_legs l
    join public.trip_items from_item
      on from_item.id = l.from_item_id
     and from_item.schedule_version_id = l.schedule_version_id
    join public.trip_items to_item
      on to_item.id = l.to_item_id
     and to_item.schedule_version_id = l.schedule_version_id
    where l.schedule_version_id = target_schedule_version_id
      and l.trip_plan_id = target_trip_plan_id
      and (
        l.planned_departure_at is null
        or l.planned_arrival_at is null
        or l.duration_minutes is null
        or l.planned_arrival_at < l.planned_departure_at
        or l.duration_minutes < 0
        or (l.duration_minutes = 0 and (
          from_item.place_id is null
          or from_item.place_id is distinct from to_item.place_id
          or l.transport_mode is distinct from 'walk'
          or l.planned_arrival_at is distinct from l.planned_departure_at
          or l.planned_departure_at is distinct from from_item.planned_end_at
          or l.walk_minutes is distinct from 0
          or l.wait_minutes is distinct from 0
          or l.ride_minutes is distinct from 0
          or l.transfer_minutes is distinct from 0
          or l.buffer_minutes is distinct from 0
          or l.distance_meters is distinct from 0
          or l.estimated_fare is distinct from 0
        ))
        or l.duration_minutes <> floor(
          extract(epoch from (l.planned_arrival_at - l.planned_departure_at)) / 60
        )::integer
        or l.duration_minutes <>
          l.walk_minutes + l.wait_minutes + l.ride_minutes + l.transfer_minutes
        or l.planned_departure_at < from_item.planned_end_at
        or l.planned_arrival_at > to_item.planned_start_at
      )
  ) then
    raise exception 'sealed schedule legs require consistent component durations within adjacent item windows';
  end if;
end;
$$;

revoke all on function public.assert_schedule_version_core_sealable(uuid, uuid) from public;
revoke execute on function public.assert_schedule_version_core_sealable(uuid, uuid) from anon;
revoke execute on function public.assert_schedule_version_core_sealable(uuid, uuid) from authenticated;
grant execute on function public.assert_schedule_version_core_sealable(uuid, uuid) to service_role;

commit;
