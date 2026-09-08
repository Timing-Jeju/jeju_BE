begin;

create schema if not exists timing_jeju_private;

revoke all on schema timing_jeju_private from public;
revoke all on schema timing_jeju_private from anon;
revoke all on schema timing_jeju_private from authenticated;
revoke all on schema timing_jeju_private from service_role;

create or replace function timing_jeju_private.trip_preferences_owner(
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

  if current_user_id is null then
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

revoke all on function timing_jeju_private.trip_preferences_owner(uuid) from public;
revoke all on function timing_jeju_private.trip_preferences_owner(uuid) from anon;
revoke all on function timing_jeju_private.trip_preferences_owner(uuid) from authenticated;
revoke all on function timing_jeju_private.trip_preferences_owner(uuid) from service_role;

grant usage on schema timing_jeju_private to authenticated;
grant execute on function timing_jeju_private.trip_preferences_owner(uuid) to authenticated;

drop policy if exists trip_preferences_owner_select on public.trip_preferences;
create policy trip_preferences_owner_select
  on public.trip_preferences
  for select
  to authenticated
  using (timing_jeju_private.trip_preferences_owner(trip_plan_id));

drop policy if exists trip_transport_modes_owner_select on public.trip_transport_modes;
create policy trip_transport_modes_owner_select
  on public.trip_transport_modes
  for select
  to authenticated
  using (timing_jeju_private.trip_preferences_owner(trip_plan_id));

commit;
