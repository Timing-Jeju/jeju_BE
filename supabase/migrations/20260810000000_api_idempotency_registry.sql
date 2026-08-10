-- Issue #17: 변경 API가 공유하는 generic idempotency registry.
-- 요청 원문과 인증 정보는 저장하지 않고 canonical SHA-256과 최소 응답만 보존한다.

create table public.api_idempotency_records (
  owner_sub uuid not null,
  http_method varchar(16) not null,
  normalized_path varchar(1024) not null,
  idempotency_key uuid not null,
  request_hash char(64) not null,
  attempt_token uuid not null,
  state varchar(16) not null,
  response_status smallint,
  response_headers bytea,
  response_body bytea,
  created_at timestamptz not null,
  lease_expires_at timestamptz,
  completed_at timestamptz,
  expires_at timestamptz not null,
  primary key (owner_sub, http_method, normalized_path, idempotency_key),
  constraint ck_api_idempotency_method
    check (http_method ~ '^[A-Z]{1,16}$'),
  constraint ck_api_idempotency_path
    check (
      normalized_path like '/%'
      and normalized_path not like '%?%'
      and normalized_path not like '%#%'
      and octet_length(normalized_path) <= 1024
    ),
  constraint ck_api_idempotency_request_hash
    check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_api_idempotency_state
    check (state in ('PROCESSING', 'COMPLETED')),
  constraint ck_api_idempotency_response_size
    check (
      response_body is null
      or octet_length(response_body) <= 1048576
    ),
  constraint ck_api_idempotency_header_size
    check (
      response_headers is null
      or octet_length(response_headers) <= 65536
    ),
  constraint ck_api_idempotency_state_payload
    check (
      (
        state = 'PROCESSING'
        and response_status is null
        and response_headers is null
        and response_body is null
        and lease_expires_at = created_at + interval '2 minutes'
        and completed_at is null
        and expires_at = created_at + interval '24 hours'
      )
      or
      (
        state = 'COMPLETED'
        and response_status between 100 and 499
        and response_headers is not null
        and response_body is not null
        and lease_expires_at is null
        and completed_at is not null
        and expires_at = completed_at + interval '24 hours'
      )
    )
);

create index ix_api_idempotency_records_expiry
  on public.api_idempotency_records (expires_at);

alter table public.api_idempotency_records enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.api_idempotency_records from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.api_idempotency_records from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'grant select, insert, update, delete on public.api_idempotency_records to service_role';
  end if;
end $$;
