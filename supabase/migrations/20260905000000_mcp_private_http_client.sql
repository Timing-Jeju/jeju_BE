-- Issue #52: MCP 감사 로그를 v0.7 여섯 도구와 payload-free 메타데이터로 제한한다.

alter table public.mcp_compute_call_logs
  drop constraint mcp_compute_call_logs_tool_name_check,
  drop constraint mcp_compute_call_logs_status_check,
  drop constraint mcp_compute_call_logs_check;

drop index if exists public.uq_mcp_compute_call_logs_request;
drop index if exists public.idx_mcp_compute_call_logs_user_created;
drop index if exists public.idx_mcp_compute_call_logs_trip_tool;

alter table public.mcp_compute_call_logs
  add column schedule_revision_run_id uuid
    references public.schedule_revision_runs(id) on delete cascade,
  add column command_input_hash char(64),
  add column mcp_input_hash char(64),
  add column schema_checksum char(64),
  add column request_fact_count integer,
  add column response_fact_count integer,
  add column attempt_no integer not null default 1,
  add column legacy_contract boolean not null default false,
  drop column user_id,
  drop column trip_plan_id,
  drop column provider,
  drop column model,
  drop column request_payload_redacted,
  drop column response_payload_redacted,
  drop column error_message;

update public.mcp_compute_call_logs
set legacy_contract = true;

alter table public.mcp_compute_call_logs
  add constraint mcp_compute_call_logs_tool_name_check check (
    legacy_contract
    or tool_name in (
      'recommend_jeju_day_trips',
      'evaluate_jeju_day_trip',
      'revalidate_jeju_day_trip',
      'search_jeju_places',
      'inspect_jeju_bus_stop',
      'preview_jeju_transfer'
    )
  ),
  add constraint mcp_compute_call_logs_status_check check (
    legacy_contract
    or status in (
      'succeeded', 'domain_failure', 'contract_invalid', 'transport_error',
      'authentication_failed', 'protocol_invalid'
    )
  ),
  add constraint mcp_compute_call_logs_request_id_check check (
    legacy_contract or request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
  ),
  add constraint mcp_compute_call_logs_error_code_check check (
    error_code is null or error_code ~ '^[A-Z][A-Z0-9_]{0,99}$'
  ),
  add constraint mcp_compute_call_logs_status_error_check check (
    legacy_contract
    or (status = 'succeeded' and error_code is null)
    or (status <> 'succeeded' and error_code is not null)
  ),
  add constraint mcp_compute_call_logs_command_hash_check
    check (command_input_hash is null or command_input_hash ~ '^[0-9a-f]{64}$'),
  add constraint mcp_compute_call_logs_mcp_hash_check
    check (mcp_input_hash is null or mcp_input_hash ~ '^[0-9a-f]{64}$'),
  add constraint mcp_compute_call_logs_schema_checksum_check
    check (schema_checksum is null or schema_checksum ~ '^[0-9a-f]{64}$'),
  add constraint mcp_compute_call_logs_fact_counts_check check (
    (request_fact_count is null or request_fact_count >= 0)
    and (response_fact_count is null or response_fact_count >= 0)
  ),
  add constraint mcp_compute_call_logs_attempt_check check (attempt_no between 1 and 5),
  add constraint mcp_compute_call_logs_parent_check check (
    legacy_contract
    or num_nonnulls(compute_run_id, generation_run_id, schedule_revision_run_id) = 1
  ),
  add constraint mcp_compute_call_logs_current_metadata_check check (
    legacy_contract
    or (
      command_input_hash is not null
      and mcp_input_hash is not null
      and schema_checksum is not null
      and request_fact_count is not null
      and response_fact_count is not null
      and latency_ms is not null
    )
  );

create unique index uq_mcp_compute_call_logs_request_attempt
  on public.mcp_compute_call_logs (request_id, tool_name, attempt_no);

create index idx_mcp_compute_call_logs_revision
  on public.mcp_compute_call_logs (schedule_revision_run_id);

create function public.protect_mcp_call_log_legacy_marker()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' and new.legacy_contract then
    raise exception using errcode = '23514', message = 'new MCP call log cannot be legacy';
  end if;
  if tg_op = 'UPDATE' and new.legacy_contract is distinct from old.legacy_contract then
    raise exception using errcode = '23514', message = 'MCP legacy marker is immutable';
  end if;
  return new;
end;
$$;

create trigger trg_mcp_call_log_legacy_marker
before insert or update on public.mcp_compute_call_logs
for each row execute function public.protect_mcp_call_log_legacy_marker();

comment on table public.mcp_compute_call_logs is
  'MCP v0.7 호출의 hash/count/status/latency 감사 로그. payload, JWT, provider body, geometry 저장 금지.';
