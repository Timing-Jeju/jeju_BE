\set ON_ERROR_STOP on

-- 완전 migration DB를 00001 직전 상태로 되돌린 뒤 v1 보존 item 하나를 오염시킨다.
drop trigger trg_validate_trip_item_required_references on public.trip_items;
alter table public.trip_items
  drop constraint trip_items_required_references_by_type;

update public.trip_items
set item_type = 'accommodation', accommodation_id = null, transport_event_id = null
where id = 'e4300000-0000-0000-0000-000000000001';
