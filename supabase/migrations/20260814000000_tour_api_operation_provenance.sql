-- Issue #107: TourAPI operation registry와 normalized row의 다중 원천 계보.

create table public.tour_api_operations (
  operation_key text primary key,
  source_provider text not null default 'tour-api',
  source_service text not null default 'KorService2',
  active boolean not null default true,
  created_at timestamptz not null default now(),
  constraint ck_tour_api_operations_key
    check (btrim(operation_key) <> '' and octet_length(operation_key) <= 128),
  constraint ck_tour_api_operations_provider
    check (source_provider = 'tour-api'),
  constraint ck_tour_api_operations_service
    check (source_service = 'KorService2'),
  unique (source_provider, source_service, operation_key)
);

insert into public.tour_api_operations (operation_key) values
  ('areaCode2'),
  ('categoryCode2'),
  ('areaBasedList2'),
  ('locationBasedList2'),
  ('searchKeyword2'),
  ('searchStay2'),
  ('detailCommon2'),
  ('detailIntro2');

create table public.tour_api_operation_provenance (
  id uuid primary key default gen_random_uuid(),
  normalized_entity_type text not null,
  normalized_row_id uuid not null,
  operation_key text not null references public.tour_api_operations(operation_key),
  content_type_id text,
  request_fingerprint text not null,
  source_snapshot_id uuid not null references public.external_api_snapshots(id) on delete restrict,
  import_run_id uuid not null references public.data_import_runs(id) on delete restrict,
  created_at timestamptz not null default now(),
  constraint ck_tour_api_provenance_entity_type
    check (
      normalized_entity_type in (
        'external_reference_codes', 'tour_places', 'tour_place_sources', 'place_aliases',
        'place_details', 'place_detail_items', 'place_images'
      )
    ),
  constraint ck_tour_api_provenance_content_type
    check (
      content_type_id is null
      or (btrim(content_type_id) <> '' and octet_length(content_type_id) <= 128)
    ),
  constraint ck_tour_api_provenance_fingerprint
    check (request_fingerprint ~ '^[0-9a-f]{64}$'),
  unique (normalized_entity_type, normalized_row_id, operation_key, source_snapshot_id)
);

create index idx_tour_api_provenance_snapshot
  on public.tour_api_operation_provenance (source_snapshot_id);
create index idx_tour_api_provenance_import_run
  on public.tour_api_operation_provenance (import_run_id);
create index idx_tour_api_provenance_operation_request
  on public.tour_api_operation_provenance (operation_key, request_fingerprint);
create index idx_tour_api_provenance_normalized_row
  on public.tour_api_operation_provenance (normalized_entity_type, normalized_row_id);

create function public.validate_tour_api_operation_provenance()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  target_exists boolean := false;
begin
  if not exists (
    select 1
    from public.tour_api_operations operation
    join public.external_api_snapshots snapshot
      on snapshot.id = new.source_snapshot_id
     and snapshot.source_provider = operation.source_provider
     and snapshot.source_service = operation.source_service
     and snapshot.source_operation = operation.operation_key
     and snapshot.import_run_id = new.import_run_id
     and snapshot.request_hash = new.request_fingerprint
     and snapshot.parse_status in ('parsed', 'tombstoned')
    join public.data_import_runs import_run
      on import_run.id = new.import_run_id
     and import_run.source_provider = operation.source_provider
     and import_run.source_service = operation.source_service
     and import_run.source_operation = operation.operation_key
    where operation.operation_key = new.operation_key
      and operation.active
  ) then
    raise exception using
      errcode = '23514',
      message = 'TourAPI operation provenance lineage mismatch';
  end if;

  case new.normalized_entity_type
    when 'external_reference_codes' then
      perform 1 from public.external_reference_codes target
      where target.id = new.normalized_row_id
      for key share;
      target_exists := found;
    when 'tour_places' then
      perform 1 from public.tour_places target
      where target.id = new.normalized_row_id
      for key share;
      target_exists := found;
    when 'tour_place_sources' then
      perform 1 from public.tour_place_sources target
      where target.id = new.normalized_row_id
      for key share;
      target_exists := found;
    when 'place_aliases' then
      perform 1 from public.place_aliases target
      where target.id = new.normalized_row_id
      for key share;
      target_exists := found;
    when 'place_details' then
      perform 1 from public.place_details target
      where target.place_id = new.normalized_row_id
      for key share;
      target_exists := found;
    when 'place_detail_items' then
      perform 1 from public.place_detail_items target
      where target.id = new.normalized_row_id
      for key share;
      target_exists := found;
    when 'place_images' then
      perform 1 from public.place_images target
      where target.id = new.normalized_row_id
      for key share;
      target_exists := found;
    else
      target_exists := false;
  end case;

  if not target_exists then
    raise exception using
      errcode = '23503',
      message = 'TourAPI operation provenance target does not exist';
  end if;

  return new;
