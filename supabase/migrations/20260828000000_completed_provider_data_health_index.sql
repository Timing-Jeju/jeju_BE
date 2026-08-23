create index idx_data_import_runs_completed_health_latest
on public.data_import_runs (
  source_provider,
  source_service,
  source_operation,
  started_at desc,
  id desc
)
include (status, finished_at)
where idempotency_key is not null
  and idempotency_enforced
  and running_scope_enforced
  and status in ('succeeded', 'failed', 'partial', 'cancelled')
  and finished_at is not null;
