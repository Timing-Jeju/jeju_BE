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

-- PostgreSQL/libpq 버전에 따라 비동기 SELECT의 행 결과 다음에 빈 PGresult를
-- 한 번 더 소비해야 같은 연결에서 다음 명령을 보낼 수 있다.
create function concurrency_contract.drain_async_result(connection_name text)
returns void
language plpgsql
as $$
begin
  perform remote.result_code
  from public.dblink_get_result(connection_name) as remote(result_code text);
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

select concurrency_contract.drain_async_result('checkpoint_b');

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

select concurrency_contract.drain_async_result('schedule_b');

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

-- REPEATABLE READ 일정 시나리오: B가 draft 상태를 먼저 읽어 스냅샷을
-- 고정한 뒤 A가 같은 일정 버전을 봉인한다. 같은 trip_plan의 Day 쓰기는
-- 부모 행 MVCC 쓰기 펜스에서 40001로 거부되어야 하며 stale draft 판단으로
-- 확정 일정 내용을 바꿔서는 안 된다.
create function concurrency_contract.try_repeatable_read_trip_day_update()
returns text
language plpgsql
as $$
begin
  update public.trip_days
  set start_time = '10:30'
  where id = 'fc320000-0000-0000-0000-000000000001';
  return 'OK';
exception when others then
  return sqlstate;
end;
$$;

insert into public.app_sessions (id, public_token)
values (
  'fc300000-0000-0000-0000-000000000001',
  'database-concurrency-contract-rr-session'
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
  'fc310000-0000-0000-0000-000000000001',
  'fc300000-0000-0000-0000-000000000001',
  'database-concurrency-contract-rr-trip',
  'draft',
  '2026-08-21',
  '2026-08-21',
  'fixture',
  'contract-v1'
);

insert into public.trip_days (id, trip_plan_id, day_no, trip_date)
values (
  'fc320000-0000-0000-0000-000000000001',
  'fc310000-0000-0000-0000-000000000001',
  1,
  '2026-08-21'
);

insert into public.trip_schedule_versions (
  id, trip_plan_id, version_no, status, source_type
) values (
  'fc330000-0000-0000-0000-000000000001',
  'fc310000-0000-0000-0000-000000000001',
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
  'fc340000-0000-0000-0000-000000000001',
  'fc310000-0000-0000-0000-000000000001',
  'fc320000-0000-0000-0000-000000000001',
  'fc330000-0000-0000-0000-000000000001',
  1,
  'custom',
  'REPEATABLE READ 동시성 계약 일정',
  '2026-08-21 09:00:00+09',
  '2026-08-21 10:00:00+09',
  60,
  'system',
  '{"location":{"lat":33.45,"lng":126.55}}'::jsonb
);

select public.dblink_connect(
  'schedule_rr_a',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-schedule-rr-a'
  )
);
select public.dblink_connect(
  'schedule_rr_b',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-schedule-rr-b'
  )
);

insert into concurrency_contract.connection_pids
select 'schedule_rr', 'a', remote.backend_pid
from public.dblink('schedule_rr_a', 'select pg_backend_pid()')
  as remote(backend_pid integer);

insert into concurrency_contract.connection_pids
select 'schedule_rr', 'b', remote.backend_pid
from public.dblink('schedule_rr_b', 'select pg_backend_pid()')
  as remote(backend_pid integer);

select public.dblink_exec(
  'schedule_rr_b',
  'begin isolation level repeatable read'
);

do $$
declare
  snapshot_status text;
begin
  select remote.status
    into snapshot_status
  from public.dblink(
    'schedule_rr_b',
    $query$
      select status
      from public.trip_schedule_versions
      where id = 'fc330000-0000-0000-0000-000000000001'
    $query$
  ) as remote(status text);

  if snapshot_status <> 'draft' then
    raise exception 'repeatable-read schedule snapshot was not draft';
  end if;
end;
$$;

select public.dblink_exec('schedule_rr_a', 'begin');
select public.dblink_exec(
  'schedule_rr_a',
  $query$
    update public.trip_schedule_versions
    set status = 'candidate'
    where id = 'fc330000-0000-0000-0000-000000000001'
  $query$
);

do $$
begin
  if public.dblink_send_query(
       'schedule_rr_b',
       'select concurrency_contract.try_repeatable_read_trip_day_update()'
     ) <> 1 then
    raise exception 'repeatable-read schedule query was not dispatched';
  end if;
