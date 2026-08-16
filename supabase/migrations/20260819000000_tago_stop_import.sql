-- Issue #35: TAGO 제주 도시코드·정류장 full importer checkpoint와 scope 조회 계약.

do $$
declare
  duplicate_record record;
begin
  select source_provider, source_service, city_code, node_id, count(*) duplicate_count
  into duplicate_record
  from public.bus_stops
  where source_provider = 'TAGO'
    and source_service = 'BusSttnInfoInqireService'
  group by source_provider, source_service, city_code, node_id
  having count(*) > 1
  order by city_code, node_id
  limit 1;

  if found then
    raise exception using
      errcode = '23505',
      message = pg_catalog.format(
        'legacy TAGO stop natural key collision: provider=%s service=%s city=%s node=%s count=%s',
        duplicate_record.source_provider,
        duplicate_record.source_service,
        duplicate_record.city_code,
        duplicate_record.node_id,
        duplicate_record.duplicate_count
      );
  end if;
end $$;

insert into public.data_import_checkpoints (
  source_provider, source_service, source_operation, scope_key, checkpoint, source_watermark_at
) values (
  'TAGO', 'BusSttnInfoInqireService', 'getSttnNoList', 'jeju',
  '{"cityCode":"unresolved"}'::jsonb,
  '1970-01-01T00:00:00Z'::timestamptz
)
on conflict (source_provider, source_service, source_operation, scope_key) do nothing;

create index idx_bus_stops_source_scope_freshness
  on public.bus_stops (
    source_provider, source_service, city_code, stale, last_seen_at desc, node_id
  );

create index idx_external_reference_codes_source_scope_name
  on public.external_reference_codes (
    source_provider, source_service, code_type, code_name, external_code
  )
  where stale_at is null and tombstoned_at is null;

alter table public.bus_stops enable row level security;
alter table public.external_reference_codes enable row level security;
revoke all on public.bus_stops from anon, authenticated;
revoke all on public.external_reference_codes from anon, authenticated;
