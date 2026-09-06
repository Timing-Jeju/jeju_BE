-- Issue #51: strengthen the immutable Issue #50 required-reference contract additively.
begin;

-- Fail before replacing any catalog object. This keeps the whole migration rollback-safe
-- when a legacy item does not satisfy the canonical mutation contract.
do $$
declare
  invalid_item record;
begin
  select
    item.id,
    item.item_type,
    item.trip_plan_id,
    item.place_id,
    item.accommodation_id,
    item.transport_event_id,
    item.title
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
        item.item_type = 'place_visit'
        and item.place_id is not null
        and item.accommodation_id is null
        and item.transport_event_id is null
      )
      or (
        item.item_type = 'accommodation'
        and item.accommodation_id is not null
        and item.transport_event_id is null
      )
      or (
        item.item_type in ('arrival', 'departure')
        and item.accommodation_id is null
        and item.transport_event_id is not null
      )
      or (
        item.item_type in ('meal', 'free_time', 'custom')
        and item.accommodation_id is null
        and item.transport_event_id is null
        and item.title is not null
        and translate(
          item.title,
          U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000',
          ''
        ) <> ''
      )
    )
    or (item.item_type = 'accommodation' and accommodation.id is null)
    or (
      item.item_type in ('arrival', 'departure')
      and (event.id is null or event.event_type <> item.item_type)
    )
  limit 1;

  if found then
    raise exception using
      errcode = '23514',
      message = format(
        'legacy schedule item required reference audit failed: item_id=%s, item_type=%s, trip_plan_id=%s, invalid_fields=%s',
        invalid_item.id,
        invalid_item.item_type,
        invalid_item.trip_plan_id,
        'required_reference_contract'
      );
  end if;
end;
$$;

drop trigger trg_trip_items_required_references on public.trip_items;

alter table public.trip_items
  drop constraint chk_trip_items_required_references;

alter table public.trip_items
  add constraint chk_trip_items_required_references check (
    (
      item_type = 'place_visit'
      and place_id is not null
      and accommodation_id is null
      and transport_event_id is null
    )
    or (
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
      item_type in ('meal', 'free_time', 'custom')
      and accommodation_id is null
      and transport_event_id is null
      and title is not null
      and translate(
        title,
        U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000',
        ''
      ) <> ''
    )
  );

create or replace function public.validate_trip_item_required_references()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if not (
    (new.item_type = 'place_visit'
      and new.place_id is not null
      and new.accommodation_id is null
      and new.transport_event_id is null)
    or (new.item_type = 'accommodation'
      and new.accommodation_id is not null
      and new.transport_event_id is null)
    or (new.item_type in ('arrival', 'departure')
      and new.accommodation_id is null
      and new.transport_event_id is not null)
    or (new.item_type in ('meal', 'free_time', 'custom')
      and new.accommodation_id is null
      and new.transport_event_id is null
      and new.title is not null
      and translate(
        new.title,
        U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000',
        ''
      ) <> '')
  ) then
    raise exception using
      errcode = '23514',
      message = 'schedule item required reference does not match item type';
  end if;

  if new.item_type = 'accommodation' then
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
  end if;

  return new;
end;
$$;

create trigger trg_trip_items_required_references
before insert or update of item_type, trip_plan_id, place_id, accommodation_id, transport_event_id, title
on public.trip_items
for each row execute function public.validate_trip_item_required_references();

create or replace function public.assert_schedule_item_required_references(
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
      and (
        not (
          (
            item.item_type = 'place_visit'
            and item.place_id is not null
            and item.accommodation_id is null
            and item.transport_event_id is null
          )
          or (
            item.item_type = 'accommodation'
            and item.accommodation_id is not null
            and item.transport_event_id is null
          )
          or (
            item.item_type in ('arrival', 'departure')
            and item.accommodation_id is null
            and item.transport_event_id is not null
          )
          or (
            item.item_type in ('meal', 'free_time', 'custom')
            and item.accommodation_id is null
            and item.transport_event_id is null
            and item.title is not null
            and translate(
              item.title,
              U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000',
              ''
            ) <> ''
          )
        )
        or (item.item_type = 'accommodation' and accommodation.id is null)
        or (
          item.item_type in ('arrival', 'departure')
          and (event.id is null or event.event_type <> item.item_type)
        )
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'sealed schedule items violate required reference invariants';
  end if;
end;
$$;

revoke all on function public.validate_trip_item_required_references() from public;
revoke execute on function public.validate_trip_item_required_references() from anon;
revoke execute on function public.validate_trip_item_required_references() from authenticated;
revoke execute on function public.validate_trip_item_required_references() from service_role;

revoke all on function public.assert_schedule_item_required_references(uuid, uuid) from public;
revoke execute on function public.assert_schedule_item_required_references(uuid, uuid) from anon;
revoke execute on function public.assert_schedule_item_required_references(uuid, uuid) from authenticated;
grant execute on function public.assert_schedule_item_required_references(uuid, uuid) to service_role;

commit;
