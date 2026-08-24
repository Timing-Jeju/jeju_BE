alter table public.trip_plans
  add column timezone text not null default 'Asia/Seoul',
  add constraint trip_plans_timezone_check check (timezone = 'Asia/Seoul');

revoke all on table
  public.trip_plans,
  public.trip_transport_modes,
  public.trip_days
from anon, authenticated;

create policy trip_plans_owner_insert
  on public.trip_plans for insert to authenticated
  with check (
    user_id = (select auth.uid())
    and session_id is null
  );

create policy trip_transport_modes_owner_insert
  on public.trip_transport_modes for insert to authenticated
  with check ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_days_owner_insert
  on public.trip_days for insert to authenticated
  with check ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_plans_owner_update
  on public.trip_plans for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and session_id is null);

create policy trip_plans_owner_delete
  on public.trip_plans for delete to authenticated
  using (user_id = (select auth.uid()));

create policy trip_transport_modes_owner_update
  on public.trip_transport_modes for update to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)))
  with check ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_transport_modes_owner_delete
  on public.trip_transport_modes for delete to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_days_owner_update
  on public.trip_days for update to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)))
  with check ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_days_owner_delete
  on public.trip_days for delete to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));