end;
$$;

-- provenance INSERT가 잡은 대상 행의 KEY SHARE 잠금과 식별자 UPDATE/DELETE의 행 잠금을
-- 직렬화한다. mutation이 먼저 잠그면 INSERT는 대상 부재로 거부되고, INSERT가
-- 먼저 잠그면 mutation은 커밋된 provenance를 확인한 뒤 FK 위반으로 거부된다.
create function public.protect_tour_api_provenance_target_delete()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  target_id uuid;
  next_target_id uuid;
begin
  target_id := (
    pg_catalog.to_jsonb(old) ->> case
      when tg_argv[0] = 'place_details' then 'place_id'
      else 'id'
    end
  )::uuid;

  if tg_op = 'UPDATE' then
    next_target_id := (
      pg_catalog.to_jsonb(new) ->> case
        when tg_argv[0] = 'place_details' then 'place_id'
        else 'id'
      end
    )::uuid;
    if next_target_id is not distinct from target_id then
      return new;
    end if;
  end if;

  if exists (
    select 1
    from public.tour_api_operation_provenance provenance
    where provenance.normalized_entity_type = tg_argv[0]
      and provenance.normalized_row_id = target_id
  ) then
    raise exception using
      errcode = '23503',
      message = 'TourAPI operation provenance target is still referenced';
  end if;

  if tg_op = 'UPDATE' then
    return new;
  end if;
  return old;
end;
$$;

create trigger trg_tour_api_operation_provenance_validate
before insert or update on public.tour_api_operation_provenance
for each row execute function public.validate_tour_api_operation_provenance();

create trigger trg_external_reference_codes_provenance_delete
before delete on public.external_reference_codes
for each row execute function public.protect_tour_api_provenance_target_delete('external_reference_codes');
create trigger trg_external_reference_codes_provenance_identifier_update
before update of id on public.external_reference_codes
for each row execute function public.protect_tour_api_provenance_target_delete('external_reference_codes');
create trigger trg_tour_places_provenance_delete
before delete on public.tour_places
for each row execute function public.protect_tour_api_provenance_target_delete('tour_places');
create trigger trg_tour_places_provenance_identifier_update
before update of id on public.tour_places
for each row execute function public.protect_tour_api_provenance_target_delete('tour_places');
create trigger trg_tour_place_sources_provenance_delete
before delete on public.tour_place_sources
for each row execute function public.protect_tour_api_provenance_target_delete('tour_place_sources');
create trigger trg_tour_place_sources_provenance_identifier_update
before update of id on public.tour_place_sources
for each row execute function public.protect_tour_api_provenance_target_delete('tour_place_sources');
create trigger trg_place_aliases_provenance_delete
before delete on public.place_aliases
for each row execute function public.protect_tour_api_provenance_target_delete('place_aliases');
create trigger trg_place_aliases_provenance_identifier_update
before update of id on public.place_aliases
for each row execute function public.protect_tour_api_provenance_target_delete('place_aliases');
create trigger trg_place_details_provenance_delete
before delete on public.place_details
for each row execute function public.protect_tour_api_provenance_target_delete('place_details');
create trigger trg_place_details_provenance_identifier_update
before update of place_id on public.place_details
for each row execute function public.protect_tour_api_provenance_target_delete('place_details');
create trigger trg_place_detail_items_provenance_delete
before delete on public.place_detail_items
for each row execute function public.protect_tour_api_provenance_target_delete('place_detail_items');
create trigger trg_place_detail_items_provenance_identifier_update
before update of id on public.place_detail_items
for each row execute function public.protect_tour_api_provenance_target_delete('place_detail_items');
create trigger trg_place_images_provenance_delete
before delete on public.place_images
for each row execute function public.protect_tour_api_provenance_target_delete('place_images');
create trigger trg_place_images_provenance_identifier_update
before update of id on public.place_images
for each row execute function public.protect_tour_api_provenance_target_delete('place_images');

alter table public.tour_api_operations enable row level security;
alter table public.tour_api_operation_provenance enable row level security;
revoke all on public.tour_api_operations from anon, authenticated;
revoke all on public.tour_api_operation_provenance from anon, authenticated;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    grant select on public.tour_api_operations to service_role;
    grant select, insert on public.tour_api_operation_provenance to service_role;
  end if;
end;
$$;
