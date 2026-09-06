begin;

create schema if not exists timing_jeju_private;

revoke all on schema timing_jeju_private from public;
revoke all on schema timing_jeju_private from anon;
revoke all on schema timing_jeju_private from authenticated;
revoke all on schema timing_jeju_private from service_role;

-- SECURITY DEFINER is required here because authenticated intentionally has neither
-- auth schema USAGE nor direct trip_plans SELECT. The boolean-only lookup breaks the
-- trip_plans RLS recursion while retaining the caller identity from auth.uid().
create or replace function timing_jeju_private.owns_trip_plan(
  target_trip_plan_id uuid
)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  current_user_id uuid;
begin
  begin
    current_user_id := (select auth.uid());
  exception
    when invalid_text_representation then
      return false;
  end;

  if current_user_id is null or target_trip_plan_id is null then
    return false;
  end if;

  return exists (
    select 1
    from public.trip_plans trip_plan
    where trip_plan.id = target_trip_plan_id
      and trip_plan.user_id = current_user_id
  );
end;
$$;

revoke all on function timing_jeju_private.owns_trip_plan(uuid) from public;
revoke all on function timing_jeju_private.owns_trip_plan(uuid) from anon;
revoke all on function timing_jeju_private.owns_trip_plan(uuid) from authenticated;
revoke all on function timing_jeju_private.owns_trip_plan(uuid) from service_role;

grant usage on schema timing_jeju_private to authenticated;
grant execute on function timing_jeju_private.owns_trip_plan(uuid) to authenticated;

drop policy if exists trip_preferences_owner_select on public.trip_preferences;
create policy trip_preferences_owner_select
  on public.trip_preferences for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_transport_modes_owner_select on public.trip_transport_modes;
create policy trip_transport_modes_owner_select
  on public.trip_transport_modes for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_transport_events_owner_select on public.trip_transport_events;
create policy trip_transport_events_owner_select
  on public.trip_transport_events for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_accommodations_owner_select on public.trip_accommodations;
create policy trip_accommodations_owner_select
  on public.trip_accommodations for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_days_owner_select on public.trip_days;
create policy trip_days_owner_select
  on public.trip_days for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_schedule_versions_owner_select on public.trip_schedule_versions;
create policy trip_schedule_versions_owner_select
  on public.trip_schedule_versions for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_items_owner_select on public.trip_items;
create policy trip_items_owner_select
  on public.trip_items for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists itinerary_generation_runs_owner_select
  on public.itinerary_generation_runs;
create policy itinerary_generation_runs_owner_select
  on public.itinerary_generation_runs for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists itinerary_generation_candidates_owner_select
  on public.itinerary_generation_candidates;
create policy itinerary_generation_candidates_owner_select
  on public.itinerary_generation_candidates for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_legs_owner_select on public.trip_legs;
create policy trip_legs_owner_select
  on public.trip_legs for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_item_progress_owner_select on public.trip_item_progress;
create policy trip_item_progress_owner_select
  on public.trip_item_progress for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_execution_events_owner_select on public.trip_execution_events;
create policy trip_execution_events_owner_select
  on public.trip_execution_events for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists compute_runs_owner_select on public.compute_runs;
create policy compute_runs_owner_select
  on public.compute_runs for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists risk_events_owner_select on public.risk_events;
create policy risk_events_owner_select
  on public.risk_events for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists trip_weather_impacts_owner_select on public.trip_weather_impacts;
create policy trip_weather_impacts_owner_select
  on public.trip_weather_impacts for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists recommendation_candidates_owner_select
  on public.recommendation_candidates;
create policy recommendation_candidates_owner_select
  on public.recommendation_candidates for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists recovery_options_owner_select on public.recovery_options;
create policy recovery_options_owner_select
  on public.recovery_options for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop policy if exists recovery_option_changes_owner_select
  on public.recovery_option_changes;
create policy recovery_option_changes_owner_select
  on public.recovery_option_changes for select to authenticated
  using (
    exists (
      select 1
      from public.recovery_options recovery_option
      where recovery_option.id = recovery_option_changes.recovery_option_id
        and timing_jeju_private.owns_trip_plan(recovery_option.trip_plan_id)
    )
  );

drop policy if exists live_state_snapshots_owner_select on public.live_state_snapshots;
create policy live_state_snapshots_owner_select
  on public.live_state_snapshots for select to authenticated
  using (timing_jeju_private.owns_trip_plan(trip_plan_id));

drop function if exists timing_jeju_private.trip_preferences_owner(uuid);
drop function if exists public.owns_trip_plan(uuid);

commit;