end;
$$;

select concurrency_contract.assert_connection_is_blocked(
  'schedule_rr', 'a', 'b', 'schedule_rr_b'
);

select public.dblink_exec('schedule_rr_a', 'commit');

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink_get_result('schedule_rr_b') as remote(result_code text);

  if result_code <> '40001' then
    raise exception
      'repeatable-read schedule writer must return 40001, got %',
      result_code;
  end if;
end;
$$;

select concurrency_contract.drain_async_result('schedule_rr_b');

select public.dblink_exec('schedule_rr_b', 'commit');

do $$
declare
  final_status text;
  final_start_time time;
begin
  select version.status, day.start_time
    into final_status, final_start_time
  from public.trip_schedule_versions version
  join public.trip_days day
    on day.trip_plan_id = version.trip_plan_id
  where version.id = 'fc330000-0000-0000-0000-000000000001'
    and day.id = 'fc320000-0000-0000-0000-000000000001';

  if final_status <> 'candidate' or final_start_time is not null then
    raise exception
      'repeatable-read schedule concurrency final state is inconsistent';
  end if;
end;
$$;

select public.dblink_disconnect('schedule_rr_a');
select public.dblink_disconnect('schedule_rr_b');

-- REPEATABLE READ 영업시간 시나리오: B가 빈 영업시간 스냅샷을 먼저 읽은
-- 뒤 A가 금요일 22:00~토요일 02:00 구간을 추가한다. B의 토요일
-- 01:00~03:00 추가는 장소 부모 행 MVCC 쓰기 펜스에서 40001이어야 한다.
create function concurrency_contract.try_repeatable_read_hours_insert()
returns text
language plpgsql
as $$
begin
  insert into public.place_operating_hours (
    id,
    place_id,
    day_of_week,
    interval_no,
    open_time,
    close_time,
    spans_next_day,
    valid_from,
    source_kind
  ) values (
    'fc410000-0000-0000-0000-000000000002',
    'fc400000-0000-0000-0000-000000000001',
    6,
    1,
    '01:00',
    '03:00',
    false,
    '2026-01-01',
    'manual'
  );
  return 'OK';
exception when others then
  return sqlstate;
end;
$$;

insert into public.tour_places (
  id,
  name,
  normalized_name,
  category,
  location,
  source_provider
) values (
  'fc400000-0000-0000-0000-000000000001',
  'REPEATABLE READ 영업시간 장소',
  'repeatableread영업시간장소',
  'tourist_attraction',
  st_setsrid(st_makepoint(126.55, 33.45), 4326)::geography,
  'admin_upload'
);

select public.dblink_connect(
  'hours_rr_a',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-hours-rr-a'
  )
);
select public.dblink_connect(
  'hours_rr_b',
  pg_catalog.format(
    'dbname=%L user=%L application_name=%L',
    current_database(),
    current_user,
    'timing-jeju-hours-rr-b'
  )
);

insert into concurrency_contract.connection_pids
select 'hours_rr', 'a', remote.backend_pid
from public.dblink('hours_rr_a', 'select pg_backend_pid()')
  as remote(backend_pid integer);

insert into concurrency_contract.connection_pids
select 'hours_rr', 'b', remote.backend_pid
from public.dblink('hours_rr_b', 'select pg_backend_pid()')
  as remote(backend_pid integer);

select public.dblink_exec(
  'hours_rr_b',
  'begin isolation level repeatable read'
);

do $$
declare
  snapshot_count integer;
begin
  select remote.hours_count
    into snapshot_count
  from public.dblink(
    'hours_rr_b',
    $query$
      select count(*)::integer
      from public.place_operating_hours
      where place_id = 'fc400000-0000-0000-0000-000000000001'
    $query$
  ) as remote(hours_count integer);

  if snapshot_count <> 0 then
    raise exception 'repeatable-read operating-hours snapshot was not empty';
  end if;
end;
$$;

select public.dblink_exec('hours_rr_a', 'begin');
select public.dblink_exec(
  'hours_rr_a',
  $query$
    insert into public.place_operating_hours (
      id,
      place_id,
      day_of_week,
      interval_no,
      open_time,
      close_time,
      spans_next_day,
      valid_from,
      source_kind
    ) values (
      'fc410000-0000-0000-0000-000000000001',
      'fc400000-0000-0000-0000-000000000001',
      5,
      1,
      '22:00',
      '02:00',
      true,
      '2026-01-01',
      'manual'
    )
  $query$
);

