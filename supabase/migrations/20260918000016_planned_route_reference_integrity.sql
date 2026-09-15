-- Issue #225: indexed planned route references and owner-trip lifecycle; migration 015 stays immutable.
begin;

alter table public.mobility_route_snapshots drop constraint fk_route_planned_owner;
alter table public.mobility_route_snapshots add constraint fk_route_planned_owner
  foreign key (trip_plan_id, owner_user_id) references public.trip_plans(id, user_id) on delete cascade;

create index idx_route_ref_owner on public.mobility_route_snapshots (trip_plan_id, owner_user_id);
create index idx_route_ref_version on public.mobility_route_snapshots (schedule_version_id, trip_plan_id);
create index idx_route_ref_origin_item on public.mobility_route_snapshots (origin_item_id, schedule_version_id, trip_plan_id);
create index idx_route_ref_destination_item on public.mobility_route_snapshots (destination_item_id, schedule_version_id, trip_plan_id);
create index idx_route_ref_origin_source_place_id on public.mobility_route_snapshots (origin_source_place_id);
create index idx_route_ref_origin_source_stop_id on public.mobility_route_snapshots (origin_source_stop_id);
create index idx_route_ref_origin_place_ref_id on public.mobility_route_snapshots (origin_place_ref_id);
create index idx_route_ref_origin_stop_ref_id on public.mobility_route_snapshots (origin_stop_ref_id);
create index idx_route_ref_origin_accommodation_ref_id on public.mobility_route_snapshots (origin_accommodation_ref_id);
create index idx_route_ref_origin_transport_event_ref_id on public.mobility_route_snapshots (origin_transport_event_ref_id);
create index idx_route_ref_destination_source_place_id on public.mobility_route_snapshots (destination_source_place_id);
create index idx_route_ref_destination_source_stop_id on public.mobility_route_snapshots (destination_source_stop_id);
create index idx_route_ref_destination_place_ref_id on public.mobility_route_snapshots (destination_place_ref_id);
create index idx_route_ref_destination_stop_ref_id on public.mobility_route_snapshots (destination_stop_ref_id);
create index idx_route_ref_destination_accommodation_ref_id on public.mobility_route_snapshots (destination_accommodation_ref_id);
create index idx_route_ref_destination_transport_event_ref_id on public.mobility_route_snapshots (destination_transport_event_ref_id);

commit;
