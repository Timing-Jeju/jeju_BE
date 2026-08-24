create index idx_external_api_snapshots_retention_due
  on public.external_api_snapshots (purge_after, id)
  where purge_after is not null
    and purged_at is null
    and raw_payload is not null;

drop index public.idx_external_api_snapshots_purge;
