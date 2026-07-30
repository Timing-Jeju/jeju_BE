-- Docker smoke의 별도 DB에서만 실행하는 실제 2세션 동시성 계약이다.
-- 운영 migration에는 dblink를 추가하지 않으며 이 DB는 검사 직후 삭제한다.

set statement_timeout = '30s';

create extension if not exists dblink;
create schema concurrency_contract;

create table concurrency_contract.connection_pids (
  scenario text not null,
  connection_role text not null,
  backend_pid integer not null,
  primary key (scenario, connection_role)
);

create function concurrency_contract.assert_connection_is_blocked(
  target_scenario text,
  blocker_role text,
  waiter_role text,
  waiter_connection text
)
returns void
language plpgsql
as $$
declare
  blocker_pid integer;
  waiter_pid integer;
  attempt integer;
begin
  select p.backend_pid
    into blocker_pid
  from concurrency_contract.connection_pids p
  where p.scenario = target_scenario
    and p.connection_role = blocker_role;

  select p.backend_pid
    into waiter_pid
  from concurrency_contract.connection_pids p
  where p.scenario = target_scenario
    and p.connection_role = waiter_role;

  if blocker_pid is null or waiter_pid is null then
    raise exception '% connection pid is missing', target_scenario;
  end if;

  for attempt in 1..100 loop
    if blocker_pid = any(pg_catalog.pg_blocking_pids(waiter_pid)) then
      return;
    end if;

    if public.dblink_is_busy(waiter_connection) = 0 then
      raise exception '% waiter completed before observing the expected lock',
        target_scenario;
    end if;

    perform pg_catalog.pg_sleep(0.05);
  end loop;

  raise exception '% waiter did not block behind the expected transaction',
    target_scenario;
end;
$$;

-- 체크포인트 갱신 결과는 SQLSTATE만 반환한다. 비밀정보나 원문 오류 메시지는
-- 테스트 출력에 포함하지 않는다.
create function concurrency_contract.try_checkpoint_advance(writer_name text)
returns text
language plpgsql
as $$
begin
  perform public.advance_data_import_checkpoint(
    'KTO',
    'TourAPI',
    'areaBasedSyncList2',
    'concurrency:checkpoint',
    0,
    pg_catalog.jsonb_build_object('writer', writer_name),
    '2026-07-30 00:00:00+00'::timestamptz,
    'fc100000-0000-0000-0000-000000000001'::uuid
  );
  return 'OK';
exception when others then
  return sqlstate;
end;
$$;

insert into public.data_import_runs (
  id,
  source_kind,
  source_name,
  source_operation,
  data_version,
  status,
  started_at,
  finished_at,
  source_provider,
  source_service,
  scope_key
) values (
  'fc100000-0000-0000-0000-000000000001',
  'tour_api',
  'database-concurrency-contract',
  'areaBasedSyncList2',
  'contract-v1',
  'succeeded',
  '2026-07-30 00:00:00+00',
  '2026-07-30 00:01:00+00',
  'KTO',
  'TourAPI',
  'concurrency:checkpoint'
);

insert into public.data_import_checkpoints (
  id,
  source_provider,
  source_service,
  source_operation,
  scope_key,
  checkpoint,
  version
) values (
  'fc110000-0000-0000-0000-000000000001',
  'KTO',
  'TourAPI',
  'areaBasedSyncList2',
  'concurrency:checkpoint',
  '{"writer":"initial"}'::jsonb,
  0
);

select public.dblink_connect(
  'checkpoint_a',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-checkpoint-a'
  )
);
select public.dblink_connect(
  'checkpoint_b',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-checkpoint-b'
  )
);

insert into concurrency_contract.connection_pids
select 'checkpoint', 'a', remote.backend_pid
from public.dblink('checkpoint_a', 'select pg_backend_pid()')
  as remote(backend_pid integer);

insert into concurrency_contract.connection_pids
select 'checkpoint', 'b', remote.backend_pid
from public.dblink('checkpoint_b', 'select pg_backend_pid()')
  as remote(backend_pid integer);

select public.dblink_exec('checkpoint_a', 'begin');

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink(
    'checkpoint_a',
    'select concurrency_contract.try_checkpoint_advance(''A'')'
  ) as remote(result_code text);

  if result_code <> 'OK' then
    raise exception 'checkpoint writer A failed: %', result_code;
  end if;
end;
$$;

do $$
begin
  if public.dblink_send_query(
       'checkpoint_b',
       'select concurrency_contract.try_checkpoint_advance(''B'')'
     ) <> 1 then
    raise exception 'checkpoint writer B query was not dispatched';
  end if;
end;
$$;

select concurrency_contract.assert_connection_is_blocked(
  'checkpoint', 'a', 'b', 'checkpoint_b'
);

select public.dblink_exec('checkpoint_a', 'commit');

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink_get_result('checkpoint_b') as remote(result_code text);

  if result_code <> '40001' then
    raise exception 'stale checkpoint writer must return 40001, got %', result_code;
  end if;
end;
$$;

do $$
declare
  final_version bigint;
  final_writer text;
  final_run_id uuid;
begin
  select checkpoint.version,
         checkpoint.checkpoint ->> 'writer',
         checkpoint.last_succeeded_run_id
    into final_version, final_writer, final_run_id
  from public.data_import_checkpoints checkpoint
  where checkpoint.id = 'fc110000-0000-0000-0000-000000000001';

  if final_version <> 1
     or final_writer <> 'A'
     or final_run_id <> 'fc100000-0000-0000-0000-000000000001'::uuid then
    raise exception 'checkpoint CAS final state is inconsistent';
  end if;
end;
$$;

