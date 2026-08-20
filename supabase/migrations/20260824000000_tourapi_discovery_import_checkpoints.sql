-- Issue #75: discovery operation별 complete manifest CAS checkpoint.
insert into public.data_import_checkpoints (
  source_provider, source_service, source_operation, scope_key, checkpoint, source_watermark_at
)
select
  'tour-api', 'KorService2', operation_key, 'jeju',
  '{"manifest":"uninitialized","pageCount":0}'::jsonb,
  '1970-01-01T00:00:00Z'::timestamptz
from (values
  ('locationBasedList2'),
  ('searchKeyword2'),
  ('searchStay2')
) as discovery_operations(operation_key)
on conflict (source_provider, source_service, source_operation, scope_key) do nothing;

create index idx_place_aliases_keyword_lookup_active
  on public.place_aliases (normalized_alias, place_id)
  where alias_type = 'keyword' and tombstoned_at is null;
