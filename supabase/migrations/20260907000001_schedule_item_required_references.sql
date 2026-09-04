-- Issue #50 remediation: typed schedule items must keep their canonical references.

-- Fail the upgrade before installing constraints when a legacy row cannot satisfy the
-- public schedule-item contract. Do not silently invent or discard a reference.
do $$
declare
  invalid_item record;
begin
  select
    item.id,
    item.item_type,
    item.trip_plan_id,
    item.accommodation_id,
    item.transport_event_id
    into invalid_item
  from public.trip_items item
  left join public.trip_accommodations accommodation
    on accommodation.id = item.accommodation_id
   and accommodation.trip_plan_id = item.trip_plan_id
  left join public.trip_transport_events event
    on event.id = item.transport_event_id
   and event.trip_plan_id = item.trip_plan_id
  where not (
      (
        item.item_type = 'accommodation'
        and item.accommodation_id is not null
        and item.transport_event_id is null
        and accommodation.id is not null
      )
      or (
        item.item_type in ('arrival', 'departure')
        and item.accommodation_id is null
        and item.transport_event_id is not null
        and event.id is not null
        and event.event_type = item.item_type
      )
      or (
        item.item_type not in ('accommodation', 'arrival', 'departure')
        and item.accommodation_id is null
        and item.transport_event_id is null
      )
    )
  limit 1;

  if found then
    raise exception using
      errcode = '23514',
      message = format(
        'legacy schedule item required reference audit failed: item_id=%s, item_type=%s, trip_plan_id=%s, accommodation_id=%s, transport_event_id=%s',
        invalid_item.id,
        invalid_item.item_type,
        invalid_item.trip_plan_id,
        invalid_item.accommodation_id,
        invalid_item.transport_event_id
      );
  end if;
end;
$$;

alter table public.trip_items
  add constraint chk_trip_items_required_references check (
    (
      item_type = 'accommodation'
      and accommodation_id is not null
      and transport_event_id is null
    )
    or (
      item_type in ('arrival', 'departure')
      and accommodation_id is null
      and transport_event_id is not null
    )
    or (
      item_type not in ('accommodation', 'arrival', 'departure')
      and accommodation_id is null
      and transport_event_id is null
    )
  );

create function public.validate_trip_item_required_references()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.item_type = 'accommodation' then
    if new.accommodation_id is null or new.transport_event_id is not null then
      raise exception using
        errcode = '23514',
        message = 'accommodation schedule item requires only accommodation_id';
    end if;

    if not exists (
      select 1
      from public.trip_accommodations accommodation
      where accommodation.id = new.accommodation_id
        and accommodation.trip_plan_id = new.trip_plan_id
    ) then
      raise exception using
        errcode = '23503',
        message = 'schedule item accommodation must belong to the same trip';
    end if;
  elsif new.item_type in ('arrival', 'departure') then
    if new.accommodation_id is not null or new.transport_event_id is null then
      raise exception using
        errcode = '23514',
        message = format('%s schedule item requires only transport_event_id', new.item_type);
    end if;

    if not exists (
      select 1
      from public.trip_transport_events event
      where event.id = new.transport_event_id
        and event.trip_plan_id = new.trip_plan_id
        and event.event_type = new.item_type
    ) then
      raise exception using
        errcode = '23503',
        message = 'schedule item transport event must match the same trip and item type';
    end if;
  elsif new.accommodation_id is not null or new.transport_event_id is not null then
    raise exception using
      errcode = '23514',
      message = 'schedule item type does not allow accommodation or transport event references';
  end if;

  return new;
end;
$$;

create trigger trg_trip_items_required_references
before insert or update of item_type, trip_plan_id, accommodation_id, transport_event_id
on public.trip_items
for each row execute function public.validate_trip_item_required_references();