select public.dblink_disconnect('checkpoint_a');
select public.dblink_disconnect('checkpoint_b');

-- 일정 시나리오: A가 draft 일정과 item을 만든 뒤 같은 trip_plan mutex를
-- 보유한다. B의 Day 수정은 실제로 대기하고, A가 candidate를 커밋한 뒤
-- 최신 상태를 다시 읽어 P0001로 거부되어야 한다.
create function concurrency_contract.prepare_schedule_candidate()
returns text
language plpgsql
as $$
begin
  insert into public.trip_schedule_versions (
    id, trip_plan_id, version_no, status, source_type
  ) values (
    'fc230000-0000-0000-0000-000000000001',
    'fc210000-0000-0000-0000-000000000001',
    1,
    'draft',
    'initial'
  );

  insert into public.trip_items (
    id,
    trip_plan_id,
    trip_day_id,
    schedule_version_id,
    sequence_no,
    item_type,
    title,
    planned_start_at,
    planned_end_at,
    stay_minutes,
    source,
    facts
  ) values (
    'fc240000-0000-0000-0000-000000000001',
    'fc210000-0000-0000-0000-000000000001',
    'fc220000-0000-0000-0000-000000000001',
    'fc230000-0000-0000-0000-000000000001',
    1,
    'custom',
    '동시성 계약 일정',
    '2026-08-20 09:00:00+09',
    '2026-08-20 10:00:00+09',
    60,
    'system',
    '{"location":{"lat":33.45,"lng":126.55}}'::jsonb
  );

  return 'OK';
end;
$$;

create function concurrency_contract.seal_schedule_candidate()
returns text
language plpgsql
as $$
begin
  update public.trip_schedule_versions
  set status = 'candidate'
  where id = 'fc230000-0000-0000-0000-000000000001';
  return 'OK';
end;
$$;

create function concurrency_contract.try_trip_day_update()
returns text
language plpgsql
as $$
begin
  update public.trip_days
  set start_time = '08:30'
  where id = 'fc220000-0000-0000-0000-000000000001';
  return 'OK';
exception when others then
  return sqlstate;
end;
$$;

insert into public.app_sessions (id, public_token)
values (
  'fc200000-0000-0000-0000-000000000001',
  'database-concurrency-contract-session'
);

insert into public.trip_plans (
  id,
  session_id,
  public_token,
  status,
  start_date,
  end_date,
  source_mode,
  data_version
) values (
  'fc210000-0000-0000-0000-000000000001',
  'fc200000-0000-0000-0000-000000000001',
  'database-concurrency-contract-trip',
  'draft',
  '2026-08-20',
  '2026-08-20',
  'fixture',
  'contract-v1'
);

insert into public.trip_days (id, trip_plan_id, day_no, trip_date)
values (
  'fc220000-0000-0000-0000-000000000001',
  'fc210000-0000-0000-0000-000000000001',
  1,
  '2026-08-20'
);

select public.dblink_connect(
  'schedule_a',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-schedule-a'
  )
);
select public.dblink_connect(
  'schedule_b',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-schedule-b'
  )
);

insert into concurrency_contract.connection_pids
select 'schedule', 'a', remote.backend_pid
from public.dblink('schedule_a', 'select pg_backend_pid()')
  as remote(backend_pid integer);

insert into concurrency_contract.connection_pids
select 'schedule', 'b', remote.backend_pid
from public.dblink('schedule_b', 'select pg_backend_pid()')
  as remote(backend_pid integer);

select public.dblink_exec('schedule_a', 'begin');

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink(
    'schedule_a',
    'select concurrency_contract.prepare_schedule_candidate()'
  ) as remote(result_code text);

  if result_code <> 'OK' then
    raise exception 'schedule writer A setup failed: %', result_code;
  end if;
end;
$$;

do $$
begin
  if public.dblink_send_query(
       'schedule_b',
       'select concurrency_contract.try_trip_day_update()'
     ) <> 1 then
    raise exception 'schedule writer B query was not dispatched';
  end if;
end;
$$;

select concurrency_contract.assert_connection_is_blocked(
  'schedule', 'a', 'b', 'schedule_b'
);

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink(
    'schedule_a',
    'select concurrency_contract.seal_schedule_candidate()'
  ) as remote(result_code text);

  if result_code <> 'OK' then
    raise exception 'schedule writer A sealing failed: %', result_code;
  end if;
end;
$$;

select public.dblink_exec('schedule_a', 'commit');

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink_get_result('schedule_b') as remote(result_code text);

  if result_code <> 'P0001' then
    raise exception 'blocked day writer must return P0001, got %', result_code;
  end if;
end;
$$;

do $$
declare
  final_status text;
  final_start_time time;
  version_count integer;
  item_count integer;
begin
  select version.status
    into final_status
  from public.trip_schedule_versions version
  where version.id = 'fc230000-0000-0000-0000-000000000001';

  select day.start_time
    into final_start_time
  from public.trip_days day
  where day.id = 'fc220000-0000-0000-0000-000000000001';

  select count(*)::integer
    into version_count
  from public.trip_schedule_versions version
  where version.trip_plan_id = 'fc210000-0000-0000-0000-000000000001';

  select count(*)::integer
    into item_count
  from public.trip_items item
  where item.schedule_version_id = 'fc230000-0000-0000-0000-000000000001';

  if final_status <> 'candidate'
     or final_start_time is not null
     or version_count <> 1
     or item_count <> 1 then
    raise exception 'schedule concurrency final state is inconsistent';
  end if;
end;
$$;

select public.dblink_disconnect('schedule_a');
select public.dblink_disconnect('schedule_b');

select 'database_concurrency_contract PASS' as result;
