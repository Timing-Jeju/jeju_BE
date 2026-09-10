-- Issue #225: hash public planned identity only; migrations 015/016 remain immutable.
begin;
lock table public.mobility_route_snapshots in access exclusive mode;

-- CREATE OR REPLACE retains the function identity, owner and existing EXECUTE ACL.
-- Coordinates remain independently checked by the unchanged INSERT/sealing guards.
create or replace function timing_jeju_planner_private.planned_route_request_hash(route public.mobility_route_snapshots)
returns text language sql immutable security invoker set search_path = ''
as $$ select public.source_identity_digest(
    route.anchor_contract_version,
    route.trip_plan_id::text,
    route.schedule_version_id::text,
    route.origin_anchor_kind,
    route.origin_anchor_id::text,
    route.destination_anchor_kind,
    route.destination_anchor_id::text); $$;

-- A narrower identity can expose legacy duplicates. Never merge/delete those rows,
-- relax uniqueness, or expose their old hashes in a constraint error.
do $$
begin
  if exists (
    select 1 from public.mobility_route_snapshots snapshot
    group by timing_jeju_planner_private.planned_route_request_hash(snapshot)
    having count(*) > 1
  ) then
    raise exception using errcode = '23514', message = 'planned route hash policy requires duplicate identity audit';
  end if;
  if not exists (
    select 1 from pg_catalog.pg_trigger
    where tgrelid = 'public.mobility_route_snapshots'::pg_catalog.regclass
      and tgname = 'trg_mobility_route_planned_provenance' and tgenabled = 'O'
  ) then
    raise exception using errcode = '23514', message = 'planned route provenance protection must be enabled';
  end if;
end;
$$;

-- Only the immutability guard is suspended, under an exclusive lock and transaction.
-- All FK, CHECK, UNIQUE, RLS and source-lineage protections remain in place.
alter table public.mobility_route_snapshots disable trigger trg_mobility_route_planned_provenance;
update public.mobility_route_snapshots snapshot
set request_hash = timing_jeju_planner_private.planned_route_request_hash(snapshot)
where snapshot.request_hash is distinct from timing_jeju_planner_private.planned_route_request_hash(snapshot);
alter table public.mobility_route_snapshots enable trigger trg_mobility_route_planned_provenance;

commit;
