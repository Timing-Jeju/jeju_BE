-- 외부 응답과 정규화 행의 계보를 일치시키고 기준정보의 시간 중복을 차단한다.
-- 기존 area/category 계열 컬럼은 legacy 호환을 위해 유지한다.

alter table public.data_import_runs
  add column source_provider text not null default 'legacy',
  add column source_service text not null default 'legacy',
  add column idempotency_enforced boolean not null default true,
  add column running_scope_enforced boolean not null default true,
  add constraint ck_data_import_runs_source_provider_nonblank
    check (btrim(source_provider) <> ''),
  add constraint ck_data_import_runs_source_service_nonblank
    check (btrim(source_service) <> '');

-- v1에서 허용하던 상태·문자열은 보존한다. 아래 source scope backfill이
-- NOT VALID CHECK까지 재검사하지 않도록 잠시 내리고 같은 계약으로 다시 건다.
alter table public.data_import_runs
  drop constraint chk_data_import_runs_nonblank_fields,
  drop constraint chk_data_import_runs_json_objects,
  drop constraint chk_data_import_runs_error_pair,
  drop constraint chk_data_import_runs_state_fields,
  drop constraint chk_data_import_runs_time_order;

update public.data_import_runs
set source_provider = source_kind,
    source_service = coalesce(
      nullif(btrim(source_name), ''),
      'legacy-' || source_kind || '-' || id::text
    );

do $$
declare
  conflict_run_id uuid;
  conflict_scopes text;
begin
  select scope_row.import_run_id,
         pg_catalog.string_agg(
           pg_catalog.concat_ws(
             '/',
             scope_row.source_provider,
             scope_row.source_service,
             scope_row.source_operation,
             scope_row.scope_key
           ),
           ', '
           order by scope_row.source_provider, scope_row.source_service,
             scope_row.source_operation, scope_row.scope_key
         )
    into conflict_run_id, conflict_scopes
  from (
    select distinct
      snapshot.import_run_id,
      snapshot.source_provider,
      snapshot.source_service,
      snapshot.source_operation,
      snapshot.scope_key
    from public.external_api_snapshots snapshot
  ) scope_row
  group by scope_row.import_run_id
  having pg_catalog.count(*) > 1
  order by scope_row.import_run_id
  limit 1;

  if conflict_run_id is not null then
    raise exception using
      errcode = '23514',
      message = 'existing import run spans multiple snapshot source scopes',
      detail = pg_catalog.format(
        'import_run_id=%s, scopes=%s',
        conflict_run_id,
        conflict_scopes
      );
  end if;
end;
$$;

update public.data_import_runs import_run
set source_provider = snapshot_scope.source_provider,
    source_service = snapshot_scope.source_service,
    source_operation = snapshot_scope.source_operation,
    scope_key = snapshot_scope.scope_key
from (
  select
    snapshot.import_run_id,
    min(snapshot.source_provider) as source_provider,
    min(snapshot.source_service) as source_service,
    min(snapshot.source_operation) as source_operation,
    min(snapshot.scope_key) as scope_key
  from public.external_api_snapshots snapshot
  group by snapshot.import_run_id
) snapshot_scope
where import_run.id = snapshot_scope.import_run_id;

update public.data_import_runs
set source_operation = 'legacy'
where source_operation is null;

-- v1에는 동시 실행 scope 제약이 없었다. 원래 source scope와 checkpoint 의미를
-- 바꾸지 않고, 각 bounded 중복 그룹의 가장 오래된 1건만 UNIQUE 대표로 남긴다.
-- 나머지 running 행은 종료할 수 있지만 새 행/재시작은 반드시 enforcement에
-- 다시 참여해야 한다.
update public.data_import_runs import_run
set running_scope_enforced = false
where import_run.status = 'running'
  and (
    pg_catalog.octet_length(import_run.source_provider) > 128
    or pg_catalog.octet_length(import_run.source_service) > 128
    or pg_catalog.octet_length(import_run.source_operation) > 128
    or pg_catalog.octet_length(import_run.scope_key) > 512
    or pg_catalog.octet_length(import_run.source_provider)
      + pg_catalog.octet_length(import_run.source_service)
      + pg_catalog.octet_length(import_run.source_operation)
      + pg_catalog.octet_length(import_run.scope_key) > 1800
  );

with ranked_running_scope as (
  select
    import_run.id,
    pg_catalog.row_number() over (
      partition by
        import_run.source_provider,
        import_run.source_service,
        import_run.source_operation,
        import_run.scope_key
      order by import_run.started_at, import_run.id
    ) as scope_rank
  from public.data_import_runs import_run
  where import_run.status = 'running'
    and import_run.running_scope_enforced
)
update public.data_import_runs import_run
set running_scope_enforced = false
from ranked_running_scope ranked_scope
where import_run.id = ranked_scope.id
  and ranked_scope.scope_rank > 1;

-- 기존 idempotency 중복과 장문 B-tree 비대상 행은 원문 key를 보존하되
-- 신규 요청의 UNIQUE arbiter로 사용하지 않는다. 이후 INSERT는 항상 enforcement
-- 대상이며, grandfathered 행은 충돌을 해소하는 단 한 번의 opt-in만 허용한다.
update public.data_import_runs import_run
set idempotency_enforced = false
where import_run.idempotency_key is not null
  and (
    pg_catalog.octet_length(import_run.source_provider) > 128
    or pg_catalog.octet_length(import_run.source_service) > 128
    or pg_catalog.octet_length(import_run.source_operation) > 128
    or pg_catalog.octet_length(import_run.scope_key) > 512
    or pg_catalog.octet_length(import_run.idempotency_key) > 512
    or pg_catalog.octet_length(import_run.source_provider)
      + pg_catalog.octet_length(import_run.source_service)
      + pg_catalog.octet_length(import_run.source_operation)
      + pg_catalog.octet_length(import_run.scope_key)
      + pg_catalog.octet_length(import_run.idempotency_key) > 1800
  );

with ranked_idempotency_scope as (
  select
    import_run.id,
    pg_catalog.row_number() over (
      partition by
        import_run.source_provider,
        import_run.source_service,
        import_run.source_operation,
        import_run.scope_key,
        import_run.idempotency_key
      order by import_run.started_at, import_run.id
    ) as idempotency_rank
  from public.data_import_runs import_run
  where import_run.idempotency_key is not null
    and import_run.idempotency_enforced
)
update public.data_import_runs import_run
set idempotency_enforced = false
from ranked_idempotency_scope ranked_scope
where import_run.id = ranked_scope.id
  and ranked_scope.idempotency_rank > 1;

alter table public.data_import_runs
  alter column source_provider drop default,
  alter column source_service drop default,
  alter column source_operation set default 'legacy',
  alter column source_operation set not null,
  add constraint chk_data_import_runs_error_text_nonblank
    check (
      (error_code is null or btrim(error_code) <> '')
      and (error_message is null or btrim(error_message) <> '')
    ) not valid,
  add constraint chk_data_import_runs_error_pair
    check (
      (error_code is null and error_message is null)
      or (error_code is not null and error_message is not null)
    ) not valid,
  add constraint chk_data_import_runs_state_fields
    check (
      (
        status = 'running'
        and finished_at is null
        and error_code is null
        and error_message is null
      )
      or (
        status = 'succeeded'
        and finished_at is not null
        and error_code is null
        and error_message is null
      )
      or (
        status = 'failed'
        and finished_at is not null
        and error_code is not null
        and error_message is not null
      )
      or (
        status in ('partial', 'cancelled')
        and finished_at is not null
      )
    ) not valid,
  add constraint chk_data_import_runs_time_order
    check (finished_at is null or finished_at >= started_at) not valid;

