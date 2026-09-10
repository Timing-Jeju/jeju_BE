-- Issue #223: an opaque revision request hash is not a proven non-location command hash.
begin;
lock table public.schedule_revision_runs, public.compute_run_inputs in access exclusive mode;

do $$
begin
  if timing_jeju_planner_private.user_location_guard_purge_revision() <> '20260918000017' then
    raise exception using errcode = '23514', message = 'location cutover predecessor mismatch';
  end if;
end;
$$;

alter function timing_jeju_planner_private.user_location_residue_counts()
  rename to user_location_residue_counts_v17;
create function timing_jeju_planner_private.user_location_residue_counts()
returns table (object_name text, residue_count bigint)
language sql stable security invoker set search_path = ''
as $$
  select * from timing_jeju_planner_private.user_location_residue_counts_v17()
  union all
  select 'unclassified_schedule_revision_request_hashes', count(*)
  from public.schedule_revision_runs;
$$;
revoke all on function timing_jeju_planner_private.user_location_residue_counts()
  from public, anon, authenticated, service_role;

do $$
begin
  if exists (select 1 from timing_jeju_planner_private.user_location_residue_counts() where residue_count <> 0) then
    raise exception using errcode = '23514', message = 'user location residue requires audit';
  end if;
end;
$$;

create or replace function timing_jeju_planner_private.user_location_guard_purge_revision()
returns text language sql immutable security invoker set search_path = ''
as $$ select '20260918000018'::text; $$;
revoke all on function timing_jeju_planner_private.user_location_guard_purge_revision()
  from public, anon, authenticated, service_role;
commit;
