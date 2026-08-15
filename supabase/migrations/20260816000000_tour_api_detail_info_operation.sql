-- Issue #28: detailInfo2 반복 상세 normalized row도 operation별 provenance를 보존한다.
insert into public.tour_api_operations (operation_key)
values ('detailInfo2');

create table public.tour_api_detail_item_sweeps (
  id uuid primary key,
  place_id uuid not null references public.tour_places(id),
  source_provider text not null,
  source_service text not null,
  content_id text not null,
  content_type_id text not null,
  import_run_id uuid not null references public.data_import_runs(id),
  manifest_hash text not null,
  fetched_at timestamptz not null,
  expected_total integer not null,
  page_count integer not null,
  accepted_at timestamptz not null,
  constraint ck_detail_item_sweeps_provider check (source_provider = 'tour-api'),
  constraint ck_detail_item_sweeps_service check (source_service = 'KorService2'),
  constraint ck_detail_item_sweeps_content check (
    btrim(content_id) <> '' and btrim(content_type_id) <> ''
  ),
  constraint ck_detail_item_sweeps_manifest_hash check (manifest_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_detail_item_sweeps_counts check (expected_total >= 0 and page_count > 0),
  constraint uq_detail_item_sweeps_scope_manifest unique (
    place_id, source_provider, source_service, content_type_id, manifest_hash
  ),
  constraint uq_detail_item_sweeps_scope_run unique (
    place_id, source_provider, source_service, content_type_id, import_run_id
  )
);

create index idx_detail_item_sweeps_scope_freshness
  on public.tour_api_detail_item_sweeps (
    place_id, source_provider, source_service, content_type_id, fetched_at desc, id desc
  );

create index idx_detail_item_sweeps_import_run
  on public.tour_api_detail_item_sweeps(import_run_id);

create table public.tour_api_detail_item_sweep_pages (
  sweep_id uuid not null references public.tour_api_detail_item_sweeps(id),
  page_no integer not null,
  source_snapshot_id uuid not null references public.external_api_snapshots(id),
  request_fingerprint text not null,
  payload_hash text not null,
  raw_item_count integer not null,
  primary key (sweep_id, page_no),
  constraint uq_detail_item_sweep_pages_snapshot unique (sweep_id, source_snapshot_id),
  constraint ck_detail_item_sweep_pages_page check (page_no > 0 and raw_item_count >= 0),
  constraint ck_detail_item_sweep_pages_fingerprint
    check (request_fingerprint ~ '^[0-9a-f]{64}$'),
  constraint ck_detail_item_sweep_pages_payload_hash check (payload_hash ~ '^[0-9a-f]{64}$')
);

create index idx_detail_item_sweep_pages_source_snapshot
  on public.tour_api_detail_item_sweep_pages(source_snapshot_id);

alter table public.place_detail_items
  add column source_sweep_id uuid references public.tour_api_detail_item_sweeps(id),
  add constraint fk_place_detail_items_sweep_page
    foreign key (source_sweep_id, source_snapshot_id)
    references public.tour_api_detail_item_sweep_pages(sweep_id, source_snapshot_id)
    deferrable initially immediate;

create index idx_place_detail_items_sweep_page
  on public.place_detail_items(source_sweep_id, source_snapshot_id);

create or replace function public.validate_detail_item_sweep_lineage()
returns trigger
language plpgsql
set search_path = public
as $$
declare
  sweep_row public.tour_api_detail_item_sweeps%rowtype;
begin
  select * into sweep_row
  from public.tour_api_detail_item_sweeps
  where id = new.sweep_id
  for key share;

  if not found or not exists (
    select 1
    from public.external_api_snapshots snapshot
    join public.data_import_runs run on run.id = snapshot.import_run_id
    where snapshot.id = new.source_snapshot_id
      and snapshot.import_run_id = sweep_row.import_run_id
      and snapshot.source_provider = sweep_row.source_provider
      and snapshot.source_service = sweep_row.source_service
      and snapshot.source_operation = 'detailInfo2'
      and snapshot.scope_key = 'content:' || sweep_row.content_id
      and snapshot.page_key = new.page_no::text
      and snapshot.request_hash = new.request_fingerprint
      and snapshot.payload_hash = new.payload_hash
      and snapshot.parse_status in ('parsed', 'tombstoned')
      and run.source_provider = sweep_row.source_provider
      and run.source_service = sweep_row.source_service
      and run.source_operation = 'detailInfo2'
      and run.scope_key = 'content:' || sweep_row.content_id
  ) then
    raise exception using errcode = '23514', message = 'detailInfo2 sweep page lineage mismatch';
  end if;
  return new;
end;
$$;

create constraint trigger trg_detail_item_sweep_page_lineage
after insert on public.tour_api_detail_item_sweep_pages
deferrable initially immediate
for each row execute function public.validate_detail_item_sweep_lineage();

alter table public.tour_api_detail_item_sweeps enable row level security;
alter table public.tour_api_detail_item_sweep_pages enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    revoke all on public.tour_api_detail_item_sweeps from anon;
    revoke all on public.tour_api_detail_item_sweep_pages from anon;
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    revoke all on public.tour_api_detail_item_sweeps from authenticated;
    revoke all on public.tour_api_detail_item_sweep_pages from authenticated;
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    revoke all on public.tour_api_detail_item_sweeps from service_role;
    revoke all on public.tour_api_detail_item_sweep_pages from service_role;
    grant select, insert on public.tour_api_detail_item_sweeps to service_role;
    grant select, insert on public.tour_api_detail_item_sweep_pages to service_role;
  end if;
end;
$$;