create function public.validate_import_run_nonblank_fields()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and old.source_name is not distinct from new.source_name
     and old.source_provider is not distinct from new.source_provider
     and old.source_service is not distinct from new.source_service
     and old.source_operation is not distinct from new.source_operation
     and old.data_version is not distinct from new.data_version
     and old.parser_version is not distinct from new.parser_version
     and old.schema_version is not distinct from new.schema_version
     and old.sync_mode is not distinct from new.sync_mode
     and old.scope_key is not distinct from new.scope_key
     and old.request_fingerprint is not distinct from new.request_fingerprint
     and old.idempotency_key is not distinct from new.idempotency_key
     and not (old.status is distinct from 'running' and new.status = 'running') then
    return new;
  end if;

  if pg_catalog.btrim(new.source_name) = ''
     or pg_catalog.btrim(new.source_provider) = ''
     or pg_catalog.btrim(new.source_service) = ''
     or pg_catalog.btrim(new.source_operation) = ''
     or pg_catalog.btrim(new.data_version) = ''
     or pg_catalog.btrim(new.parser_version) = ''
     or pg_catalog.btrim(new.schema_version) = ''
     or pg_catalog.btrim(new.sync_mode) = ''
     or pg_catalog.btrim(new.scope_key) = ''
     or (
       new.request_fingerprint is not null
       and pg_catalog.btrim(new.request_fingerprint) = ''
     )
     or (
       new.idempotency_key is not null
       and pg_catalog.btrim(new.idempotency_key) = ''
     ) then
    raise exception using
      errcode = '23514',
      message = 'import run identity fields must be nonblank';
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_nonblank_insert
before insert on public.data_import_runs
for each row execute function public.validate_import_run_nonblank_fields();

create trigger trg_data_import_runs_nonblank_update
before update of source_name, source_provider, source_service, source_operation,
  data_version, parser_version, schema_version, sync_mode, scope_key,
  request_fingerprint, idempotency_key, status
on public.data_import_runs
for each row execute function public.validate_import_run_nonblank_fields();

create function public.validate_import_run_json_objects()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and old.metadata is not distinct from new.metadata
     and old.checkpoint_before is not distinct from new.checkpoint_before
     and old.checkpoint_after is not distinct from new.checkpoint_after
     and not (old.status is distinct from 'running' and new.status = 'running') then
    return new;
  end if;

  if pg_catalog.jsonb_typeof(new.metadata) <> 'object'
     or pg_catalog.jsonb_typeof(new.checkpoint_before) <> 'object'
     or pg_catalog.jsonb_typeof(new.checkpoint_after) <> 'object' then
    raise exception using
      errcode = '23514',
      message = 'import run metadata and checkpoints must be JSON objects';
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_json_insert
before insert on public.data_import_runs
for each row execute function public.validate_import_run_json_objects();

create trigger trg_data_import_runs_json_update
before update of metadata, checkpoint_before, checkpoint_after, status
on public.data_import_runs
for each row execute function public.validate_import_run_json_objects();

-- NOT VALID CHECK도 기존 위반 행의 일반 UPDATE에는 다시 적용된다. 따라서
-- v1 장문 running 행이 terminal 상태로 끝나지 못하는 교착을 피하면서,
-- 신규·키 변경·terminal→running 전이에는 동일한 길이 계약을 강제한다.
create function public.validate_import_run_source_key_lengths()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and old.source_provider is not distinct from new.source_provider
     and old.source_service is not distinct from new.source_service
     and old.source_operation is not distinct from new.source_operation
     and old.scope_key is not distinct from new.scope_key
     and old.idempotency_key is not distinct from new.idempotency_key
     and old.idempotency_enforced is not distinct from new.idempotency_enforced
     and old.running_scope_enforced is not distinct from new.running_scope_enforced
     and not (old.status is distinct from 'running' and new.status = 'running') then
    return new;
  end if;

  if pg_catalog.octet_length(new.source_provider) > 128
     or pg_catalog.octet_length(new.source_service) > 128
     or pg_catalog.octet_length(new.source_operation) > 128
     or pg_catalog.octet_length(new.scope_key) > 512
     or (
       new.idempotency_key is not null
       and pg_catalog.octet_length(new.idempotency_key) > 512
     )
     or pg_catalog.octet_length(new.source_provider)
       + pg_catalog.octet_length(new.source_service)
       + pg_catalog.octet_length(new.source_operation)
       + pg_catalog.octet_length(new.scope_key)
       + coalesce(
         pg_catalog.octet_length(new.idempotency_key),
         0
       ) > 1800 then
    raise exception using
      errcode = '23514',
      message = 'import run source key exceeds storage limit';
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_source_key_insert
before insert on public.data_import_runs
for each row execute function public.validate_import_run_source_key_lengths();

create trigger trg_data_import_runs_source_key_update
before update of source_provider, source_service, source_operation, scope_key,
  idempotency_key, idempotency_enforced, running_scope_enforced, status
on public.data_import_runs
for each row execute function public.validate_import_run_source_key_lengths();

create function public.protect_import_run_idempotency()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if not new.idempotency_enforced then
      raise exception using
        errcode = '23514',
        message = 'new import run must enforce idempotency';
    end if;
  else
    if old.idempotency_enforced and not new.idempotency_enforced then
      raise exception using
        errcode = '23514',
        message = 'import run idempotency enforcement cannot be disabled';
    end if;

    if old.idempotency_enforced
       and old.idempotency_key is not null
       and old.idempotency_key is distinct from new.idempotency_key then
      raise exception using
        errcode = '23514',
        message = 'import run idempotency key is immutable';
    end if;

    if not old.idempotency_enforced
       and not new.idempotency_enforced
       and old.idempotency_key is distinct from new.idempotency_key then
      raise exception using
        errcode = '23514',
        message = 'grandfathered idempotency key requires enforcement opt-in to change';
    end if;
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_idempotency_guard
before insert or update of idempotency_key, idempotency_enforced
on public.data_import_runs
for each row execute function public.protect_import_run_idempotency();

create function public.protect_grandfathered_idempotency_arbiter()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.idempotency_enforced
     and old.idempotency_key is not null
     and exists (
       select 1
       from public.data_import_runs legacy_run
       where not legacy_run.idempotency_enforced
         and legacy_run.id <> old.id
         and legacy_run.source_provider = old.source_provider
         and legacy_run.source_service = old.source_service
         and legacy_run.source_operation = old.source_operation
         and legacy_run.scope_key = old.scope_key
         and legacy_run.idempotency_key = old.idempotency_key
     ) then
    raise exception using
      errcode = '23514',
      message = 'canonical idempotency arbiter cannot be deleted before grandfathered rows';
  end if;

  return old;
end;
$$;

create trigger trg_data_import_runs_protect_idempotency_arbiter
before delete on public.data_import_runs
for each row execute function public.protect_grandfathered_idempotency_arbiter();

create function public.protect_import_run_running_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if not new.running_scope_enforced then
      raise exception using
        errcode = '23514',
        message = 'new import run must enforce running scope';
    end if;
  else
    if old.running_scope_enforced and not new.running_scope_enforced then
      raise exception using
        errcode = '23514',
        message = 'import run running-scope enforcement cannot be disabled';
    end if;

    if old.status <> 'running'
       and new.status = 'running'
       and not new.running_scope_enforced then
      raise exception using
        errcode = '23514',
        message = 'restarted import run must enforce running scope';
    end if;
  end if;

  if new.status = 'running'
     and new.running_scope_enforced
     and exists (
       select 1
       from public.data_import_runs legacy_run
       where legacy_run.status = 'running'
         and not legacy_run.running_scope_enforced
         and legacy_run.id <> new.id
         and legacy_run.source_provider = new.source_provider
         and legacy_run.source_service = new.source_service
         and legacy_run.source_operation = new.source_operation
         and legacy_run.scope_key = new.scope_key
     ) then
    raise exception using
      errcode = '23505',
      message = 'import run scope is occupied by a grandfathered running run';
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_running_scope_guard
before insert or update of status, running_scope_enforced,
  source_provider, source_service, source_operation, scope_key
on public.data_import_runs
for each row execute function public.protect_import_run_running_scope();

drop index if exists public.uq_data_import_runs_idempotency;
drop index if exists public.uq_data_import_runs_running_scope;

create unique index uq_data_import_runs_idempotency
  on public.data_import_runs (
    source_provider,
    source_service,
    source_operation,
    scope_key,
    idempotency_key
  )
  where idempotency_key is not null
    and idempotency_enforced;

create unique index uq_data_import_runs_running_scope
  on public.data_import_runs (
    source_provider,
    source_service,
    source_operation,
    scope_key
  )
  where status = 'running'
    and running_scope_enforced;

create function public.protect_import_run_source_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.source_provider is distinct from new.source_provider
     or old.source_service is distinct from new.source_service
     or old.source_operation is distinct from new.source_operation
     or old.scope_key is distinct from new.scope_key then
    raise exception using
      errcode = '23514',
      message = 'import run source scope is immutable';
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_source_scope_immutable
before update of source_provider, source_service, source_operation, scope_key
on public.data_import_runs
for each row execute function public.protect_import_run_source_scope();

