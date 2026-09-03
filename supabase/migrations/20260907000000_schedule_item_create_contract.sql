-- Issue #50: schedule item type references required by the public create contract.
alter table public.trip_transport_events
  add constraint uq_trip_transport_events_id_plan unique (id, trip_plan_id);

alter table public.trip_accommodations
  add constraint uq_trip_accommodations_id_plan unique (id, trip_plan_id);

alter table public.trip_items
  add column accommodation_id uuid,
  add column transport_event_id uuid,
  add constraint fk_trip_items_accommodation_plan
    foreign key (accommodation_id, trip_plan_id)
    references public.trip_accommodations (id, trip_plan_id),
  add constraint fk_trip_items_transport_event_plan
    foreign key (transport_event_id, trip_plan_id)
    references public.trip_transport_events (id, trip_plan_id);

create index idx_trip_items_accommodation
  on public.trip_items (accommodation_id, trip_plan_id)
  where accommodation_id is not null;

create index idx_trip_items_transport_event
  on public.trip_items (transport_event_id, trip_plan_id)
  where transport_event_id is not null;

comment on column public.trip_items.accommodation_id is
  '일정 accommodation 항목이 참조하는 동일 여행의 숙소';
comment on column public.trip_items.transport_event_id is
  '일정 arrival/departure 항목이 참조하는 동일 여행의 교통 이벤트';