do $$
begin
  if public.dblink_send_query(
       'hours_rr_b',
       'select concurrency_contract.try_repeatable_read_hours_insert()'
     ) <> 1 then
    raise exception 'repeatable-read operating-hours query was not dispatched';
  end if;
end;
$$;

select concurrency_contract.assert_connection_is_blocked(
  'hours_rr', 'a', 'b', 'hours_rr_b'
);

select public.dblink_exec('hours_rr_a', 'commit');

do $$
declare
  result_code text;
begin
  select remote.result_code
    into result_code
  from public.dblink_get_result('hours_rr_b') as remote(result_code text);

  if result_code <> '40001' then
    raise exception
      'repeatable-read operating-hours writer must return 40001, got %',
      result_code;
  end if;
end;
$$;

select concurrency_contract.drain_async_result('hours_rr_b');

select public.dblink_exec('hours_rr_b', 'commit');

do $$
declare
  final_count integer;
begin
  select count(*)::integer
    into final_count
  from public.place_operating_hours
  where place_id = 'fc400000-0000-0000-0000-000000000001';

  if final_count <> 1 then
    raise exception
      'repeatable-read operating-hours final state is inconsistent';
  end if;
end;
$$;

select public.dblink_disconnect('hours_rr_a');
select public.dblink_disconnect('hours_rr_b');

-- provenance INSERT가 참조 대상을 잠근 동안 별도 세션의 DELETE는 대기해야 한다.
-- INSERT 커밋 뒤 DELETE는 커밋된 provenance를 보고 23503으로 거부된다.
insert into public.data_import_runs (
  id, source_kind, source_name, source_operation, data_version, status, started_at,
  parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
  idempotency_key, source_provider, source_service
) values (
  'fc500000-0000-0000-0000-000000000001', 'tour_api', 'concurrency-provenance',
  'areaBasedList2', 'contract-v1', 'running', '2026-08-14 00:00:00+00',
  'parser-v1', 'schema-v1', 'incremental', 'jeju', 'sha256:fixture',
  'concurrency-provenance', 'tour-api', 'KorService2'
);

insert into public.external_api_snapshots (
  id, import_run_id, source_provider, source_service, source_operation, scope_key,
  request_hash, page_key, fetched_at, parser_version, payload_hash,
  request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
  payload_format, initial_parse_status, parse_status, parsed_at
) values (
  'fc510000-0000-0000-0000-000000000001',
  'fc500000-0000-0000-0000-000000000001',
  'tour-api', 'KorService2', 'areaBasedList2', 'jeju',
  repeat('a', 64), '', '2026-08-14 00:00:00+00', 'parser-v1', repeat('b', 64),
  '{}'::jsonb, '{}'::jsonb, 2, 'contract-v1', 'JSON', 'parsed', 'parsed',
  '2026-08-14 00:00:00+00'
);

insert into public.tour_places
  (id, name, normalized_name, category, location, source_provider)
values
  ('fc520000-0000-0000-0000-000000000001', 'provenance', 'provenance', 'contract',
   public.st_geogfromtext('SRID=4326;POINT(126.5 33.5)'), 'admin_upload');
insert into public.external_reference_codes
  (id, source_provider, source_service, code_type, external_code, code_name)
values ('fc530000-0000-0000-0000-000000000001', 'admin_upload', 'contract', 'area', '50', '제주');
insert into public.tour_place_sources
  (id, place_id, source_provider, source_service, external_id)
values ('fc540000-0000-0000-0000-000000000001', 'fc520000-0000-0000-0000-000000000001',
        'admin_upload', 'contract', 'source-107');
insert into public.place_aliases
  (id, place_id, alias, normalized_alias, alias_type, source_snapshot_id, import_run_id)
values ('fc550000-0000-0000-0000-000000000001', 'fc520000-0000-0000-0000-000000000001',
        '계약 별칭', '계약별칭', 'official', 'fc510000-0000-0000-0000-000000000001',
        'fc500000-0000-0000-0000-000000000001');
insert into public.place_details (place_id, source_provider, source_service)
values ('fc520000-0000-0000-0000-000000000001', 'admin_upload', 'contract');
insert into public.place_detail_items
  (id, place_id, source_provider, source_service, item_type, source_item_key, payload_hash)