create function public.validate_external_snapshot_import_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  perform import_run.id
  from public.data_import_runs import_run
  where import_run.id = new.import_run_id
    and import_run.source_provider = new.source_provider
    and import_run.source_service = new.source_service
    and import_run.source_operation = new.source_operation
    and import_run.scope_key = new.scope_key
  for key share;

  if not found then
    raise exception using
      errcode = '23514',
      message = 'external snapshot source scope must match its import run';
  end if;

  return new;
end;
$$;

create constraint trigger trg_external_snapshots_import_scope
after insert or update on public.external_api_snapshots
deferrable initially immediate
for each row execute function public.validate_external_snapshot_import_scope();

create function public.validate_checkpoint_succeeded_run()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  run_status text;
begin
  if new.last_succeeded_run_id is null then
    return new;
  end if;

  select r.status
    into run_status
  from public.data_import_runs r
  where r.id = new.last_succeeded_run_id
    and r.source_provider = new.source_provider
    and r.source_service = new.source_service
    and r.source_operation = new.source_operation
    and r.scope_key = new.scope_key
  for share;

  if run_status is null then
    raise exception using
      errcode = '23514',
      message = 'checkpoint source scope must match its import run';
  end if;

  if run_status <> 'succeeded' then
    raise exception using
      errcode = '23514',
      message = 'checkpoint may reference only a succeeded import run';
  end if;

  return new;
end;
$$;

create constraint trigger trg_data_import_checkpoints_succeeded_run
after insert or update on public.data_import_checkpoints
deferrable initially immediate
for each row
execute function public.validate_checkpoint_succeeded_run();

-- checkpoint 행뿐 아니라 참조 대상 run의 상태 전이도 먼저 막아야 한다.
-- 두 방향의 guard 설치가 끝난 뒤 legacy 행을 감사해 audit 사이의 write race를 닫는다.
create function public.protect_checkpoint_succeeded_run()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status <> 'succeeded'
     and exists (
       select 1
       from public.data_import_checkpoints checkpoint_row
       where checkpoint_row.last_succeeded_run_id = new.id
     ) then
    raise exception using
      errcode = '23514',
      message = 'a checkpoint-referenced run must remain succeeded';
  end if;

  return new;
end;
$$;

create constraint trigger trg_data_import_runs_protect_checkpoint
after update of status on public.data_import_runs
deferrable initially immediate
for each row
when (old.status is distinct from new.status)
execute function public.protect_checkpoint_succeeded_run();

-- .100에는 raw UUID FK만 있었으므로 같은 범위의 succeeded run이라는 의미를
-- 위반한 checkpoint가 존재할 수 있다. 신규 write guard를 먼저 설치한 뒤 기존
-- 위반을 자동 보정하지 않고 운영자가 복구할 상태·scope·식별자를 반환한다.
do $$
declare
  invalid_checkpoint record;
begin
  select
    checkpoint.id as checkpoint_id,
    checkpoint.last_succeeded_run_id,
    import_run.status as run_status,
    pg_catalog.concat_ws(
      '/', checkpoint.source_provider, checkpoint.source_service,
      checkpoint.source_operation, checkpoint.scope_key
    ) as checkpoint_scope,
    pg_catalog.concat_ws(
      '/', import_run.source_provider, import_run.source_service,
      import_run.source_operation, import_run.scope_key
    ) as run_scope
    into invalid_checkpoint
  from public.data_import_checkpoints checkpoint
  left join public.data_import_runs import_run
    on import_run.id = checkpoint.last_succeeded_run_id
  where checkpoint.last_succeeded_run_id is not null
    and (
      import_run.id is null
      or import_run.status is distinct from 'succeeded'
      or import_run.source_provider is distinct from checkpoint.source_provider
      or import_run.source_service is distinct from checkpoint.source_service
      or import_run.source_operation is distinct from checkpoint.source_operation
      or import_run.scope_key is distinct from checkpoint.scope_key
    )
  order by checkpoint.id
  limit 1;

  if found then
    raise exception using
      errcode = '23514',
      message = 'legacy checkpoint succeeded-run audit failed',
      detail = pg_catalog.format(
        'checkpoint_id=%s, last_succeeded_run_id=%s, run_status=%s, checkpoint_scope=%s, run_scope=%s',
        invalid_checkpoint.checkpoint_id,
        invalid_checkpoint.last_succeeded_run_id,
        invalid_checkpoint.run_status,
        invalid_checkpoint.checkpoint_scope,
        invalid_checkpoint.run_scope
      );
  end if;
end;
$$;

create function public.protect_checkpoint_progress()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  previous_run_order record;
  next_run_order record;
  expected_version_setting text;
begin
  if tg_op = 'INSERT' then
    if new.version <> 0 then
      raise exception using
        errcode = '23514',
        message = 'new checkpoint version must start at zero';
    end if;
    return new;
  end if;

  if old.source_provider is distinct from new.source_provider
     or old.source_service is distinct from new.source_service
     or old.source_operation is distinct from new.source_operation
     or old.scope_key is distinct from new.scope_key then
    raise exception using
      errcode = '23514',
      message = 'checkpoint source scope is immutable';
  end if;

  expected_version_setting :=
    pg_catalog.current_setting('timing_jeju.checkpoint_expected_version', true);

  if expected_version_setting is null
     or expected_version_setting = ''
     or expected_version_setting::bigint <> old.version
     or new.version <> old.version + 1 then
    raise exception using
      errcode = '40001',
      message = 'checkpoint update requires compare-and-set version increment';
  end if;

  if old.last_succeeded_run_id is not null
     and new.last_succeeded_run_id is distinct from old.last_succeeded_run_id then
    select
      coalesce(import_run.finished_at, import_run.started_at) as completed_at,
      import_run.started_at,
      import_run.id
      into previous_run_order
    from public.data_import_runs import_run
    where import_run.id = old.last_succeeded_run_id;

    select
      coalesce(import_run.finished_at, import_run.started_at) as completed_at,
      import_run.started_at,
      import_run.id
      into next_run_order
    from public.data_import_runs import_run
    where import_run.id = new.last_succeeded_run_id;

    if not found then
      raise exception using
        errcode = '23503',
        message = 'checkpoint import run does not exist';
    end if;

    if row(
         next_run_order.completed_at,
         next_run_order.started_at,
         next_run_order.id
       ) <= row(
         previous_run_order.completed_at,
         previous_run_order.started_at,
         previous_run_order.id
       ) then
      raise exception using
        errcode = '23514',
        message = 'checkpoint cannot move to an older succeeded import run';
    end if;
  end if;

  return new;
end;
$$;

create trigger trg_data_import_checkpoints_progress
before insert or update of source_provider, source_service, source_operation, scope_key,
  checkpoint, source_watermark_at, last_succeeded_run_id, version
on public.data_import_checkpoints
for each row execute function public.protect_checkpoint_progress();

create function public.advance_data_import_checkpoint(
  p_source_provider text,
  p_source_service text,
  p_source_operation text,
  p_scope_key text,
  p_expected_version bigint,
  p_checkpoint jsonb,
  p_source_watermark_at timestamptz,
  p_last_succeeded_run_id uuid
)
returns public.data_import_checkpoints
language plpgsql
security definer
set search_path = ''
as $$
declare
  updated_checkpoint public.data_import_checkpoints%rowtype;
  checkpoint_updated boolean;
begin
  perform pg_catalog.set_config(
    'timing_jeju.checkpoint_expected_version',
    p_expected_version::text,
    true
  );

  update public.data_import_checkpoints checkpoint_row
  set checkpoint = p_checkpoint,
      source_watermark_at = p_source_watermark_at,
      last_succeeded_run_id = p_last_succeeded_run_id,
      version = checkpoint_row.version + 1,
      updated_at = pg_catalog.clock_timestamp()
  where checkpoint_row.source_provider = p_source_provider
    and checkpoint_row.source_service = p_source_service
    and checkpoint_row.source_operation = p_source_operation
    and checkpoint_row.scope_key = p_scope_key
    and checkpoint_row.version = p_expected_version
  returning checkpoint_row.* into updated_checkpoint;

  checkpoint_updated := found;
  perform pg_catalog.set_config(
    'timing_jeju.checkpoint_expected_version',
    '',
    true
  );

  if not checkpoint_updated then
    raise exception using
      errcode = '40001',
      message = 'checkpoint compare-and-set expected version is stale';
  end if;

  return updated_checkpoint;
