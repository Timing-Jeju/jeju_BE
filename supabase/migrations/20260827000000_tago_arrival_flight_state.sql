-- Issue #39의 다중 인스턴스 도착정보 요청을 짧은 DB CAS로 합치고
-- 외부 호출 동안 application connection/transaction을 점유하지 않는다.
create table public.tago_arrival_flights (
  fingerprint character(64) primary key,
  generation bigint not null,
  owner_token uuid not null,
  lease_expires_at timestamptz not null,
  state text not null,
  outcome_code text,
  retain_until timestamptz not null,
  updated_at timestamptz not null default statement_timestamp(),
  constraint chk_tago_arrival_flight_fingerprint
    check (fingerprint ~ '^[0-9a-f]{64}$'),
  constraint chk_tago_arrival_flight_generation check (generation > 0),
  constraint chk_tago_arrival_flight_state
    check (state in ('running', 'succeeded', 'failed', 'abandoned')),
  constraint chk_tago_arrival_flight_outcome
    check (
      outcome_code is null
      or outcome_code in (
        'rate_limited', 'timeout', 'provider_unavailable', 'empty_result',
        'invalid_provider_response', 'invalid_request', 'data_unavailable'
      )
    ),
  constraint chk_tago_arrival_flight_terminal_shape
    check (
      (state in ('running', 'succeeded') and outcome_code is null)
      or (state in ('failed', 'abandoned') and outcome_code is not null)
    ),
  constraint chk_tago_arrival_flight_times
    check ((state <> 'running' or lease_expires_at > updated_at) and retain_until >= updated_at)
);

create index idx_tago_arrival_flights_cleanup
  on public.tago_arrival_flights (retain_until, fingerprint)
  where state <> 'running';

alter table public.tago_arrival_flights enable row level security;
revoke all on public.tago_arrival_flights from anon, authenticated;
grant select, insert, update, delete on public.tago_arrival_flights to service_role;
