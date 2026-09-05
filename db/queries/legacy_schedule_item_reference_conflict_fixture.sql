\set ON_ERROR_STOP on

-- legacy audit rejects invalid required reference: 완전 migration DB를 00001 직전 상태로
-- 되돌린 뒤 canonical custom title을 newline-only로 오염시킨다.
drop trigger trg_trip_items_required_references on public.trip_items;
alter table public.trip_items
  drop constraint chk_trip_items_required_references;

update public.trip_items
set item_type = 'custom', place_id = null,
    accommodation_id = null, transport_event_id = null, title = E'\n'
where id = 'e4300000-0000-0000-0000-000000000001';