exception
  when others then
    perform pg_catalog.set_config(
      'timing_jeju.checkpoint_expected_version',
      '',
      true
    );
    raise;
end;
$$;

revoke all on function public.advance_data_import_checkpoint(
  text, text, text, text, bigint, jsonb, timestamptz, uuid
) from public;

create function public.prevent_checkpoint_reset()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  raise exception using
    errcode = 'P0001',
    message = 'checkpoint rows cannot be deleted or truncated';
end;
$$;

create trigger trg_data_import_checkpoints_no_delete
before delete on public.data_import_checkpoints
for each row execute function public.prevent_checkpoint_reset();

create trigger trg_data_import_checkpoints_no_truncate
before truncate on public.data_import_checkpoints
for each statement execute function public.prevent_checkpoint_reset();

do $$
begin
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'anon') then
    execute 'revoke execute on function public.advance_data_import_checkpoint(text, text, text, text, bigint, jsonb, timestamptz, uuid) from anon';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated') then
    execute 'revoke execute on function public.advance_data_import_checkpoint(text, text, text, text, bigint, jsonb, timestamptz, uuid) from authenticated';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role') then
    execute 'revoke update, delete, truncate on public.data_import_checkpoints from service_role';
    execute 'grant execute on function public.advance_data_import_checkpoint(text, text, text, text, bigint, jsonb, timestamptz, uuid) to service_role';
  end if;
end;
$$;

create function public.protect_external_snapshot_identity()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.import_run_id is distinct from new.import_run_id
     or old.source_provider is distinct from new.source_provider
     or old.source_service is distinct from new.source_service
     or old.source_operation is distinct from new.source_operation
     or old.scope_key is distinct from new.scope_key then
    raise exception using
      errcode = '23514',
      message = 'external snapshot source identity is immutable';
  end if;

  if old.external_record_id is distinct from new.external_record_id
     or old.request_hash is distinct from new.request_hash
     or old.page_key is distinct from new.page_key
     or old.http_status is distinct from new.http_status
     or old.provider_result_code is distinct from new.provider_result_code
     or old.fetched_at is distinct from new.fetched_at
     or old.source_modified_at is distinct from new.source_modified_at
     or old.parser_version is distinct from new.parser_version
     or old.payload_hash is distinct from new.payload_hash
     or old.request_metadata_redacted is distinct from new.request_metadata_redacted
     or old.raw_payload is distinct from new.raw_payload then
    raise exception using
      errcode = '23514',
      message = 'external snapshot audit payload is immutable';
  end if;

  if old.parse_status in ('parsed', 'tombstoned')
     and new.parse_status not in ('parsed', 'tombstoned') then
    raise exception using
      errcode = '23514',
      message = 'a normalized-capable snapshot cannot return to an unparsed status';
  end if;

  return new;
end;
$$;

create trigger trg_external_snapshots_immutable_identity
before update of
  import_run_id,
  source_provider,
  source_service,
  source_operation,
  scope_key,
  external_record_id,
  request_hash,
  page_key,
  http_status,
  provider_result_code,
  fetched_at,
  source_modified_at,
  parser_version,
  payload_hash,
  request_metadata_redacted,
  raw_payload,
  parse_status
on public.external_api_snapshots
for each row
execute function public.protect_external_snapshot_identity();

alter table public.tour_place_sources
  add column l_dong_regn_cd text,
  add column l_dong_signgu_cd text,
  add column lcls_systm1 text,
  add column lcls_systm2 text,
  add column lcls_systm3 text,
  add constraint ck_tour_place_sources_latest_codes_nonblank
    check (
      (l_dong_regn_cd is null or btrim(l_dong_regn_cd) <> '')
      and (l_dong_signgu_cd is null or btrim(l_dong_signgu_cd) <> '')
      and (lcls_systm1 is null or btrim(lcls_systm1) <> '')
      and (lcls_systm2 is null or btrim(lcls_systm2) <> '')
      and (lcls_systm3 is null or btrim(lcls_systm3) <> '')
    );

comment on column public.tour_place_sources.l_dong_regn_cd
  is 'KorService2 lDongRegnCd 원문 코드';
comment on column public.tour_place_sources.l_dong_signgu_cd
  is 'KorService2 lDongSignguCd 원문 코드';
comment on column public.tour_place_sources.lcls_systm1
  is 'KorService2 lclsSystm1 원문 분류 코드';
comment on column public.tour_place_sources.lcls_systm2
  is 'KorService2 lclsSystm2 원문 분류 코드';
comment on column public.tour_place_sources.lcls_systm3
  is 'KorService2 lclsSystm3 원문 분류 코드';

-- 길이 prefix를 포함한 SHA-256으로 인덱스 key만 고정 길이화한다. 원문은 그대로
-- 보존하고 trigger가 동일 digest의 원문까지 비교하므로 collision을 덮어쓰지 않는다.
create function public.source_identity_digest(variadic components text[])
returns text
language plpgsql
immutable
security invoker
set search_path = pg_catalog, extensions, public
as $$
declare
  component text;
  digest_input bytea := ''::bytea;
begin
  foreach component in array components loop
    if component is null then
      digest_input := digest_input || pg_catalog.convert_to('-1:', 'UTF8');
    else
      digest_input := digest_input || pg_catalog.convert_to(
        pg_catalog.octet_length(component)::text || ':' || component,
        'UTF8'
      );
    end if;
  end loop;

  return pg_catalog.encode(digest(digest_input, 'sha256'), 'hex');
end;
$$;

revoke all on function public.source_identity_digest(text[]) from public;

do $$
begin
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'anon') then
    execute 'revoke execute on function public.source_identity_digest(text[]) from anon';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated') then
    execute 'revoke execute on function public.source_identity_digest(text[]) from authenticated';
  end if;
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role') then
    execute 'grant execute on function public.source_identity_digest(text[]) to service_role';
  end if;
end;
$$;

-- lineage trigger를 설치하기 전에 migration 자체의 legacy URL key backfill을 끝낸다.
alter table public.place_images
  add column source_url_key text;

with unique_legacy_urls as (
  select
    image.place_id,
    image.source_provider,
    image.source_service,
    image.image_url
  from public.place_images image
  group by
    image.place_id,
    image.source_provider,
    image.source_service,
    image.image_url
  having count(*) = 1
)
update public.place_images image
set source_url_key = public.source_identity_digest(
  image.place_id::text,
  image.source_provider,
  image.source_service,
  image.image_url
)
from unique_legacy_urls unique_url
where image.place_id = unique_url.place_id
  and image.source_provider = unique_url.source_provider
  and image.source_service = unique_url.source_service
  and image.image_url = unique_url.image_url;

drop index if exists public.uq_place_images_provider_url_without_source_id;
drop index if exists public.idx_place_images_provider_url_transition;

alter table public.place_images
  add constraint ck_place_images_source_url_key
    check (
      source_url_key is null
      or source_url_key = public.source_identity_digest(
        place_id::text,
        source_provider,
        source_service,
        image_url
      )
    ) not valid,
  add constraint ck_place_images_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(image_url) <= 8192
      and (
        source_image_id is null
        or octet_length(source_image_id) <= 512
      )
      and octet_length(source_provider)
        + octet_length(source_service)
        + coalesce(octet_length(source_image_id), 0) <= 1024
    ) not valid,
  add constraint uq_place_images_source_url_key
    unique (place_id, source_url_key);

create function public.normalized_lineage_is_optional(
  target_table text,
  normalized_row jsonb
)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select
    (
      normalized_row ? 'source_provider'
      and normalized_row ->> 'source_provider'
          in ('fixture', 'admin_upload', 'manual')
    )
    or (
      target_table = 'place_operating_hours'
      and normalized_row ->> 'source_kind' = 'manual'
    )
    or (
      target_table = 'place_aliases'
      and normalized_row ->> 'alias_type' = 'user_query'
    );
$$;