values ('fc560000-0000-0000-0000-000000000001', 'fc520000-0000-0000-0000-000000000001',
        'admin_upload', 'contract', 'overview', 'item-107', repeat('c', 64));
insert into public.place_images
  (id, place_id, image_url, source_provider, source_service)
values ('fc570000-0000-0000-0000-000000000001', 'fc520000-0000-0000-0000-000000000001',
        'https://example.test/contract.jpg', 'admin_upload', 'contract');

create function concurrency_contract.try_delete_provenance_target(
  entity_type text,
  target_id uuid
)
returns text
language plpgsql
as $$
begin
  case entity_type
    when 'external_reference_codes' then delete from public.external_reference_codes where id = target_id;
    when 'tour_place_sources' then delete from public.tour_place_sources where id = target_id;
    when 'place_aliases' then delete from public.place_aliases where id = target_id;
    when 'place_details' then delete from public.place_details where place_id = target_id;
    when 'place_detail_items' then delete from public.place_detail_items where id = target_id;
    when 'place_images' then delete from public.place_images where id = target_id;
    when 'tour_places' then delete from public.tour_places where id = target_id;
    else raise exception 'unsupported provenance target type';
  end case;
  return 'OK';
exception when others then
  return sqlstate;
end;
$$;

select public.dblink_connect('provenance_a', pg_catalog.format(
  'dbname=%L user=%L application_name=%L', current_database(), current_user,
  'timing-jeju-provenance-a'));
select public.dblink_connect('provenance_b', pg_catalog.format(
  'dbname=%L user=%L application_name=%L', current_database(), current_user,
  'timing-jeju-provenance-b'));
insert into concurrency_contract.connection_pids
select 'provenance', 'a', remote.backend_pid
from public.dblink('provenance_a', 'select pg_backend_pid()') as remote(backend_pid integer);
insert into concurrency_contract.connection_pids
select 'provenance', 'b', remote.backend_pid
from public.dblink('provenance_b', 'select pg_backend_pid()') as remote(backend_pid integer);

do $$
declare
  entity_types text[] := array[
    'external_reference_codes', 'tour_place_sources', 'place_aliases', 'place_details',
    'place_detail_items', 'place_images', 'tour_places'
  ];
  target_ids uuid[] := array[
    'fc530000-0000-0000-0000-000000000001', 'fc540000-0000-0000-0000-000000000001',
    'fc550000-0000-0000-0000-000000000001', 'fc520000-0000-0000-0000-000000000001',
    'fc560000-0000-0000-0000-000000000001', 'fc570000-0000-0000-0000-000000000001',
    'fc520000-0000-0000-0000-000000000001'
  ]::uuid[];
  result_code text;
  index integer;
begin
  for index in 1..array_length(entity_types, 1) loop
    perform public.dblink_exec('provenance_a', 'begin');
    perform public.dblink_exec('provenance_a', pg_catalog.format(
      $query$
        insert into public.tour_api_operation_provenance (
          normalized_entity_type, normalized_row_id, operation_key, request_fingerprint,
          source_snapshot_id, import_run_id
        ) values (%L, %L::uuid, 'areaBasedList2', %L,
          'fc510000-0000-0000-0000-000000000001',
          'fc500000-0000-0000-0000-000000000001')
      $query$, entity_types[index], target_ids[index], repeat('a', 64)));

    if public.dblink_send_query('provenance_b', pg_catalog.format(
         'select concurrency_contract.try_delete_provenance_target(%L, %L::uuid)',
         entity_types[index], target_ids[index])) <> 1 then
      raise exception 'provenance delete query was not dispatched';
    end if;

    perform concurrency_contract.assert_connection_is_blocked(
      'provenance', 'a', 'b', 'provenance_b');
    perform public.dblink_exec('provenance_a', 'commit');

    select remote.result_code into result_code
    from public.dblink_get_result('provenance_b') as remote(result_code text);
    if result_code <> '23503' then
      raise exception 'provenance target delete must return 23503, got % for %',
        result_code, entity_types[index];
    end if;
    perform concurrency_contract.drain_async_result('provenance_b');
  end loop;
end;
$$;

select public.dblink_disconnect('provenance_a');
select public.dblink_disconnect('provenance_b');

select 'database_concurrency_contract PASS' as result;
