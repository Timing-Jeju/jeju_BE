-- Issue #106: durable account deletion worker lease, fencing, retry and step history.

alter table public.account_deletion_requests
  add column lease_owner varchar(100),
  add column lease_expires_at timestamptz,
  add column fencing_token bigint not null default 0 check (fencing_token >= 0),
  add column attempt integer not null default 0 check (attempt >= 0),
  add column failure_code varchar(64),
  add column cancellation_requested boolean not null default false,
  add constraint account_deletion_lease_pair check (
    (lease_owner is null) = (lease_expires_at is null)
  );

create index account_deletion_worker_claim_idx
  on public.account_deletion_requests (status, next_retry_at, lease_expires_at, requested_at)
  where status in ('queued', 'running');

create table public.account_deletion_steps (
  request_id varchar(26) not null
    references public.account_deletion_requests(id) on delete cascade,
  step varchar(64) not null,
  attempt integer not null check (attempt > 0),
  idempotency_key varchar(128) not null,
  started_at timestamptz not null,
  completed_at timestamptz,
  failure_code varchar(64),
  primary key (request_id, step, attempt),
  unique (idempotency_key)
);

alter table public.account_deletion_steps enable row level security;
alter table public.account_deletion_steps force row level security;

revoke all on table public.account_deletion_steps from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on table public.account_deletion_steps from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on table public.account_deletion_steps from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on table public.account_deletion_steps from service_role';
    execute 'grant select, insert, update on table public.account_deletion_steps to service_role';
    execute 'revoke delete, truncate, references, trigger on table public.account_deletion_steps from service_role';
  end if;
end
$$;