create function public.validate_normalized_source_lineage()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  normalized_row jsonb := to_jsonb(new);
  old_normalized_row jsonb;
  normalized_run_id uuid;
  old_normalized_run_id uuid;
  run_source_kind text;
  run_source_provider text;
  run_source_service text;
  run_source_operation text;
  old_snapshot_run_source_kind text;
  old_snapshot_run_source_provider text;
  old_normalized_run_source_kind text;
  old_normalized_run_source_provider text;
  lineage_optional boolean := false;
  old_lineage_optional boolean := false;
  old_origin_is_external boolean := false;
  snapshot_row public.external_api_snapshots%rowtype;
begin
  normalized_run_id := coalesce(
    (normalized_row ->> 'import_run_id')::uuid,
    (normalized_row ->> 'last_import_run_id')::uuid
  );

  if tg_op = 'UPDATE' then
    old_normalized_row := to_jsonb(old);
    old_lineage_optional := public.normalized_lineage_is_optional(
      tg_table_name,
      old_normalized_row
    );
    if old_lineage_optional then
      old_normalized_run_id := coalesce(
        (old_normalized_row ->> 'import_run_id')::uuid,
        (old_normalized_row ->> 'last_import_run_id')::uuid
      );

      if old_normalized_row ->> 'source_snapshot_id' is not null then
        select
          import_run.source_kind,
          import_run.source_provider
          into
            old_snapshot_run_source_kind,
            old_snapshot_run_source_provider
        from public.external_api_snapshots snapshot
        join public.data_import_runs import_run
          on import_run.id = snapshot.import_run_id
        where snapshot.id =
          (old_normalized_row ->> 'source_snapshot_id')::uuid
        for share of snapshot, import_run;
      end if;

      if old_normalized_run_id is not null then
        select
          import_run.source_kind,
          import_run.source_provider
          into
            old_normalized_run_source_kind,
            old_normalized_run_source_provider
        from public.data_import_runs import_run
        where import_run.id = old_normalized_run_id
        for share;
      end if;

      old_origin_is_external :=
        coalesce(
          old_snapshot_run_source_kind not in ('fixture', 'admin_upload'),
          false
        )
        or coalesce(
          old_snapshot_run_source_provider not in ('fixture', 'admin_upload'),
          false
        )
        or coalesce(
          old_normalized_run_source_kind not in ('fixture', 'admin_upload'),
          false
        )
        or coalesce(
          old_normalized_run_source_provider not in ('fixture', 'admin_upload'),
          false
        );
    end if;
  end if;

  lineage_optional := public.normalized_lineage_is_optional(
    tg_table_name,
    normalized_row
  );

  if tg_op = 'UPDATE'
     and not old_lineage_optional
     and lineage_optional then
    -- 외부 원본 행은 한 번의 UPDATE로 optional 표식과 lineage를 함께 제거할 수 없다.
    -- snapshot 보존기간 만료는 아래 pointer-only 예외만 허용한다.
    raise exception using
      errcode = '23514',
      message = 'external normalized row cannot become an optional lineage row';
  end if;

  if normalized_row ->> 'source_snapshot_id' is null then
    if tg_op = 'UPDATE' then
      if old_normalized_row ->> 'source_snapshot_id' is not null
         and not exists (
           select 1
           from public.external_api_snapshots snapshot
           where snapshot.id =
             (old_normalized_row ->> 'source_snapshot_id')::uuid
         )
         and normalized_row - array['source_snapshot_id', 'updated_at']
             = old_normalized_row - array['source_snapshot_id', 'updated_at'] then
        -- snapshot purge may clear only the source pointer; import-run lineage remains.
        return new;
      end if;

      if old_lineage_optional
         and old_origin_is_external
         and old_normalized_row ->> 'source_snapshot_id' is not null then
        -- marker가 이미 optional이어도 실제 외부 snapshot/run 출처를 우회할 수 없다.
        -- 살아 있는 snapshot의 pointer 제거와 내용 변경을 분리한 2단계 우회도 막는다.
        raise exception using
          errcode = '23514',
          message = 'snapshot-backed optional row cannot remove external lineage';
      end if;

      if old_normalized_row ->> 'source_snapshot_id' is null then
        if old_lineage_optional and lineage_optional then
          -- 수동·fixture·admin 예외 입력은 예외 성격을 유지하는 동안 편집 가능하다.
          -- run을 새로 연결했다면 아래 scope 검증까지 계속 진행한다.
          if normalized_run_id is null then
            return new;
          end if;
        elsif normalized_run_id is null
           and normalized_row - array['updated_at']
               = old_normalized_row - array['updated_at'] then
          -- 외부 legacy 행은 값이 완전히 같을 때만 유지 작업을 허용한다.
          return new;
        else
          -- 기존 external run의 유무와 관계없이 run 교체 또는 optional marker
          -- 전환은 snapshot 없는 repair가 아니다.
          raise exception using
            errcode = '23514',
            message = 'legacy lineage-free row content is immutable';
        end if;
      end if;
    end if;

    if normalized_run_id is not null then
      select
        import_run.source_kind,
        import_run.source_provider,
        import_run.source_service,
        import_run.source_operation
        into
          run_source_kind,
          run_source_provider,
          run_source_service,
          run_source_operation
      from public.data_import_runs import_run
      where import_run.id = normalized_run_id
      for share;

      if not found then
        raise exception using
          errcode = '23503',
          message = 'normalized source import run does not exist';
      end if;

      if lineage_optional
         and (
           run_source_kind not in ('fixture', 'admin_upload')
           or run_source_provider not in ('fixture', 'admin_upload')
         ) then
        raise exception using
          errcode = '23514',
          message = 'manual normalized row cannot use an external import run';
      end if;

      if lineage_optional
         and (
           (
             normalized_row ? 'source_provider'
             and normalized_row ->> 'source_provider'
                 is distinct from run_source_provider
           )
           or (
             normalized_row ? 'source_service'
             and normalized_row ->> 'source_service'
                 is distinct from run_source_service
           )
           or (
             normalized_row ? 'source_operation'
             and normalized_row ->> 'source_operation'
                 is distinct from run_source_operation
           )
         ) then
        raise exception using
          errcode = '23514',
          message = 'manual normalized row scope must match its import run';
      end if;
    end if;

    if lineage_optional then
      return new;
    end if;

    if tg_op = 'INSERT' then
      raise exception using
        errcode = '23514',
        message = 'normalized external source row requires a source snapshot and import run';
    end if;

    raise exception using
      errcode = '23514',
      message = 'normalized external source row requires a source snapshot and import run';
  end if;

  select snapshot.*
    into snapshot_row
  from public.external_api_snapshots snapshot
  where snapshot.id = (normalized_row ->> 'source_snapshot_id')::uuid
  for share;

  if not found then
    raise exception using
      errcode = '23503',
      message = 'normalized source snapshot does not exist';
  end if;

  if snapshot_row.parse_status not in ('parsed', 'tombstoned') then
    raise exception using
      errcode = '23514',
      message = 'normalized source row requires a parsed or tombstoned snapshot';
  end if;

  if normalized_run_id is null
     or normalized_run_id <> snapshot_row.import_run_id then
    raise exception using
      errcode = '23514',
      message = 'normalized source row must use the snapshot import run';
  end if;

  if normalized_row ? 'source_provider'
     and normalized_row ->> 'source_provider'
         is distinct from snapshot_row.source_provider then
    raise exception using
      errcode = '23514',
      message = 'normalized source row provider must match its snapshot';
  end if;

  if normalized_row ? 'source_service'
     and normalized_row ->> 'source_service'
         is distinct from snapshot_row.source_service then
    raise exception using
      errcode = '23514',
      message = 'normalized source row service must match its snapshot';
  end if;

  if normalized_row ? 'source_operation'
     and normalized_row ->> 'source_operation'
         is distinct from snapshot_row.source_operation then
    raise exception using
      errcode = '23514',
      message = 'normalized source row operation must match its snapshot';
  end if;

  return new;
end;
$$;