create function public.protect_transport_event_schedule_item_references()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if exists (
    select 1
    from public.trip_items item
    where item.transport_event_id = old.id
      and (
        item.trip_plan_id <> new.trip_plan_id
        or item.item_type <> new.event_type
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'transport event update conflicts with a schedule item reference';
  end if;

  return new;
end;
$$;

create trigger trg_trip_transport_events_schedule_item_references
before update of trip_plan_id, event_type on public.trip_transport_events
for each row execute function public.protect_transport_event_schedule_item_references();

create function public.assert_schedule_item_required_references(
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
    left join public.trip_accommodations accommodation
      on accommodation.id = item.accommodation_id
     and accommodation.trip_plan_id = item.trip_plan_id
    left join public.trip_transport_events event
      on event.id = item.transport_event_id
     and event.trip_plan_id = item.trip_plan_id
    where item.schedule_version_id = target_schedule_version_id
      and item.trip_plan_id = target_trip_plan_id
      and not (
        (
          item.item_type = 'accommodation'
          and item.accommodation_id is not null
          and item.transport_event_id is null
          and accommodation.id is not null
        )
        or (
          item.item_type in ('arrival', 'departure')
          and item.accommodation_id is null
          and item.transport_event_id is not null
          and event.id is not null
          and event.event_type = item.item_type
        )
        or (
          item.item_type not in ('accommodation', 'arrival', 'departure')
          and item.accommodation_id is null
          and item.transport_event_id is null
        )
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'sealed schedule items violate required reference invariants';
  end if;
end;
$$;

-- Preserve the mature timeline/leg assertion as a core function and make the public
-- entry point compose it with the new typed-reference assertion.
alter function public.assert_schedule_version_sealable(uuid, uuid)
  rename to assert_schedule_version_core_sealable;

create function public.assert_schedule_version_sealable(
  target_schedule_version_id uuid,
  target_trip_plan_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  perform public.assert_schedule_version_core_sealable(
    target_schedule_version_id,
    target_trip_plan_id
  );
  perform public.assert_schedule_item_required_references(
    target_schedule_version_id,
    target_trip_plan_id
  );
end;
$$;

revoke all on function public.assert_schedule_version_core_sealable(uuid, uuid) from public;
revoke execute on function public.assert_schedule_version_core_sealable(uuid, uuid) from anon;
revoke execute on function public.assert_schedule_version_core_sealable(uuid, uuid) from authenticated;
grant execute on function public.assert_schedule_version_core_sealable(uuid, uuid) to service_role;

revoke all on function public.assert_schedule_item_required_references(uuid, uuid) from public;
revoke execute on function public.assert_schedule_item_required_references(uuid, uuid) from anon;
revoke execute on function public.assert_schedule_item_required_references(uuid, uuid) from authenticated;
grant execute on function public.assert_schedule_item_required_references(uuid, uuid) to service_role;

revoke all on function public.validate_trip_item_required_references() from public;
revoke execute on function public.validate_trip_item_required_references() from anon;
revoke execute on function public.validate_trip_item_required_references() from authenticated;
grant execute on function public.validate_trip_item_required_references() to service_role;

revoke all on function public.protect_transport_event_schedule_item_references() from public;
revoke execute on function public.protect_transport_event_schedule_item_references() from anon;
revoke execute on function public.protect_transport_event_schedule_item_references() from authenticated;
grant execute on function public.protect_transport_event_schedule_item_references() to service_role;

revoke all on function public.assert_schedule_version_sealable(uuid, uuid) from public;
revoke execute on function public.assert_schedule_version_sealable(uuid, uuid) from anon;
revoke execute on function public.assert_schedule_version_sealable(uuid, uuid) from authenticated;
grant execute on function public.assert_schedule_version_sealable(uuid, uuid) to service_role;

revoke all on function public.validate_schedule_version_sealing() from public;
revoke execute on function public.validate_schedule_version_sealing() from anon;
revoke execute on function public.validate_schedule_version_sealing() from authenticated;
grant execute on function public.validate_schedule_version_sealing() to service_role;
