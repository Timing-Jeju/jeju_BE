-- Issue #74 공통 async run worker의 lease, fencing, retry, 복구 상태를 추가한다.

alter table public.compute_runs
  drop constraint compute_runs_status_check;

alter table public.compute_runs
  alter column facts_snapshot_at drop not null,
  alter column source_data_version drop not null,
  add column result_source text,
  add column attempt_count integer not null default 0,
  add column fencing_token bigint not null default 0,
  add column lease_owner text,
  add column lease_expires_at timestamptz,
  add column heartbeat_at timestamptz,
  add column next_attempt_at timestamptz;

update public.compute_runs
set result_source = case when status = 'fallback' then 'fallback' else 'computed' end
where status in ('succeeded', 'fallback');

update public.compute_runs
set status = 'succeeded'
where status = 'fallback';

update public.compute_runs
set next_attempt_at = created_at
where status = 'queued';

-- 배포 중 발견되는 legacy running은 즉시 만료 lease로 표시해 새 worker가 복구한다.
update public.compute_runs
set lease_owner = 'migration-recovery',
    lease_expires_at = now(),
    heartbeat_at = now() - interval '1 second'
where status = 'running';

alter table public.compute_runs
  add constraint compute_runs_status_check
    check (status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  add constraint chk_compute_runs_attempt_count
    check (attempt_count between 0 and 5),
  add constraint chk_compute_runs_fencing_token
    check (fencing_token >= 0),
  add constraint chk_compute_runs_worker_fields
    check (
      (status = 'running'
        and lease_owner is not null
        and btrim(lease_owner) <> ''
        and lease_expires_at is not null
        and heartbeat_at is not null
        and next_attempt_at is null)
      or
      (status <> 'running'
        and lease_owner is null
        and lease_expires_at is null
        and heartbeat_at is null)
    ),
  add constraint chk_compute_runs_result_source
    check (
      (status = 'succeeded' and result_source in ('computed', 'fallback'))
      or (status <> 'succeeded' and result_source is null)
    ),
  add constraint chk_compute_runs_retry_schedule
    check (status = 'queued' or next_attempt_at is null),
  add constraint chk_compute_runs_lease_time_order
    check (lease_expires_at is null or lease_expires_at > heartbeat_at),
  add constraint chk_compute_runs_completion_time_order
    check (completed_at is null or started_at is null or completed_at >= started_at);

create index idx_compute_runs_worker_claim
  on public.compute_runs (next_attempt_at, created_at, id)
  where status = 'queued' and attempt_count < 5;

create index idx_compute_runs_worker_recovery
  on public.compute_runs (lease_expires_at, created_at, id)
  where status = 'running' and attempt_count < 5;
