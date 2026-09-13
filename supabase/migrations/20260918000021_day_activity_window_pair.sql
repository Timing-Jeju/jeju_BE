begin;

-- 기존 사용자 값을 보정하거나 삭제하지 않는다. 불완전한 값은 적용 전에 별도 정정해야 한다.
lock table public.trip_days in access exclusive mode;
do $$
begin
  if exists (
    select 1 from public.trip_days
    where (start_time is null) <> (end_time is null)
       or (start_time is not null and (
         start_time >= end_time
         or start_time >= time '24:00'
         or end_time >= time '24:00'
         or extract(second from start_time) <> 0
         or extract(second from end_time) <> 0
       ))
  ) then
    raise exception 'Day activity windows require explicit legacy data correction before migration';
  end if;
end;
$$;

alter table public.trip_days
  add constraint trip_days_activity_window_pair_check check (
    (start_time is null and end_time is null)
    or (start_time is not null and end_time is not null
        and start_time < end_time
        and start_time < time '24:00' and end_time < time '24:00'
        and extract(second from start_time) = 0
        and extract(second from end_time) = 0)
  );

commit;
