-- Issue #170: 일정 보정 접수와 worker 구현 전에 독립 run identity/lifecycle을 제공한다.
-- 요청 원문, structured command input, 정밀 위치와 MCP call log는 후속 Issue가 소유한다.

alter table public.trip_plans
  add constraint uq_trip_plans_user_identity unique (id, user_id);

create table public.schedule_revision_runs (
  id uuid primary key default gen_random_uuid(),
  owner_user_id uuid not null,
  trip_plan_id uuid not null,
  base_schedule_version_id uuid not null,
  target_trip_day_id uuid not null,
  status varchar(16) not null default 'queued'
    check (status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  contract_version varchar(64) not null,
  algorithm_version varchar(64) not null,
  idempotency_key uuid not null,
  request_hash char(64) not null,
  failure_code varchar(100),
  attempt_count integer not null default 0,
  fencing_token bigint not null default 0,
  lease_owner text,
  lease_expires_at timestamptz,
  heartbeat_at timestamptz,
  next_attempt_at timestamptz default now(),
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fk_schedule_revision_runs_owner_trip
    foreign key (trip_plan_id, owner_user_id)
    references public.trip_plans (id, user_id)
    on delete cascade,
  constraint fk_schedule_revision_runs_base_schedule
    foreign key (base_schedule_version_id, trip_plan_id)
    references public.trip_schedule_versions (id, trip_plan_id),
  constraint fk_schedule_revision_runs_target_day
    foreign key (target_trip_day_id, trip_plan_id)
    references public.trip_days (id, trip_plan_id),
  constraint uq_schedule_revision_runs_identity
    unique (id, owner_user_id, trip_plan_id, base_schedule_version_id, target_trip_day_id),
  constraint uq_schedule_revision_runs_idempotency
    unique (owner_user_id, trip_plan_id, idempotency_key),
  constraint chk_schedule_revision_runs_request_hash
    check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint chk_schedule_revision_runs_versions
    check (
      btrim(contract_version) <> ''
      and btrim(algorithm_version) <> ''
    ),
  constraint chk_schedule_revision_runs_failure_code
    check (
      (status = 'queued' and attempt_count = 0 and failure_code is null)
      or (
        status = 'queued' and attempt_count > 0
        and failure_code is not null and btrim(failure_code) <> ''
      )
      or (
        status in ('failed', 'cancelled')
        and failure_code is not null and btrim(failure_code) <> ''
      )
      or (status in ('running', 'succeeded') and failure_code is null)
    ),
  constraint chk_schedule_revision_runs_attempt_count
    check (attempt_count between 0 and 5),
  constraint chk_schedule_revision_runs_fencing_token
    check (fencing_token >= 0),
  constraint chk_schedule_revision_runs_worker_fields
    check (
      (
        status = 'running'
        and lease_owner is not null
        and btrim(lease_owner) <> ''
        and lease_expires_at is not null
        and heartbeat_at is not null
        and next_attempt_at is null
      )
      or
      (
        status <> 'running'
        and lease_owner is null
        and lease_expires_at is null
        and heartbeat_at is null
      )
    ),
  constraint chk_schedule_revision_runs_execution_phase
    check (
      (
        status = 'queued'
        and started_at is null
        and completed_at is null
        and next_attempt_at is not null
      )
      or
      (
        status = 'running'
        and started_at is not null
        and completed_at is null
      )
      or
      (
        status = 'succeeded'
        and started_at is not null
        and completed_at is not null
        and next_attempt_at is null
      )
      or
      (
        status in ('failed', 'cancelled')
        and completed_at is not null
        and next_attempt_at is null
      )
    ),
  constraint chk_schedule_revision_runs_lease_time_order
    check (lease_expires_at is null or lease_expires_at > heartbeat_at),
  constraint chk_schedule_revision_runs_completion_time_order
    check (completed_at is null or started_at is null or completed_at >= started_at)
);

create unique index uq_schedule_revision_runs_active_scope
  on public.schedule_revision_runs
    (owner_user_id, trip_plan_id, base_schedule_version_id, target_trip_day_id)
  where status in ('queued', 'running');

create index idx_schedule_revision_runs_trip_owner_fk
  on public.schedule_revision_runs (trip_plan_id, owner_user_id);

create index idx_schedule_revision_runs_worker_claim
  on public.schedule_revision_runs (next_attempt_at, created_at, id)
  where status = 'queued' and attempt_count < 5;

create index idx_schedule_revision_runs_worker_recovery
  on public.schedule_revision_runs (lease_expires_at, created_at, id)
  where status = 'running';

create index idx_schedule_revision_runs_owner_created
  on public.schedule_revision_runs (owner_user_id, created_at desc, id);

create index idx_schedule_revision_runs_trip_created
  on public.schedule_revision_runs (trip_plan_id, created_at desc, id);

create index idx_schedule_revision_runs_base_schedule
  on public.schedule_revision_runs (base_schedule_version_id, trip_plan_id);

create index idx_schedule_revision_runs_target_day
  on public.schedule_revision_runs (target_trip_day_id, trip_plan_id);

create function public.protect_schedule_revision_run_lifecycle()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'queued'
       or new.attempt_count <> 0
       or new.fencing_token <> 0 then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run must be created as queued';
    end if;
    return new;
  end if;

  if old.id is distinct from new.id
     or old.owner_user_id is distinct from new.owner_user_id
     or old.trip_plan_id is distinct from new.trip_plan_id
     or old.base_schedule_version_id is distinct from new.base_schedule_version_id
     or old.target_trip_day_id is distinct from new.target_trip_day_id
     or old.contract_version is distinct from new.contract_version
     or old.algorithm_version is distinct from new.algorithm_version
     or old.idempotency_key is distinct from new.idempotency_key
     or old.request_hash is distinct from new.request_hash
     or old.created_at is distinct from new.created_at then
    raise exception using
      errcode = '23514',
      message = 'schedule revision run identity is immutable';
  end if;

  if old.status in ('succeeded', 'failed', 'cancelled') then
    raise exception using
      errcode = '23514',
      message = 'schedule revision run terminal status is immutable';
  end if;

  if (old.status = 'queued' and new.status not in ('queued', 'running', 'failed', 'cancelled'))
     or (old.status = 'running'
         and new.status not in ('running', 'queued', 'succeeded', 'failed', 'cancelled')) then
    raise exception using
      errcode = '23514',
      message = 'schedule revision run status transition is invalid';
  end if;

  if old.status = 'queued' and new.status = 'queued' then
    if new.attempt_count <> old.attempt_count
       or new.fencing_token <> old.fencing_token
       or new.failure_code is distinct from old.failure_code then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run queued retry state is immutable';
    end if;
  elsif old.status = 'queued' and new.status = 'running' then
    if new.attempt_count <> old.attempt_count + 1
       or new.fencing_token <> old.fencing_token + 1 then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run claim must advance attempt and fencing token exactly once';
    end if;
  elsif old.status = 'queued' and new.status in ('failed', 'cancelled') then
    if new.attempt_count <> old.attempt_count
       or new.fencing_token <> old.fencing_token then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run terminal transition must preserve fencing counters';
    end if;
  elsif old.status = 'running' and new.status = 'running' then
    if new.attempt_count = old.attempt_count
       and new.fencing_token = old.fencing_token
       and new.lease_owner is not distinct from old.lease_owner then
      if old.lease_expires_at <= statement_timestamp()
         or new.heartbeat_at < old.heartbeat_at
         or new.lease_expires_at <= old.lease_expires_at
         or new.started_at is distinct from old.started_at then
        raise exception using
          errcode = '23514',
          message = 'schedule revision run heartbeat cannot change owner or fencing counters';
      end if;
    elsif new.attempt_count = old.attempt_count + 1
          and new.fencing_token = old.fencing_token + 1 then
      if old.lease_expires_at > statement_timestamp() then
        raise exception using
          errcode = '23514',
          message = 'schedule revision run live lease cannot be reclaimed';
      end if;
      if new.started_at is distinct from old.started_at then
        raise exception using
          errcode = '23514',
          message = 'schedule revision run reclaim must retain execution start';
      end if;
    else
      raise exception using
        errcode = '23514',
        message = 'schedule revision run heartbeat cannot change owner or fencing counters';
    end if;
  elsif old.status = 'running' and new.status = 'queued' then
    if old.attempt_count = 5 then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run fifth attempt cannot be retried';
    elsif old.attempt_count < 5
          and (
            old.lease_expires_at <= statement_timestamp()
            or new.attempt_count <> old.attempt_count
            or new.fencing_token <> old.fencing_token
            or new.failure_code is null
          ) then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run retry must preserve fencing counters and failure code';
    end if;
  elsif old.status = 'running'
        and new.status = 'failed'
        and old.attempt_count = 5
        and old.lease_expires_at <= statement_timestamp() then
    if new.attempt_count <> old.attempt_count
       or new.fencing_token <> old.fencing_token
       or new.started_at is distinct from old.started_at
       or not (
         new.failure_code = 'ASYNC_RUN_RETRY_EXHAUSTED'
         and new.failure_code is not null
       ) then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run exhausted recovery requires expired fifth attempt';
    end if;
  elsif old.status = 'running'
        and new.status in ('succeeded', 'failed', 'cancelled') then
    if old.lease_expires_at <= statement_timestamp()
       or new.attempt_count <> old.attempt_count
       or new.fencing_token <> old.fencing_token
       or new.started_at is distinct from old.started_at then
      raise exception using
        errcode = '23514',
        message = 'schedule revision run terminal transition must preserve fencing counters';
    end if;
  end if;

  new.updated_at := now();
  return new;
end;
$$;

create trigger trg_schedule_revision_runs_lifecycle
before insert or update
on public.schedule_revision_runs
for each row execute function public.protect_schedule_revision_run_lifecycle();

alter table public.schedule_revision_runs enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.schedule_revision_runs from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.schedule_revision_runs from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'grant select, insert, update, delete on public.schedule_revision_runs to service_role';
  end if;
end $$;
