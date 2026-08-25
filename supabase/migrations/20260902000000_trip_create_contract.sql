alter table public.trip_plans
  add column timezone text not null default 'Asia/Seoul',
  add constraint trip_plans_timezone_check check (timezone = 'Asia/Seoul');

revoke all on table
  public.trip_plans,
  public.trip_transport_modes,
  public.trip_days
from anon, authenticated;

grant select, insert, update, delete on table
  public.trip_plans,
  public.trip_transport_modes,
  public.trip_days
to service_role;

revoke truncate, references, trigger on table
  public.trip_plans,
  public.trip_transport_modes,
  public.trip_days
from service_role;
