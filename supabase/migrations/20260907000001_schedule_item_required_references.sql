-- Issue #50/#88 itemPolicy.requiredByType를 DB의 모든 쓰기 경계에서 강제한다.
-- 이미 배포된 create-contract migration은 변경하지 않고 append-only correction으로 적용한다.

do $$
declare
  invalid_item record;
begin
  select item.id, item.item_type, item.accommodation_id, item.transport_event_id
    into invalid_item
  from public.trip_items item
  where not (
    (item.item_type = 'accommodation'
      and item.accommodation_id is not null
      and item.transport_event_id is null)
    or (item.item_type in ('arrival', 'departure')
      and item.transport_event_id is not null
      and item.accommodation_id is null)
    or (item.item_type not in ('accommodation', 'arrival', 'departure')
      and item.accommodation_id is null
      and item.transport_event_id is null)
  )
  order by item.id
  limit 1;

  if found then
    raise exception using
      errcode = '23514',
      message = 'legacy schedule item required reference audit failed',
      detail = pg_catalog.format(
        'item_id=%s, item_type=%s, accommodation_id=%s, transport_event_id=%s',
        invalid_item.id,
        invalid_item.item_type,
        invalid_item.accommodation_id,
        invalid_item.transport_event_id
      );
  end if;
end;
$$;

alter table public.trip_items
  add constraint trip_items_required_references_by_type
  check (
    (item_type = 'accommodation'
      and accommodation_id is not null
      and transport_event_id is null)
    or (item_type in ('arrival', 'departure')
      and transport_event_id is not null
      and accommodation_id is null)
    or (item_type not in ('accommodation', 'arrival', 'departure')
      and accommodation_id is null
      and transport_event_id is null)
  ) not valid;

alter table public.trip_items
  validate constraint trip_items_required_references_by_type;

create or replace function public.validate_trip_item_required_references()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if not (
    (new.item_type = 'accommodation'
      and new.accommodation_id is not null
      and new.transport_event_id is null)
    or (new.item_type in ('arrival', 'departure')
      and new.transport_event_id is not null
      and new.accommodation_id is null)
    or (new.item_type not in ('accommodation', 'arrival', 'departure')
      and new.accommodation_id is null
      and new.transport_event_id is null)
  ) then
    raise exception using
      errcode = '23514',
      message = 'schedule item required reference does not match item type',
      detail = pg_catalog.format('item_id=%s, item_type=%s', new.id, new.item_type);
  end if;
  return new;
end;
$$;

create trigger trg_validate_trip_item_required_references
before insert or update of item_type, accommodation_id, transport_event_id
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
    where item.schedule_version_id = target_schedule_version_id
      and item.trip_plan_id = target_trip_plan_id
      and not (
        (item.item_type = 'accommodation'
          and item.accommodation_id is not null
          and item.transport_event_id is null)
        or (item.item_type in ('arrival', 'departure')
          and item.transport_event_id is not null
          and item.accommodation_id is null)
        or (item.item_type not in ('accommodation', 'arrival', 'departure')
          and item.accommodation_id is null
          and item.transport_event_id is null)
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'schedule version contains an item with invalid required references';
  end if;
end;
$$;

revoke execute on function public.assert_schedule_item_required_references(uuid, uuid)
from public, anon, authenticated;
grant execute on function public.assert_schedule_item_required_references(uuid, uuid)
to service_role;

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
    perform public.assert_schedule_item_required_references(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_coverage(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_item_windows(new.id, new.trip_plan_id);
  end if;

  return new;
end;
$$;
