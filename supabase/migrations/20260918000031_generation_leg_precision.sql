-- 정수 분으로 표현할 수 없는 생성 버스 시간은 정확한 분해와 함께만 허용한다.
create function timing_jeju_planner_private.valid_leg_precision(
  facts jsonb, mode text, walk integer, wait integer, ride integer,
  interchange integer, duration integer
) returns boolean language plpgsql immutable security invoker set search_path = '' as $$
declare
  p jsonb := facts #> '{generation,precision}';
  k text;
  n bigint;
  minute constant bigint := 60000000000;
  walking bigint; waiting bigint; riding bigint; changing bigint; rounding bigint;
begin
  if p is null then
    -- 기존 draft는 duration 미정일 수 있다. 완성 일정 합계는 기존 sealing에서 검사한다.
    return wait is not null and ride is not null and wait >= 0 and ride >= 0;
  end if;
  if mode is distinct from 'public_transit' or jsonb_typeof(p) is distinct from 'object'
    or facts #> '{generation,schemaVersion}' is distinct from '1'::jsonb
    or walk is null or interchange is null or duration is null
    or walk < 0 or interchange < 0 or duration < 0 or duration > 1440 then return false; end if;
  if (select count(*) from jsonb_object_keys(p)) <> 5 then return false; end if;
  foreach k in array array['walkNanos','waitNanos','rideNanos','transferNanos','roundingNanos'] loop
    if jsonb_typeof(p -> k) is distinct from 'number'
      or (p ->> k) !~ '^[0-9]+$' then return false; end if;
    n := (p ->> k)::bigint;
    if n > 1440 * minute then return false; end if;
  end loop;
  walking := (p ->> 'walkNanos')::bigint;
  waiting := (p ->> 'waitNanos')::bigint;
  riding := (p ->> 'rideNanos')::bigint;
  changing := (p ->> 'transferNanos')::bigint;
  rounding := (p ->> 'roundingNanos')::bigint;
  return rounding < minute and walking = walk * minute and changing = interchange * minute
    and wait is not distinct from (case when waiting % minute = 0 then (waiting / minute)::integer end)
    and ride is not distinct from (case when riding % minute = 0 then (riding / minute)::integer end)
    and walking + waiting + riding + changing + rounding = duration * minute;
exception when numeric_value_out_of_range or invalid_text_representation then
  return false;
end;
$$;

revoke all on function timing_jeju_planner_private.valid_leg_precision(jsonb,text,integer,integer,integer,integer,integer) from public, anon, authenticated;
grant execute on function timing_jeju_planner_private.valid_leg_precision(jsonb,text,integer,integer,integer,integer,integer) to service_role;

alter table public.trip_legs
  alter column wait_minutes drop not null,
  alter column ride_minutes drop not null,
  add constraint trip_legs_precision_check check (
    timing_jeju_planner_private.valid_leg_precision(
      facts, transport_mode::text, walk_minutes, wait_minutes, ride_minutes,
      transfer_minutes, duration_minutes
    ) is true
  );
