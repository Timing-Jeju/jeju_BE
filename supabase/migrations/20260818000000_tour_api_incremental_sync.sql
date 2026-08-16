-- Issue #30: areaBasedSyncList2 증분 커서는 정규화 commit과 같은 트랜잭션에서 CAS 전진한다.
insert into public.tour_api_operations (operation_key) values ('areaBasedSyncList2');

insert into public.data_import_checkpoints (
  source_provider, source_service, source_operation, scope_key, checkpoint, source_watermark_at
) values (
  'tour-api', 'KorService2', 'areaBasedSyncList2', 'jeju',
  '{"modifiedTime":"1970-01-01T00:00:00Z"}'::jsonb,
  '1970-01-01T00:00:00Z'::timestamptz
)
on conflict (source_provider, source_service, source_operation, scope_key) do nothing;
