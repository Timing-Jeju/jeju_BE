-- Issue #225: immutable route provenance belongs to a single planned schedule version.
begin;
lock table public.mobility_route_snapshots in access exclusive mode;
lock table public.trip_legs in share row exclusive mode;

-- Audit IDs only: coordinates are compared in place, never copied into an audit table.
create temporary table audited_planned_routes on commit drop as
select snapshot.id as snapshot_id, trip.user_id as owner_user_id,
       leg.trip_plan_id, leg.schedule_version_id, leg.from_item_id, leg.to_item_id,
       origin_anchor.anchor_kind as origin_kind, origin_anchor.anchor_id as origin_id,
       origin_anchor.source_place_id as origin_place, origin_anchor.source_stop_id as origin_stop,
       destination_anchor.anchor_kind as destination_kind, destination_anchor.anchor_id as destination_id,
       destination_anchor.source_place_id as destination_place, destination_anchor.source_stop_id as destination_stop
from public.mobility_route_snapshots snapshot
join public.trip_legs leg on leg.mobility_route_snapshot_id = snapshot.id
join public.trip_plans trip on trip.id = leg.trip_plan_id
cross join lateral (
  select anchor.* from timing_jeju_planner_private.resolve_planned_item_anchor(
    leg.from_item_id, leg.schedule_version_id, leg.trip_plan_id) anchor
  where public.ST_Equals(snapshot.origin_location::public.geometry, anchor.location::public.geometry)
  union all
  select anchor.* from timing_jeju_planner_private.resolve_planned_anchor('stop', leg.origin_stop_id, leg.trip_plan_id) anchor
  where public.ST_Equals(snapshot.origin_location::public.geometry, anchor.location::public.geometry)
) origin_anchor
cross join lateral (
  select anchor.* from timing_jeju_planner_private.resolve_planned_item_anchor(
    leg.to_item_id, leg.schedule_version_id, leg.trip_plan_id) anchor
  where public.ST_Equals(snapshot.destination_location::public.geometry, anchor.location::public.geometry)
  union all
  select anchor.* from timing_jeju_planner_private.resolve_planned_anchor('stop', leg.destination_stop_id, leg.trip_plan_id) anchor
  where public.ST_Equals(snapshot.destination_location::public.geometry, anchor.location::public.geometry)
) destination_anchor
where (select count(*) from public.trip_legs reference where reference.mobility_route_snapshot_id=snapshot.id) = 1
  and snapshot.source_provider = 'fixture' and snapshot.raw_payload = '{}'::jsonb
  and jsonb_typeof(snapshot.route_summary) = 'object'
  and not exists (
    select 1 from jsonb_each(case when jsonb_typeof(snapshot.route_summary)='object' then snapshot.route_summary else '{}'::jsonb end) entry
    where case when entry.key = 'routeNo' then
      jsonb_typeof(entry.value) <> 'string' or (entry.value #>> '{}') !~ '^[0-9A-Za-z-]{1,20}$'
    when entry.key in ('walkMinutes','waitMinutes','rideMinutes','transferMinutes','transfers') then
      jsonb_typeof(entry.value) <> 'number' or entry.value::text !~ '^[0-9]{1,6}$'
    else true end
  )
  and snapshot.transport_mode=leg.transport_mode and snapshot.departure_at=leg.planned_departure_at
  and snapshot.duration_minutes=leg.duration_minutes
  and snapshot.distance_meters is not distinct from leg.distance_meters
  and snapshot.estimated_fare is not distinct from leg.estimated_fare;

do $$
begin
  if exists (
    select 1 from public.mobility_route_snapshots snapshot
    where (select count(*) from audited_planned_routes audit where audit.snapshot_id=snapshot.id) <> 1
  ) then
    raise exception using errcode = '23514', message = 'legacy route snapshots require provenance audit';
  end if;
end;
$$;

alter table public.mobility_route_snapshots
  add column owner_user_id uuid,
  add column trip_plan_id uuid,
  add column schedule_version_id uuid,
  add column origin_item_id uuid,
  add column destination_item_id uuid,
  add column anchor_contract_version text,
  add column origin_anchor_kind text,
  add column origin_anchor_id uuid,
  add column destination_anchor_kind text,
  add column destination_anchor_id uuid,
  add column origin_source_place_id uuid references public.tour_places(id),
  add column destination_source_place_id uuid references public.tour_places(id),
  add column origin_source_stop_id uuid references public.bus_stops(id),
  add column destination_source_stop_id uuid references public.bus_stops(id),
  add constraint fk_route_planned_owner foreign key (trip_plan_id, owner_user_id)
    references public.trip_plans(id, user_id),
  add constraint fk_route_planned_version foreign key (schedule_version_id, trip_plan_id)
    references public.trip_schedule_versions(id, trip_plan_id),
  add constraint fk_route_planned_origin_item foreign key (origin_item_id, schedule_version_id, trip_plan_id)
    references public.trip_items(id, schedule_version_id, trip_plan_id),
  add constraint fk_route_planned_destination_item foreign key (destination_item_id, schedule_version_id, trip_plan_id)
    references public.trip_items(id, schedule_version_id, trip_plan_id);

alter table public.mobility_route_snapshots
  add column origin_place_ref_id uuid generated always as
    (case when origin_anchor_kind = 'place' then origin_anchor_id end) stored
    references public.tour_places(id);

alter table public.mobility_route_snapshots
  add column origin_stop_ref_id uuid generated always as
    (case when origin_anchor_kind = 'stop' then origin_anchor_id end) stored
    references public.bus_stops(id);

alter table public.mobility_route_snapshots
  add column origin_accommodation_ref_id uuid generated always as
    (case when origin_anchor_kind = 'accommodation' then origin_anchor_id end) stored
    references public.trip_accommodations(id);

alter table public.mobility_route_snapshots
  add column origin_transport_event_ref_id uuid generated always as
    (case when origin_anchor_kind = 'transport_event' then origin_anchor_id end) stored
    references public.trip_transport_events(id);

alter table public.mobility_route_snapshots
  add column destination_place_ref_id uuid generated always as
    (case when destination_anchor_kind = 'place' then destination_anchor_id end) stored
    references public.tour_places(id);

alter table public.mobility_route_snapshots
  add column destination_stop_ref_id uuid generated always as
    (case when destination_anchor_kind = 'stop' then destination_anchor_id end) stored
    references public.bus_stops(id);

alter table public.mobility_route_snapshots
  add column destination_accommodation_ref_id uuid generated always as
    (case when destination_anchor_kind = 'accommodation' then destination_anchor_id end) stored
    references public.trip_accommodations(id);

alter table public.mobility_route_snapshots
  add column destination_transport_event_ref_id uuid generated always as
    (case when destination_anchor_kind = 'transport_event' then destination_anchor_id end) stored
    references public.trip_transport_events(id);


create function timing_jeju_planner_private.planned_route_request_hash(route public.mobility_route_snapshots)
returns text language sql immutable security invoker set search_path = ''
as $$ select public.source_identity_digest(
    route.anchor_contract_version, route.owner_user_id::text, route.trip_plan_id::text,
    route.schedule_version_id::text, route.origin_item_id::text, route.destination_item_id::text,
    route.origin_anchor_kind, route.origin_anchor_id::text, route.destination_anchor_kind, route.destination_anchor_id::text,
    route.origin_source_place_id::text, route.destination_source_place_id::text,
    route.origin_source_stop_id::text, route.destination_source_stop_id::text,
    public.ST_AsEWKT(route.origin_location::public.geometry), public.ST_AsEWKT(route.destination_location::public.geometry),
    route.transport_mode, extract(epoch from route.departure_at)::text, route.source_provider, route.source_operation); $$;
revoke all on function timing_jeju_planner_private.planned_route_request_hash(public.mobility_route_snapshots)
  from public, anon, authenticated, service_role;
grant execute on function timing_jeju_planner_private.planned_route_request_hash(public.mobility_route_snapshots) to service_role;

update public.mobility_route_snapshots snapshot set
  owner_user_id=audit.owner_user_id, trip_plan_id=audit.trip_plan_id,
  schedule_version_id=audit.schedule_version_id, origin_item_id=audit.from_item_id,
  destination_item_id=audit.to_item_id, anchor_contract_version='planned-anchor.v1',
  origin_anchor_kind=audit.origin_kind, origin_anchor_id=audit.origin_id,
  destination_anchor_kind=audit.destination_kind, destination_anchor_id=audit.destination_id,
  origin_source_place_id=audit.origin_place, destination_source_place_id=audit.destination_place,
  origin_source_stop_id=audit.origin_stop, destination_source_stop_id=audit.destination_stop
from audited_planned_routes audit where audit.snapshot_id=snapshot.id;
update public.mobility_route_snapshots snapshot
set request_hash=timing_jeju_planner_private.planned_route_request_hash(snapshot);

alter table public.mobility_route_snapshots
  alter column owner_user_id set not null,
  alter column trip_plan_id set not null,
  alter column schedule_version_id set not null,
  alter column origin_item_id set not null,
  alter column destination_item_id set not null,
  alter column anchor_contract_version set not null,
  alter column origin_anchor_kind set not null,
  alter column origin_anchor_id set not null,
  alter column destination_anchor_kind set not null,
  alter column destination_anchor_id set not null,
  add constraint ck_route_planned_anchor_contract check (anchor_contract_version = 'planned-anchor.v1'),
  add constraint ck_route_planned_anchor_kinds check (
    origin_anchor_kind in ('place', 'stop', 'accommodation', 'transport_event')
    and destination_anchor_kind in ('place', 'stop', 'accommodation', 'transport_event')),
  add constraint ck_route_planned_source_identity check (
    num_nonnulls(origin_source_place_id, origin_source_stop_id) = 1
    and num_nonnulls(destination_source_place_id, destination_source_stop_id) = 1),
  add constraint ck_route_planned_hash check (request_hash ~ '^[0-9a-f]{64}$');
create index idx_mobility_planned_version_lookup on public.mobility_route_snapshots
  (trip_plan_id,schedule_version_id,origin_item_id,destination_item_id,transport_mode,expires_at);

create function timing_jeju_planner_private.resolve_route_endpoint(
  target_trip_id uuid, target_version_id uuid, target_origin_item_id uuid,
  target_destination_item_id uuid, target_kind text, target_anchor_id uuid, is_origin boolean
)
returns table (anchor_kind text, anchor_id uuid, source_place_id uuid, source_stop_id uuid, location public.geography)
language sql stable security invoker set search_path = ''
as $$
  select anchor.*
  from timing_jeju_planner_private.resolve_planned_item_anchor(
    case when is_origin then target_origin_item_id else target_destination_item_id end,
    target_version_id, target_trip_id
  ) anchor
  where anchor.anchor_kind = target_kind and anchor.anchor_id = target_anchor_id
  union all
  select anchor.*
  from timing_jeju_planner_private.resolve_planned_anchor(target_kind, target_anchor_id, target_trip_id) anchor
  where target_kind = 'stop' and exists (
    select 1 from public.trip_legs leg
    where leg.trip_plan_id = target_trip_id and leg.schedule_version_id = target_version_id
      and leg.from_item_id = target_origin_item_id and leg.to_item_id = target_destination_item_id
      and (case when is_origin then leg.origin_stop_id else leg.destination_stop_id end) = target_anchor_id
  );
$$;

create function timing_jeju_planner_private.validate_planned_route_snapshot()
returns trigger language plpgsql security invoker set search_path = ''
as $$
declare
  origin_anchor record;
  destination_anchor record;
  expected_owner uuid;
  expected_hash text;
  compared_new jsonb;
  compared_old jsonb;
  origin_end timestamptz;
  destination_start timestamptz;
begin
  if tg_op = 'UPDATE' then
    -- Generated FK columns are recomputed after BEFORE triggers; compare their immutable inputs.
    compared_new := to_jsonb(new) - array['origin_place_ref_id', 'origin_stop_ref_id', 'origin_accommodation_ref_id', 'origin_transport_event_ref_id', 'destination_place_ref_id', 'destination_stop_ref_id', 'destination_accommodation_ref_id', 'destination_transport_event_ref_id'];
    compared_old := to_jsonb(old) - array['origin_place_ref_id', 'origin_stop_ref_id', 'origin_accommodation_ref_id', 'origin_transport_event_ref_id', 'destination_place_ref_id', 'destination_stop_ref_id', 'destination_accommodation_ref_id', 'destination_transport_event_ref_id'];
    if compared_new = compared_old then return new; end if;
    -- Raw source retention may detach its nullable FK, never the import ledger or planned identity.
    if old.source_snapshot_id is not null and new.source_snapshot_id is null
       and (compared_new - 'source_snapshot_id') = (compared_old - 'source_snapshot_id') then
      return new;
    end if;
    raise exception using errcode = '23514', message = 'planned route snapshots are immutable';
  end if;
  -- No provider route metric has an approved persistence contract yet (#216).
  if new.source_provider is distinct from 'fixture' then
    raise exception using errcode = '23514', message = 'planned route storage approval is required';
  end if;
  if new.raw_payload is distinct from '{}'::jsonb
     or jsonb_typeof(new.route_summary) is distinct from 'object' then
    raise exception using errcode = '23514', message = 'planned route payload is not approved';
  end if;
  if exists (
    select 1 from jsonb_each(new.route_summary) entry
    where case when entry.key = 'routeNo' then
      jsonb_typeof(entry.value) <> 'string' or (entry.value #>> '{}') !~ '^[0-9A-Za-z-]{1,20}$'
    when entry.key in ('walkMinutes','waitMinutes','rideMinutes','transferMinutes','transfers') then
      jsonb_typeof(entry.value) <> 'number' or entry.value::text !~ '^[0-9]{1,6}$'
    else true end
  ) then
    raise exception using errcode = '23514', message = 'planned route payload is not approved';
  end if;
  if new.origin_item_id = new.destination_item_id then
    raise exception using errcode = '23514', message = 'planned route endpoints must be distinct';
  end if;
  select trip.user_id into expected_owner from public.trip_plans trip
  join public.trip_schedule_versions version on version.trip_plan_id = trip.id
  where trip.id = new.trip_plan_id and version.id = new.schedule_version_id;
  if expected_owner is null or (new.owner_user_id is not null and new.owner_user_id <> expected_owner) then
    raise exception using errcode = '23514', message = 'planned route owner or version is invalid';
  end if;
  select * into origin_anchor from timing_jeju_planner_private.resolve_route_endpoint(
    new.trip_plan_id, new.schedule_version_id, new.origin_item_id, new.destination_item_id,
    new.origin_anchor_kind, new.origin_anchor_id, true);
  if not found then
    raise exception using errcode = '23514', message = 'planned route origin anchor is invalid';
  end if;
  select * into destination_anchor from timing_jeju_planner_private.resolve_route_endpoint(
    new.trip_plan_id, new.schedule_version_id, new.origin_item_id, new.destination_item_id,
    new.destination_anchor_kind, new.destination_anchor_id, false);
  if not found then
    raise exception using errcode = '23514', message = 'planned route destination anchor is invalid';
  end if;
  if (new.origin_location is not null and not public.ST_Equals(new.origin_location::public.geometry, origin_anchor.location::public.geometry))
     or (new.destination_location is not null and not public.ST_Equals(new.destination_location::public.geometry, destination_anchor.location::public.geometry))
     or (new.origin_source_place_id is not null and new.origin_source_place_id is distinct from origin_anchor.source_place_id)
     or (new.destination_source_place_id is not null and new.destination_source_place_id is distinct from destination_anchor.source_place_id)
     or (new.origin_source_stop_id is not null and new.origin_source_stop_id is distinct from origin_anchor.source_stop_id)
     or (new.destination_source_stop_id is not null and new.destination_source_stop_id is distinct from destination_anchor.source_stop_id)
     or (new.anchor_contract_version is not null and new.anchor_contract_version <> 'planned-anchor.v1') then
    raise exception using errcode = '23514', message = 'planned route public provenance does not match';
  end if;
  select item.planned_end_at into origin_end from public.trip_items item where item.id = new.origin_item_id;
  select item.planned_start_at into destination_start from public.trip_items item where item.id = new.destination_item_id;
  new.departure_at := coalesce(new.departure_at, origin_end);
  if origin_end is null or destination_start is null or new.departure_at < origin_end
     or new.departure_at >= destination_start then
    raise exception using errcode = '23514', message = 'planned route departure is outside item boundaries';
  end if;
  new.owner_user_id := expected_owner;
  new.anchor_contract_version := 'planned-anchor.v1';
  new.origin_source_place_id := origin_anchor.source_place_id;
  new.destination_source_place_id := destination_anchor.source_place_id;
  new.origin_source_stop_id := origin_anchor.source_stop_id;
  new.destination_source_stop_id := destination_anchor.source_stop_id;
  new.origin_location := origin_anchor.location;
  new.destination_location := destination_anchor.location;
  expected_hash := timing_jeju_planner_private.planned_route_request_hash(new);
  if new.request_hash is not null and new.request_hash <> expected_hash then
    raise exception using errcode = '23514', message = 'planned route request hash does not match';
  end if;
  new.request_hash := expected_hash;
  return new;
end;
$$;

revoke all on function timing_jeju_planner_private.resolve_route_endpoint(uuid,uuid,uuid,uuid,text,uuid,boolean)
  from public, anon, authenticated, service_role;
grant execute on function timing_jeju_planner_private.resolve_route_endpoint(uuid,uuid,uuid,uuid,text,uuid,boolean)
  to service_role;
revoke all on function timing_jeju_planner_private.validate_planned_route_snapshot()
  from public, anon, authenticated, service_role;
create trigger trg_mobility_route_planned_provenance
before insert or update on public.mobility_route_snapshots
for each row execute function timing_jeju_planner_private.validate_planned_route_snapshot();


create function timing_jeju_planner_private.clone_planned_route_snapshot(
  source_snapshot uuid, target_trip uuid, target_version uuid,
  target_origin_item uuid, target_destination_item uuid, target_departure timestamptz,
  target_time timestamptz
)
returns uuid language plpgsql security invoker set search_path = ''
as $$
declare
  source public.mobility_route_snapshots%rowtype;
  cloned_id uuid;
begin
  select * into source from public.mobility_route_snapshots snapshot
  where snapshot.id = source_snapshot and snapshot.trip_plan_id = target_trip
    and snapshot.observed_at <= target_time and snapshot.expires_at > target_time
    and snapshot.departure_at = target_departure;
  if not found then return null; end if;
  insert into public.mobility_route_snapshots
    (trip_plan_id,schedule_version_id,origin_item_id,destination_item_id,
     origin_anchor_kind,origin_anchor_id,destination_anchor_kind,destination_anchor_id,
     origin_location,destination_location,transport_mode,departure_at,distance_meters,
     duration_minutes,estimated_fare,source_provider,source_operation,route_summary,
     observed_at,expires_at,raw_payload,source_snapshot_id,import_run_id)
  values
    (target_trip,target_version,target_origin_item,target_destination_item,
     source.origin_anchor_kind,source.origin_anchor_id,source.destination_anchor_kind,source.destination_anchor_id,
     source.origin_location,source.destination_location,source.transport_mode,target_departure,source.distance_meters,
     source.duration_minutes,source.estimated_fare,source.source_provider,source.source_operation,source.route_summary,
     source.observed_at,source.expires_at,source.raw_payload,source.source_snapshot_id,source.import_run_id)
  returning id into cloned_id;
  return cloned_id;
end;
$$;
revoke all on function timing_jeju_planner_private.clone_planned_route_snapshot(uuid,uuid,uuid,uuid,uuid,timestamptz,timestamptz)
  from public, anon, authenticated, service_role;
grant execute on function timing_jeju_planner_private.clone_planned_route_snapshot(uuid,uuid,uuid,uuid,uuid,timestamptz,timestamptz)
  to service_role;

create function timing_jeju_planner_private.validate_planned_route_leg()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
  if new.mobility_route_snapshot_id is null then return new; end if;
  if not exists (
    select 1 from public.mobility_route_snapshots snapshot
    where snapshot.id = new.mobility_route_snapshot_id
      and snapshot.trip_plan_id = new.trip_plan_id and snapshot.schedule_version_id = new.schedule_version_id
      and snapshot.origin_item_id = new.from_item_id and snapshot.destination_item_id = new.to_item_id
      and snapshot.transport_mode = new.transport_mode and snapshot.departure_at = new.planned_departure_at
      and (snapshot.origin_anchor_kind <> 'stop' or snapshot.origin_anchor_id is not distinct from new.origin_stop_id)
      and (snapshot.destination_anchor_kind <> 'stop' or snapshot.destination_anchor_id is not distinct from new.destination_stop_id)
      and snapshot.duration_minutes = new.duration_minutes
      and snapshot.distance_meters is not distinct from new.distance_meters
      and snapshot.estimated_fare is not distinct from new.estimated_fare
  ) then
    raise exception using errcode = '23514', message = 'planned route leg lineage does not match';
  end if;
  return new;
end;
$$;
revoke all on function timing_jeju_planner_private.validate_planned_route_leg()
  from public, anon, authenticated, service_role;
create trigger trg_trip_legs_planned_route
before insert or update on public.trip_legs
for each row execute function timing_jeju_planner_private.validate_planned_route_leg();

-- Revalidate mutable draft anchors before sealing a version.
create or replace function public.assert_schedule_version_sealable(
  target_schedule_version_id uuid, target_trip_plan_id uuid
)
returns void language plpgsql security invoker set search_path = ''
as $$
begin
  perform public.assert_schedule_version_core_sealable(target_schedule_version_id, target_trip_plan_id);
  perform public.assert_schedule_item_required_references(target_schedule_version_id, target_trip_plan_id);
  if exists (
    select 1 from public.trip_legs leg
    join public.mobility_route_snapshots snapshot on snapshot.id = leg.mobility_route_snapshot_id
    where leg.schedule_version_id = target_schedule_version_id and leg.trip_plan_id = target_trip_plan_id
      and not exists (
        select 1
        from timing_jeju_planner_private.resolve_route_endpoint(
          leg.trip_plan_id, leg.schedule_version_id, leg.from_item_id, leg.to_item_id,
          snapshot.origin_anchor_kind, snapshot.origin_anchor_id, true) origin
        cross join timing_jeju_planner_private.resolve_route_endpoint(
          leg.trip_plan_id, leg.schedule_version_id, leg.from_item_id, leg.to_item_id,
          snapshot.destination_anchor_kind, snapshot.destination_anchor_id, false) destination
        where snapshot.trip_plan_id = leg.trip_plan_id
          and snapshot.schedule_version_id = leg.schedule_version_id
          and snapshot.origin_item_id = leg.from_item_id and snapshot.destination_item_id = leg.to_item_id
          and snapshot.origin_source_place_id is not distinct from origin.source_place_id
          and snapshot.origin_source_stop_id is not distinct from origin.source_stop_id
          and snapshot.destination_source_place_id is not distinct from destination.source_place_id
          and snapshot.destination_source_stop_id is not distinct from destination.source_stop_id
          and public.ST_Equals(snapshot.origin_location::public.geometry, origin.location::public.geometry)
          and public.ST_Equals(snapshot.destination_location::public.geometry, destination.location::public.geometry)
      )
  ) then
    raise exception using errcode = '23514', message = 'planned route anchor lineage does not match';
  end if;
end;
$$;

commit;
