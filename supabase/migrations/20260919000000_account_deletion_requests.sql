-- Issue #61: durable account deletion request and opaque status-token contract.

create table if not exists public.account_deletion_requests (
  id varchar(26) primary key check (id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
  user_profile_id uuid references public.user_profiles(id) on delete set null,
  idempotency_hash bytea not null check (octet_length(idempotency_hash) = 32),
  request_hash bytea not null check (octet_length(request_hash) = 32),
  status_token_hash bytea not null check (octet_length(status_token_hash) = 32),
  status_token_ciphertext text,
  status_token_key_version text,
  status_token_expires_at timestamptz not null,
  auth_subject_ciphertext text,
  auth_subject_key_version text,
  status text not null default 'queued'
    check (status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  current_step text check (current_step is null or char_length(current_step) between 1 and 64),
  next_retry_at timestamptz,
  requested_at timestamptz not null,
  completed_at timestamptz,
  constraint account_deletion_status_token_cipher_pair check (
    (status_token_ciphertext is null) = (status_token_key_version is null)
  ),
  constraint account_deletion_auth_subject_cipher_pair check (
    (auth_subject_ciphertext is null) = (auth_subject_key_version is null)
  ),
  constraint account_deletion_completion_state check (
    (status in ('succeeded', 'failed', 'cancelled')) = (completed_at is not null)
  ),
  constraint account_deletion_token_expiry_after_request check (
    status_token_expires_at > requested_at
  )
);

create unique index if not exists account_deletion_requests_active_idempotency_uq
  on public.account_deletion_requests (user_profile_id, idempotency_hash)
  where user_profile_id is not null;

create index if not exists account_deletion_requests_status_poll_idx
  on public.account_deletion_requests (id, status_token_expires_at);

create unique index if not exists account_deletion_requests_status_token_hash_uq
  on public.account_deletion_requests (status_token_hash);

alter table public.account_deletion_requests enable row level security;
alter table public.account_deletion_requests force row level security;

revoke all on table public.account_deletion_requests from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on table public.account_deletion_requests from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on table public.account_deletion_requests from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on table public.account_deletion_requests from service_role';
    execute 'grant select, insert, update on table public.account_deletion_requests to service_role';
    execute 'revoke delete, truncate, references, trigger on table public.account_deletion_requests from service_role';
  end if;
end
$$;

create or replace function public.account_deletion_session_is_recent(
  target_session_id uuid,
  target_user_id uuid,
  not_before timestamptz
) returns boolean
language sql
stable
security definer
set search_path = pg_catalog, auth
as $$
  select exists (
    select 1
    from auth.sessions session_record
    where session_record.id = target_session_id
      and session_record.user_id = target_user_id
      and session_record.created_at >= not_before
  );
$$;

revoke all on function public.account_deletion_session_is_recent(uuid, uuid, timestamptz)
  from public;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on function public.account_deletion_session_is_recent(uuid, uuid, timestamptz) from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on function public.account_deletion_session_is_recent(uuid, uuid, timestamptz) from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'grant execute on function public.account_deletion_session_is_recent(uuid, uuid, timestamptz) to service_role';
  end if;
end
$$;
