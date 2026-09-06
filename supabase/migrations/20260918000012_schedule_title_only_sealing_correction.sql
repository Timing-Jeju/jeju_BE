-- Issue #215: align the legacy core sealing assertion with the Issue #51 title-only contract.
begin;

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
      and i.place_id is null
      and i.item_type not in ('meal', 'free_time', 'custom')
      and not coalesce(
        jsonb_typeof(i.facts -> 'location') = 'object'
        and jsonb_typeof(i.facts #> '{location,lat}') = 'number'
        and jsonb_typeof(i.facts #> '{location,lng}') = 'number',
        false
      )
  ) then
    raise exception 'sealed schedule items require a place or explicit location facts';
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
        or l.planned_arrival_at <= l.planned_departure_at
        or l.duration_minutes <= 0
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