create function public.prevent_duplicate_place_image_source()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  computed_source_url_key text;
begin
  computed_source_url_key := public.source_identity_digest(
    new.place_id::text,
    new.source_provider,
    new.source_service,
    new.image_url
  );
  new.source_url_key := computed_source_url_key;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      new.place_id::text || '|' || computed_source_url_key,
      0
    )
  );

  if exists (
    select 1
    from public.place_images image
    where image.place_id = new.place_id
      and image.source_url_key = computed_source_url_key
      and image.id <> new.id
      and (
        image.source_provider is distinct from new.source_provider
        or image.source_service is distinct from new.source_service
        or image.image_url is distinct from new.image_url
      )
  ) then
    raise exception using
      errcode = '23505',
      message = 'place image source digest collision';
  end if;

  -- 중복이 이미 존재할 수 있는 v1 legacy 그룹만 trigger로 차단한다.
  -- 정상 행은 declarative unique constraint가 ON CONFLICT arbiter가 된다.
  if exists (
    select 1
    from public.place_images image
    where image.place_id = new.place_id
      and image.source_provider = new.source_provider
      and image.source_service = new.source_service
      and image.image_url = new.image_url
      and image.source_url_key is null
      and image.id <> new.id
  ) then
    raise exception using
      errcode = '23505',
      message = 'place image source URL already exists';
  end if;

  return new;
end;
$$;

create trigger trg_place_images_source_url_unique
before insert or update of place_id, source_provider, source_service, image_url,
  source_image_id, source_url_key
on public.place_images
for each row execute function public.prevent_duplicate_place_image_source();

do $$
declare
  conflict_pair record;
begin
  select left_code.id as left_id, right_code.id as right_id
    into conflict_pair
  from public.external_reference_codes left_code
  join public.external_reference_codes right_code
    on left_code.id < right_code.id
   and left_code.source_provider = right_code.source_provider
   and left_code.source_service = right_code.source_service
   and left_code.code_type = right_code.code_type
   and left_code.external_code = right_code.external_code
   and daterange(
     left_code.valid_from,
     coalesce(left_code.valid_to, 'infinity'::date),
     '[]'
   ) && daterange(
     right_code.valid_from,
     coalesce(right_code.valid_to, 'infinity'::date),
     '[]'
   )
  order by left_code.id, right_code.id
  limit 1;

  if found then
    raise exception using
      errcode = '23P01',
      message = 'legacy external reference validity overlap audit failed',
      detail = pg_catalog.format(
        'left_id=%s, right_id=%s',
        conflict_pair.left_id,
        conflict_pair.right_id
      );
  end if;
end;
$$;

alter table public.external_reference_codes
  add constraint ex_external_reference_codes_no_validity_overlap
    exclude using gist (
      source_provider with =,
      source_service with =,
      code_type with =,
      external_code with =,
      daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&
    );

create function public.protect_transport_source_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.source_provider is distinct from new.source_provider
     or old.source_service is distinct from new.source_service
     or old.city_code is distinct from new.city_code then
    raise exception using
      errcode = '23514',
      message = 'transport source scope is immutable';
  end if;

  return new;
end;
$$;

create trigger trg_bus_routes_source_scope_immutable
before update of source_provider, source_service, city_code
on public.bus_routes
for each row execute function public.protect_transport_source_scope();

create trigger trg_bus_stops_source_scope_immutable
before update of source_provider, source_service, city_code
on public.bus_stops
for each row execute function public.protect_transport_source_scope();

alter table public.route_stops
  add column source_provider text,
  add column city_code text;

update public.route_stops route_stop
set source_provider = route.source_provider,
    city_code = route.city_code
from public.bus_routes route
where route.id = route_stop.route_id;

alter table public.route_stops
  alter column source_provider set not null,
  alter column city_code set not null,
  add constraint ck_route_stops_source_provider_nonblank
    check (btrim(source_provider) <> '') not valid,
  add constraint ck_route_stops_city_code_nonblank
    check (btrim(city_code) <> ''),
  add constraint ck_route_stops_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(city_code) <= 64
      and octet_length(direction_key) <= 512
      and octet_length(source_provider)
        + octet_length(city_code)
        + octet_length(direction_key) <= 1024
    ) not valid;

create function public.validate_route_stop_source_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  perform route.id
  from public.bus_routes route
  where route.id = new.route_id
    and route.source_provider = new.source_provider
    and route.city_code = new.city_code
  for key share;

  if not found then
    raise exception using
      errcode = '23514',
      message = 'route stop source scope must match its route';
  end if;

  perform stop.id
  from public.bus_stops stop
  where stop.id = new.stop_id
    and stop.source_provider = new.source_provider
    and stop.city_code = new.city_code
  for key share;

  if not found then
    raise exception using
      errcode = '23514',
      message = 'route stop source scope must match its stop';
  end if;

  return new;
end;
$$;

create trigger trg_route_stops_validate_source_scope
before insert or update
on public.route_stops
for each row execute function public.validate_route_stop_source_scope();

create index idx_route_stops_route_provider_city
  on public.route_stops (route_id, source_provider, city_code)
  where octet_length(source_provider) <= 128
    and octet_length(city_code) <= 64
    and octet_length(source_provider) + octet_length(city_code) <= 512;
create index idx_route_stops_stop_provider_city
  on public.route_stops (stop_id, source_provider, city_code)
  where octet_length(source_provider) <= 128
    and octet_length(city_code) <= 64
    and octet_length(source_provider) + octet_length(city_code) <= 512;

alter table public.timetable_entries
  add column source_service text not null default 'legacy',
  add column city_code text,
  add constraint ck_timetable_source_service_nonblank
    check (btrim(source_service) <> '');

update public.timetable_entries timetable
set city_code = route_stop.city_code
from public.route_stops route_stop
where route_stop.route_id = timetable.route_id
  and route_stop.direction_key = timetable.direction_key
  and route_stop.stop_id = timetable.stop_id
  and route_stop.source_provider = timetable.source_provider;

alter table public.timetable_entries
  add constraint ck_timetable_city_code_nonblank
    check (btrim(city_code) <> ''),
  add constraint ck_timetable_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(city_code) <= 64
      and octet_length(direction_key) <= 512
      and octet_length(source_record_key) <= 512
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(city_code)
        + octet_length(direction_key)
        + octet_length(source_record_key) <= 1800
    ) not valid;

create function public.validate_timetable_source_scope()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and old.city_code is null and new.city_code is null then
    if to_jsonb(old) is not distinct from to_jsonb(new) then
      return new;
    end if;

    raise exception using
      errcode = '23514',
      message = 'legacy timetable source identity is immutable';
  end if;

  if new.city_code is null then
    raise exception using
      errcode = '23502',
      message = 'new timetable row requires provider city scope';
  end if;

  perform route_stop.route_id
  from public.route_stops route_stop
  join public.bus_routes route
    on route.id = route_stop.route_id
  join public.bus_stops stop
    on stop.id = route_stop.stop_id
  where route_stop.route_id = new.route_id
    and route_stop.direction_key = new.direction_key
    and route_stop.stop_id = new.stop_id
    and route_stop.source_provider = new.source_provider
    and route_stop.city_code = new.city_code
    and route.source_provider = route_stop.source_provider
    and route.city_code = route_stop.city_code
    and stop.source_provider = route_stop.source_provider
    and stop.city_code = route_stop.city_code
  for key share of route_stop, route, stop;

  if not found then
    raise exception using
      errcode = '23514',
      message = 'timetable source scope must match a valid route stop';
  end if;

  return new;
end;
$$;

create trigger trg_timetable_entries_validate_source_scope
before insert or update
on public.timetable_entries
for each row execute function public.validate_timetable_source_scope();

drop index public.uq_timetable_provider_source_record_validity;

create unique index uq_timetable_source_scope_record_validity
  on public.timetable_entries (
    source_provider,
    source_service,
    city_code,
    source_record_key,
    valid_from
  )
  where city_code is not null
    and octet_length(source_provider) <= 128
    and octet_length(source_service) <= 128
    and octet_length(city_code) <= 64
    and octet_length(source_record_key) <= 512
    and octet_length(source_provider)
      + octet_length(source_service)
      + octet_length(city_code)
      + octet_length(source_record_key) <= 1800;

create index idx_timetable_route_stop_source_scope
  on public.timetable_entries (
    route_id,
    direction_key,
    stop_id,
    source_provider,
    city_code
  )
  where city_code is not null
    and octet_length(direction_key) <= 512
    and octet_length(source_provider) <= 128
    and octet_length(city_code) <= 64
    and octet_length(direction_key)
      + octet_length(source_provider)
      + octet_length(city_code) <= 1024;

do $$
declare
  conflict_pair record;
begin
  select left_entry.id as left_id, right_entry.id as right_id
    into conflict_pair
  from public.timetable_entries left_entry
  join public.timetable_entries right_entry
    on left_entry.id < right_entry.id
   and left_entry.source_provider = right_entry.source_provider
   and left_entry.source_service = right_entry.source_service
   and left_entry.city_code = right_entry.city_code
   and left_entry.source_record_key = right_entry.source_record_key
   and daterange(
     left_entry.valid_from,
     coalesce(left_entry.valid_to, 'infinity'::date),
     '[]'
   ) && daterange(
     right_entry.valid_from,
     coalesce(right_entry.valid_to, 'infinity'::date),
     '[]'
   )
  where left_entry.city_code is not null
    and pg_catalog.octet_length(left_entry.source_provider) <= 128
    and pg_catalog.octet_length(left_entry.source_service) <= 128
    and pg_catalog.octet_length(left_entry.city_code) <= 64
    and pg_catalog.octet_length(left_entry.source_record_key) <= 512
    and pg_catalog.octet_length(right_entry.source_provider) <= 128
    and pg_catalog.octet_length(right_entry.source_service) <= 128
    and pg_catalog.octet_length(right_entry.city_code) <= 64
    and pg_catalog.octet_length(right_entry.source_record_key) <= 512
  order by left_entry.id, right_entry.id
  limit 1;

  if found then
    raise exception using
      errcode = '23P01',
      message = 'legacy timetable validity overlap audit failed',
      detail = pg_catalog.format(
        'left_id=%s, right_id=%s',
        conflict_pair.left_id,
        conflict_pair.right_id
      );
  end if;
end;
$$;

alter table public.timetable_entries
  add constraint ex_timetable_source_scope_no_validity_overlap
    exclude using gist (
      source_provider with =,
      source_service with =,
      city_code with =,
      source_record_key with =,
      daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&
    )
    where (
      city_code is not null
      and octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(city_code) <= 64
      and octet_length(source_record_key) <= 512
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(city_code)
        + octet_length(source_record_key) <= 1800
    );

do $$
declare
  conflict_pair record;
begin
  select left_hours.id as left_id, right_hours.id as right_id
    into conflict_pair
  from public.place_operating_hours left_hours
  join public.place_operating_hours right_hours
    on left_hours.id < right_hours.id
   and left_hours.place_id = right_hours.place_id
   and left_hours.day_of_week = right_hours.day_of_week
   and left_hours.is_closed <> right_hours.is_closed
   and daterange(
     left_hours.valid_from,
     coalesce(left_hours.valid_to, 'infinity'::date),
     '[]'
   ) && daterange(
     right_hours.valid_from,
     coalesce(right_hours.valid_to, 'infinity'::date),
     '[]'
   )
  order by left_hours.id, right_hours.id
  limit 1;

  if found then
    raise exception using
      errcode = '23P01',
      message = 'legacy operating hours open-closed overlap audit failed',
      detail = pg_catalog.format(
        'left_id=%s, right_id=%s',
        conflict_pair.left_id,
        conflict_pair.right_id
      );
  end if;
end;
$$;

alter table public.place_operating_hours
  add constraint ck_place_hours_last_entry_within_interval
    check (
      is_closed
      or last_entry_time is null
      or (
        not spans_next_day
        and last_entry_time >= open_time
        and last_entry_time <= close_time
      )
      or (
        spans_next_day
        and (last_entry_time >= open_time or last_entry_time <= close_time)
      )
    ) not valid,
  add constraint ex_place_hours_no_open_closed_conflict
    exclude using gist (
      place_id with =,
      day_of_week with =,
      daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&,
      is_closed with <>
    );

create function public.validate_place_hours_cross_day_overlap()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  -- 단일 행 CHECK가 먼저 설명해야 하는 잘못된 입력은 교차 행 검사에서 건드리지 않는다.
  if new.valid_to is not null and new.valid_to < new.valid_from then
    return new;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(new.place_id::text, 0)
  );

  if not new.is_closed
     and new.spans_next_day
     and exists (
       select 1
       from public.place_operating_hours existing
       where existing.place_id = new.place_id
         and existing.id <> new.id
         and existing.day_of_week = (new.day_of_week + 1) % 7
         and daterange(
           new.valid_from,
           coalesce(new.valid_to, 'infinity'::date),
           '[]'
         ) && daterange(
           existing.valid_from - 1,
           coalesce(existing.valid_to, 'infinity'::date) - 1,
           '[]'
         )
         and (
           (existing.is_closed and new.close_time > time '00:00')
           or existing.open_time < new.close_time
         )
     ) then
    raise exception using
      errcode = '23P01',
      message = 'overnight operating hours overlap the next service day';
  end if;

  if exists (
    select 1
    from public.place_operating_hours existing
    where existing.place_id = new.place_id
      and existing.id <> new.id
      and not existing.is_closed
      and existing.spans_next_day
      and new.day_of_week = (existing.day_of_week + 1) % 7
      and daterange(
        existing.valid_from,
        coalesce(existing.valid_to, 'infinity'::date),
        '[]'
      ) && daterange(
        new.valid_from - 1,
        coalesce(new.valid_to, 'infinity'::date) - 1,
        '[]'
      )
         and (
           (new.is_closed and existing.close_time > time '00:00')
           or new.open_time < existing.close_time
         )
  ) then
    raise exception using
      errcode = '23P01',
      message = 'operating hours overlap the previous overnight service day';
  end if;

  return new;
end;
$$;

-- v1에서 합법이었던 자정 넘김 행을 의미 보강한 뒤, 최신 규칙과 충돌하는
-- 기존 조합은 조용히 보존하거나 재작성하지 않고 migration을 명시적으로 중단한다.
do $$
declare
  conflict_row record;
begin
  select
    overnight.id as overnight_id,
    next_day.id as next_day_id
    into conflict_row
  from public.place_operating_hours overnight
  join public.place_operating_hours next_day
    on next_day.place_id = overnight.place_id
   and next_day.id <> overnight.id
   and next_day.day_of_week = (overnight.day_of_week + 1) % 7
   and daterange(
         overnight.valid_from,
         coalesce(overnight.valid_to, 'infinity'::date),
         '[]'
       ) && daterange(
         next_day.valid_from - 1,
         coalesce(next_day.valid_to, 'infinity'::date) - 1,
         '[]'
       )
  where not overnight.is_closed
    and overnight.spans_next_day
    and (
      (next_day.is_closed and overnight.close_time > time '00:00')
      or next_day.open_time < overnight.close_time
    )
  limit 1;

  if found then
    raise exception using
      errcode = '23P01',
      message = 'legacy operating hours failed cross-day overlap audit',
      detail = pg_catalog.format(
        'overnight_id=%s, next_day_id=%s',
        conflict_row.overnight_id,
        conflict_row.next_day_id
      );
  end if;
end;
$$;

create trigger trg_place_hours_cross_day_overlap
before insert or update of
  place_id,
  day_of_week,
  is_closed,
  open_time,
  close_time,
  spans_next_day,
  valid_from,
  valid_to
on public.place_operating_hours
for each row execute function public.validate_place_hours_cross_day_overlap();

-- v1의 빈 키는 업그레이드 시 보존하되, 최신 적재의 natural key와 URL은
-- 빈 문자열로 생성하거나 변경할 수 없다.
alter table public.bus_stops
  add constraint ck_bus_stops_node_id_nonblank
    check (btrim(node_id) <> '') not valid,
  add constraint ck_bus_stops_external_stop_id_nonblank
    check (external_stop_id is null or btrim(external_stop_id) <> '') not valid,
  add constraint ck_bus_stops_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and btrim(source_service) <> ''
    ) not valid;

alter table public.bus_routes
  add constraint ck_bus_routes_external_route_id_nonblank
    check (external_route_id is null or btrim(external_route_id) <> '') not valid,
  add constraint ck_bus_routes_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and btrim(source_service) <> ''
    ) not valid;

alter table public.place_images
  add constraint ck_place_images_image_url_nonblank
    check (btrim(image_url) <> '') not valid,
  add constraint ck_place_images_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and btrim(source_service) <> ''
    ) not valid;

alter table public.tour_places
  add constraint ck_tour_places_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and (source_service is null or btrim(source_service) <> '')
      and (external_place_id is null or btrim(external_place_id) <> '')
      and (content_id is null or btrim(content_id) <> '')
    ) not valid;

alter table public.place_details
  add constraint ck_place_details_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and (source_service is null or btrim(source_service) <> '')
    ) not valid;

alter table public.timetable_entries
  add constraint ck_timetable_source_provider_nonblank
    check (btrim(source_provider) <> '') not valid,
  add constraint ck_timetable_direction_key_nonblank
    check (btrim(direction_key) <> '') not valid;

alter table public.route_stops
  add constraint ck_route_stops_direction_key_nonblank
    check (btrim(direction_key) <> '') not valid;

alter table public.weather_observations
  add constraint ck_weather_observations_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and btrim(source_operation) <> ''
    ) not valid;

alter table public.weather_forecasts
  add constraint ck_weather_forecasts_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and btrim(source_operation) <> ''
    ) not valid;

alter table public.bus_arrival_snapshots
  add constraint ck_bus_arrivals_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and (source_operation is null or btrim(source_operation) <> '')
      and (external_route_id is null or btrim(external_route_id) <> '')
    ) not valid;

alter table public.mobility_route_snapshots
  add constraint ck_mobility_routes_source_scope_nonblank
    check (
      btrim(source_provider) <> ''
      and btrim(source_operation) <> ''
    ) not valid;

-- 모든 legacy backfill이 끝난 뒤부터 신규/변경 정규화 행에 strict lineage를 적용한다.
create constraint trigger trg_tour_places_source_lineage
after insert or update on public.tour_places
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_tour_place_sources_source_lineage
after insert or update on public.tour_place_sources
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_details_source_lineage
after insert or update on public.place_details
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_detail_items_source_lineage
after insert or update on public.place_detail_items
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_hours_source_lineage
after insert or update on public.place_operating_hours
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_aliases_source_lineage
after insert or update on public.place_aliases
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_place_images_source_lineage
after insert or update on public.place_images
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_reference_codes_source_lineage
after insert or update on public.external_reference_codes
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_bus_stops_source_lineage
after insert or update on public.bus_stops
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_bus_routes_source_lineage
after insert or update on public.bus_routes
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_route_stops_source_lineage
after insert or update on public.route_stops
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_timetable_source_lineage
after insert or update on public.timetable_entries
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_weather_observations_source_lineage
after insert or update on public.weather_observations
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_weather_forecasts_source_lineage
after insert or update on public.weather_forecasts
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_bus_arrivals_source_lineage
after insert or update on public.bus_arrival_snapshots
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

create constraint trigger trg_mobility_routes_source_lineage
after insert or update on public.mobility_route_snapshots
deferrable initially immediate
for each row execute function public.validate_normalized_source_lineage();

-- .100에서 non-null snapshot/run을 연결한 행은 당시 strict lineage trigger의
-- 소급 검사를 받지 않았다. 신규/변경 guard를 모두 설치한 뒤 16개 정규화
-- 테이블을 감사하고, 위반을 조용히 NULL 처리하지 않고 원본 식별자로 중단한다.
do $$
declare
  target_table text;
  invalid_lineage record;
  row_id text;
  normalized_run_id text;
  audit_row_count bigint;
begin
  foreach target_table in array array[
    'tour_places',
    'tour_place_sources',
    'place_details',
    'place_detail_items',
    'place_operating_hours',
    'place_aliases',
    'place_images',
    'external_reference_codes',
    'bus_stops',
    'bus_routes',
    'route_stops',
    'timetable_entries',
    'weather_observations',
    'weather_forecasts',
    'bus_arrival_snapshots',
    'mobility_route_snapshots'
  ]
  loop
    execute pg_catalog.format(
      $audit$
        select
          to_jsonb(source_row) as normalized_row,
          snapshot.import_run_id as snapshot_run_id,
          snapshot.parse_status,
          snapshot.source_provider as snapshot_provider,
          snapshot.source_service as snapshot_service,
          snapshot.source_operation as snapshot_operation,
          snapshot_import_run.source_kind as snapshot_run_source_kind,
          snapshot_import_run.source_provider as snapshot_run_source_provider,
          normalized_import_run.source_kind as normalized_run_source_kind,
          normalized_import_run.source_provider as normalized_run_source_provider
        from public.%I source_row
        left join public.external_api_snapshots snapshot
          on snapshot.id = nullif(
            to_jsonb(source_row) ->> 'source_snapshot_id', ''
          )::uuid
        left join public.data_import_runs snapshot_import_run
          on snapshot_import_run.id = snapshot.import_run_id
        left join public.data_import_runs normalized_import_run
          on normalized_import_run.id = coalesce(
            nullif(to_jsonb(source_row) ->> 'import_run_id', ''),
            nullif(to_jsonb(source_row) ->> 'last_import_run_id', '')
          )::uuid
        where nullif(
                to_jsonb(source_row) ->> 'source_snapshot_id', ''
              ) is not null
          and (
            snapshot.id is null
            or snapshot.parse_status not in ('parsed', 'tombstoned')
            or coalesce(
                 nullif(to_jsonb(source_row) ->> 'import_run_id', ''),
                 nullif(to_jsonb(source_row) ->> 'last_import_run_id', '')
               ) is null
            or coalesce(
                 nullif(to_jsonb(source_row) ->> 'import_run_id', ''),
                 nullif(to_jsonb(source_row) ->> 'last_import_run_id', '')
               )::uuid is distinct from snapshot.import_run_id
            or (
              to_jsonb(source_row) ? 'source_provider'
              and to_jsonb(source_row) ->> 'source_provider'
                  is distinct from snapshot.source_provider
            )
            or (
              to_jsonb(source_row) ? 'source_service'
              and to_jsonb(source_row) ->> 'source_service'
                  is distinct from snapshot.source_service
            )
            or (
              to_jsonb(source_row) ? 'source_operation'
              and to_jsonb(source_row) ->> 'source_operation'
                  is distinct from snapshot.source_operation
            )
            or (
              public.normalized_lineage_is_optional(
                %L,
                to_jsonb(source_row)
              )
              and (
                snapshot_import_run.source_kind
                  not in ('fixture', 'admin_upload')
                or snapshot_import_run.source_provider
                  not in ('fixture', 'admin_upload')
                or normalized_import_run.source_kind
                  not in ('fixture', 'admin_upload')
                or normalized_import_run.source_provider
                  not in ('fixture', 'admin_upload')
              )
            )
          )
        limit 1
      $audit$,
      target_table,
      target_table
    ) into invalid_lineage;

    get diagnostics audit_row_count = row_count;

    if audit_row_count > 0 then
      if target_table = 'place_details' then
        row_id := invalid_lineage.normalized_row ->> 'place_id';
      elsif target_table = 'route_stops' then
        row_id := pg_catalog.concat_ws(
          '/',
          invalid_lineage.normalized_row ->> 'route_id',
          invalid_lineage.normalized_row ->> 'direction_key',
          invalid_lineage.normalized_row ->> 'stop_sequence'
        );
      else
        row_id := invalid_lineage.normalized_row ->> 'id';
      end if;

      normalized_run_id := coalesce(
        invalid_lineage.normalized_row ->> 'import_run_id',
        invalid_lineage.normalized_row ->> 'last_import_run_id'
      );

      raise exception using
        errcode = '23514',
        message = 'legacy normalized source lineage audit failed',
        detail = pg_catalog.format(
          'table=%s, row_id=%s, source_snapshot_id=%s, normalized_run_id=%s, snapshot_run_id=%s, parse_status=%s, normalized_scope=%s/%s/%s, snapshot_scope=%s/%s/%s, normalized_run_origin=%s/%s, snapshot_run_origin=%s/%s',
          target_table,
          row_id,
          invalid_lineage.normalized_row ->> 'source_snapshot_id',
          normalized_run_id,
          invalid_lineage.snapshot_run_id,
          invalid_lineage.parse_status,
          invalid_lineage.normalized_row ->> 'source_provider',
          invalid_lineage.normalized_row ->> 'source_service',
          invalid_lineage.normalized_row ->> 'source_operation',
          invalid_lineage.snapshot_provider,
          invalid_lineage.snapshot_service,
          invalid_lineage.snapshot_operation,
          invalid_lineage.normalized_run_source_kind,
          invalid_lineage.normalized_run_source_provider,
          invalid_lineage.snapshot_run_source_kind,
          invalid_lineage.snapshot_run_source_provider
        );
    end if;
  end loop;
end;
$$;
