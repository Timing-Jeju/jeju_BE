-- 외부 API 원문을 보존한 뒤 검증·정규화된 read model로 연결하는 공통 적재 기준이다.
-- API key, Authorization 헤더, 원문 요청 URL은 저장하지 않고 redacted metadata만 저장한다.

create table public.data_import_checkpoints (
  id uuid primary key default gen_random_uuid(),
  source_provider text not null,
  source_service text not null,
  source_operation text not null,
  scope_key text not null default 'global',
  checkpoint jsonb not null default '{}'::jsonb,
  source_watermark_at timestamptz,
  last_succeeded_run_id uuid references public.data_import_runs(id) on delete set null,
  version bigint not null default 0,
  updated_at timestamptz not null default now(),
  constraint ck_data_import_checkpoints_provider_nonblank
    check (btrim(source_provider) <> ''),
  constraint ck_data_import_checkpoints_service_nonblank
    check (btrim(source_service) <> ''),
  constraint ck_data_import_checkpoints_operation_nonblank
    check (btrim(source_operation) <> ''),
  constraint ck_data_import_checkpoints_scope_nonblank
    check (btrim(scope_key) <> ''),
  constraint ck_data_import_checkpoints_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(source_operation) <= 128
      and octet_length(scope_key) <= 512
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(source_operation)
        + octet_length(scope_key) <= 1024
    ),
  constraint ck_data_import_checkpoints_object
    check (jsonb_typeof(checkpoint) = 'object'),
  constraint ck_data_import_checkpoints_version
    check (version >= 0),
  unique (source_provider, source_service, source_operation, scope_key)
);

create index idx_data_import_checkpoints_last_run
  on public.data_import_checkpoints (last_succeeded_run_id)
  where last_succeeded_run_id is not null;

create table public.external_api_snapshots (
  id uuid primary key default gen_random_uuid(),
  import_run_id uuid not null references public.data_import_runs(id) on delete cascade,
  source_provider text not null,
  source_service text not null,
  source_operation text not null,
  scope_key text not null default 'global',
  external_record_id text,
  request_hash text not null,
  page_key text not null default '',
  http_status integer,
  provider_result_code text,
  fetched_at timestamptz not null default now(),
  source_modified_at timestamptz,
  expires_at timestamptz,
  parser_version text not null,
  payload_hash text not null,
  parse_status text not null default 'received',
  parsed_at timestamptz,
  error_code text,
  error_message text,
  request_metadata_redacted jsonb not null default '{}'::jsonb,
  raw_payload jsonb not null,
  purge_after timestamptz,
  constraint ck_external_snapshots_provider_nonblank
    check (btrim(source_provider) <> ''),
  constraint ck_external_snapshots_service_nonblank
    check (btrim(source_service) <> ''),
  constraint ck_external_snapshots_operation_nonblank
    check (btrim(source_operation) <> ''),
  constraint ck_external_snapshots_scope_nonblank
    check (btrim(scope_key) <> ''),
  constraint ck_external_snapshots_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(source_operation) <= 128
      and octet_length(scope_key) <= 512
      and (external_record_id is null or octet_length(external_record_id) <= 512)
      and octet_length(page_key) <= 512
      and octet_length(parser_version) <= 128
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(source_operation)
        + octet_length(scope_key) <= 1024
    ),
  constraint ck_external_snapshots_external_id_nonblank
    check (external_record_id is null or btrim(external_record_id) <> ''),
  constraint ck_external_snapshots_request_hash
    check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_external_snapshots_payload_hash
    check (payload_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_external_snapshots_parser_nonblank
    check (btrim(parser_version) <> ''),
  constraint ck_external_snapshots_http_status
    check (http_status is null or http_status between 100 and 599),
  constraint ck_external_snapshots_parse_status
    check (parse_status in ('received', 'parsed', 'rejected', 'ignored', 'tombstoned')),
  constraint ck_external_snapshots_parse_result
    check (
      (parse_status = 'parsed' and parsed_at is not null and error_code is null)
      or (parse_status = 'rejected' and error_code is not null and btrim(error_code) <> '')
      or parse_status in ('received', 'ignored', 'tombstoned')
    ),
  constraint ck_external_snapshots_error_code_nonblank
    check (error_code is null or btrim(error_code) <> ''),
  constraint ck_external_snapshots_metadata_object
    check (jsonb_typeof(request_metadata_redacted) = 'object'),
  constraint ck_external_snapshots_payload_container
    check (jsonb_typeof(raw_payload) in ('object', 'array')),
  constraint ck_external_snapshots_expiry
    check (expires_at is null or expires_at >= fetched_at),
  constraint ck_external_snapshots_retention
    check (purge_after is null or purge_after >= fetched_at),
  unique (import_run_id, source_operation, request_hash, page_key, payload_hash)
);

create index idx_external_api_snapshots_import_run
  on public.external_api_snapshots (import_run_id);

create index idx_external_api_snapshots_latest
  on public.external_api_snapshots (
    source_provider, source_service, source_operation, scope_key, fetched_at desc
  );

create index idx_external_api_snapshots_external_record
  on public.external_api_snapshots (
    source_provider, source_service, external_record_id, fetched_at desc
  ) where external_record_id is not null;

create index idx_external_api_snapshots_purge
  on public.external_api_snapshots (purge_after)
  where purge_after is not null;

create table public.tour_place_sources (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references public.tour_places(id) on delete cascade,
  source_provider text not null,
  source_service text not null,
  external_id text not null,
  content_type_id text,
  area_code text,
  sigungu_code text,
  category_code_1 text,
  category_code_2 text,
  category_code_3 text,
  source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  last_import_run_id uuid references public.data_import_runs(id) on delete set null,
  source_modified_at timestamptz,
  last_seen_at timestamptz not null default now(),
  stale_at timestamptz,
  tombstoned_at timestamptz,
  source_deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ck_tour_place_sources_provider_nonblank
    check (btrim(source_provider) <> ''),
  constraint ck_tour_place_sources_service_nonblank
    check (btrim(source_service) <> ''),
  constraint ck_tour_place_sources_external_id_nonblank
    check (btrim(external_id) <> ''),
  constraint ck_tour_place_sources_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(external_id) <= 512
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(external_id) <= 1024
    ),
  constraint ck_tour_place_sources_lifecycle_order
    check (
      (stale_at is null or stale_at >= created_at)
      and (tombstoned_at is null or tombstoned_at >= created_at)
      and (source_deleted_at is null or source_deleted_at >= created_at)
    ),
  unique (source_provider, source_service, external_id)
);

create index idx_tour_place_sources_place
  on public.tour_place_sources (place_id);
create index idx_tour_place_sources_snapshot
  on public.tour_place_sources (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_tour_place_sources_import_run
  on public.tour_place_sources (last_import_run_id)
  where last_import_run_id is not null;
create index idx_tour_place_sources_active
  on public.tour_place_sources (place_id, last_seen_at desc)
  where tombstoned_at is null;

create table public.place_detail_items (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references public.tour_places(id) on delete cascade,
  source_provider text not null,
  source_service text not null,
  content_type_id text,
  item_type text not null,
  source_item_key text not null,
  title text,
  sequence_no integer,
  attributes jsonb not null default '{}'::jsonb,
  payload_hash text not null,
  source_modified_at timestamptz,
  source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  import_run_id uuid references public.data_import_runs(id) on delete set null,
  last_seen_at timestamptz not null default now(),
  stale_at timestamptz,
  tombstoned_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ck_place_detail_items_provider_nonblank
    check (btrim(source_provider) <> ''),
  constraint ck_place_detail_items_service_nonblank
    check (btrim(source_service) <> ''),
  constraint ck_place_detail_items_type_nonblank
    check (btrim(item_type) <> ''),
  constraint ck_place_detail_items_source_key_nonblank
    check (btrim(source_item_key) <> ''),
  constraint ck_place_detail_items_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(item_type) <= 128
      and octet_length(source_item_key) <= 512
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(item_type)
        + octet_length(source_item_key) <= 1024
    ),
  constraint ck_place_detail_items_sequence
    check (sequence_no is null or sequence_no > 0),
  constraint ck_place_detail_items_attributes_object
    check (jsonb_typeof(attributes) = 'object'),
  constraint ck_place_detail_items_payload_hash
    check (payload_hash ~ '^[0-9a-f]{64}$'),
  unique (place_id, source_provider, source_service, item_type, source_item_key)
);

create index idx_place_detail_items_place
  on public.place_detail_items (place_id, item_type, sequence_no);
create index idx_place_detail_items_snapshot
  on public.place_detail_items (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_place_detail_items_import_run
  on public.place_detail_items (import_run_id)
  where import_run_id is not null;

create table public.external_reference_codes (
  id uuid primary key default gen_random_uuid(),
  source_provider text not null,
  source_service text not null,
  code_type text not null,
  external_code text not null,
  parent_external_code text,
  code_name text not null,
  code_path text,
  attributes jsonb not null default '{}'::jsonb,
  valid_from date not null default '-infinity'::date,
  valid_to date,
  source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  import_run_id uuid references public.data_import_runs(id) on delete set null,
  last_seen_at timestamptz not null default now(),
  stale_at timestamptz,
  tombstoned_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ck_external_reference_codes_provider_nonblank
    check (btrim(source_provider) <> ''),
  constraint ck_external_reference_codes_service_nonblank
    check (btrim(source_service) <> ''),
  constraint ck_external_reference_codes_type_nonblank
    check (btrim(code_type) <> ''),
  constraint ck_external_reference_codes_code_nonblank
    check (btrim(external_code) <> ''),
  constraint ck_external_reference_codes_name_nonblank
    check (btrim(code_name) <> ''),
  constraint ck_external_reference_codes_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(code_type) <= 128
      and octet_length(external_code) <= 512
      and (
        parent_external_code is null
        or octet_length(parent_external_code) <= 512
      )
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(code_type)
        + octet_length(external_code) <= 1024
    ),
  constraint ck_external_reference_codes_attributes_object
    check (jsonb_typeof(attributes) = 'object'),
  constraint ck_external_reference_codes_validity
    check (valid_to is null or valid_to >= valid_from),
  unique (source_provider, source_service, code_type, external_code, valid_from)
);

create index idx_external_reference_codes_snapshot
  on public.external_reference_codes (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_external_reference_codes_import_run
  on public.external_reference_codes (import_run_id)
  where import_run_id is not null;

alter table public.tour_places
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column stale_reason text,
  add column source_deleted_at timestamptz;

create index idx_tour_places_source_snapshot
  on public.tour_places (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.place_details
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column import_run_id uuid references public.data_import_runs(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz;

create index idx_place_details_source_snapshot
  on public.place_details (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_place_details_import_run
  on public.place_details (import_run_id)
  where import_run_id is not null;

alter table public.place_operating_hours
  drop constraint place_operating_hours_place_id_day_of_week_key,
  add column interval_no integer not null default 1,
  add column valid_from date not null default '-infinity'::date,
  add column valid_to date,
  add column spans_next_day boolean not null default false,
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column import_run_id uuid references public.data_import_runs(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz;

-- v1은 22:00~02:00 같은 익일 종료를 별도 플래그 없이 허용했다.
-- 기존 시간을 변경하지 않고 의미만 명시한 뒤 신규 행부터 엄격한 CHECK를 적용한다.
update public.place_operating_hours
set spans_next_day = true
where not is_closed
  and open_time is not null
  and close_time is not null
  and close_time <= open_time;

alter table public.place_operating_hours
  add constraint ck_place_hours_interval_no check (interval_no > 0),
  add constraint ck_place_hours_validity check (valid_to is null or valid_to >= valid_from),
  add constraint ck_place_hours_closed_values check (
    (is_closed and open_time is null and close_time is null and last_entry_time is null)
    or (not is_closed and open_time is not null and close_time is not null)
  ) not valid,
  add constraint ck_place_hours_time_order check (
    is_closed
    or (spans_next_day and close_time <= open_time)
    or (not spans_next_day and close_time > open_time)
  ) not valid,
  add constraint uq_place_hours_interval unique (
    place_id, day_of_week, interval_no, valid_from
  ),
  add constraint ex_place_hours_no_overlap exclude using gist (
    place_id with =,
    day_of_week with =,
    daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') with &&,
    numrange(
      extract(epoch from open_time),
      extract(epoch from close_time) + case when spans_next_day then 86400 else 0 end,
      '[)'
    ) with &&
  ) where (not is_closed);

create index idx_place_hours_source_snapshot
  on public.place_operating_hours (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_place_hours_import_run
  on public.place_operating_hours (import_run_id)
  where import_run_id is not null;

alter table public.place_aliases
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column import_run_id uuid references public.data_import_runs(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz;

create index idx_place_aliases_source_snapshot
  on public.place_aliases (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_place_aliases_import_run
  on public.place_aliases (import_run_id)
  where import_run_id is not null;

alter table public.place_images
  add column source_service text not null default 'legacy',
  add column source_image_id text,
  add column image_name text,
  add column copyright_code text,
  add column copyright_owner text,
  add column license_text text,
  add column source_modified_at timestamptz,
  add column payload_hash text,
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column import_run_id uuid references public.data_import_runs(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz,
  add constraint ck_place_images_source_id_nonblank
    check (source_image_id is null or btrim(source_image_id) <> ''),
  add constraint ck_place_images_payload_hash
    check (payload_hash is null or payload_hash ~ '^[0-9a-f]{64}$');

create unique index uq_place_images_provider_source_id
  on public.place_images (place_id, source_provider, source_service, source_image_id)
  where source_image_id is not null
    and octet_length(source_provider) <= 128
    and octet_length(source_service) <= 128
    and octet_length(source_image_id) <= 512
    and octet_length(source_provider)
      + octet_length(source_service)
      + octet_length(source_image_id) <= 1024;
-- v1 URL은 인덱싱되지 않은 장문일 수 있다. raw URL 복합 인덱스 대신
-- 다음 migration의 고정 길이 digest key로 반복 적재를 제어한다.
create index idx_place_images_source_snapshot
  on public.place_images (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_place_images_import_run
  on public.place_images (import_run_id)
  where import_run_id is not null;

alter table public.bus_stops
  drop constraint bus_stops_external_stop_id_key,
  add column city_code text not null default '39',
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz,
  add column source_deleted_at timestamptz,
  add constraint ck_bus_stops_city_code_nonblank check (btrim(city_code) <> '');

drop index public.uq_bus_stops_node_id;

update public.bus_stops
set source_service = 'legacy'
where source_service is null or btrim(source_service) = '';

alter table public.bus_stops
  alter column source_service set default 'legacy',
  alter column source_service set not null,
  add constraint ck_bus_stops_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(city_code) <= 64
      and octet_length(node_id) <= 512
      and (
        external_stop_id is null
        or octet_length(external_stop_id) <= 512
      )
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(city_code)
        + octet_length(node_id) <= 1024
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(city_code)
        + coalesce(octet_length(external_stop_id), 0) <= 1024
    ) not valid;

create unique index uq_bus_stops_provider_city_node
  on public.bus_stops (source_provider, source_service, city_code, node_id)
  where octet_length(source_provider) <= 128
    and octet_length(source_service) <= 128
    and octet_length(city_code) <= 64
    and octet_length(node_id) <= 512
    and octet_length(source_provider)
      + octet_length(source_service)
      + octet_length(city_code)
      + octet_length(node_id) <= 1024;
create unique index uq_bus_stops_provider_city_external
  on public.bus_stops (source_provider, source_service, city_code, external_stop_id)
  where external_stop_id is not null
    and octet_length(source_provider) <= 128
    and octet_length(source_service) <= 128
    and octet_length(city_code) <= 64
    and octet_length(external_stop_id) <= 512
    and octet_length(source_provider)
      + octet_length(source_service)
      + octet_length(city_code)
      + octet_length(external_stop_id) <= 1024;
create index idx_bus_stops_source_snapshot
  on public.bus_stops (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.bus_routes
  drop constraint bus_routes_external_route_id_key,
  add column city_code text not null default '39',
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz,
  add column source_deleted_at timestamptz,
  add constraint ck_bus_routes_city_code_nonblank check (btrim(city_code) <> '');

update public.bus_routes
set source_service = 'legacy'
where source_service is null or btrim(source_service) = '';

alter table public.bus_routes
  alter column source_service set default 'legacy',
  alter column source_service set not null,
  add constraint ck_bus_routes_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_service) <= 128
      and octet_length(city_code) <= 64
      and (
        external_route_id is null
        or octet_length(external_route_id) <= 512
      )
      and octet_length(source_provider)
        + octet_length(source_service)
        + octet_length(city_code)
        + coalesce(octet_length(external_route_id), 0) <= 1024
    ) not valid;

create unique index uq_bus_routes_provider_city_external
  on public.bus_routes (source_provider, source_service, city_code, external_route_id)
  where external_route_id is not null
    and octet_length(source_provider) <= 128
    and octet_length(source_service) <= 128
    and octet_length(city_code) <= 64
    and octet_length(external_route_id) <= 512
    and octet_length(source_provider)
      + octet_length(source_service)
      + octet_length(city_code)
      + octet_length(external_route_id) <= 1024;
create index idx_bus_routes_source_snapshot
  on public.bus_routes (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.route_stops
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz;

create index idx_route_stops_source_snapshot
  on public.route_stops (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.timetable_entries
  add column source_record_key text,
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column last_seen_at timestamptz not null default now(),
  add column stale_at timestamptz,
  add column tombstoned_at timestamptz;

update public.timetable_entries
set source_record_key = 'legacy:' || id::text
where source_record_key is null;

update public.timetable_entries
set valid_from = '-infinity'::date
where valid_from is null;

alter table public.timetable_entries
  alter column source_record_key set not null,
  alter column valid_from set default '-infinity'::date,
  alter column valid_from set not null,
  add constraint ck_timetable_source_record_nonblank
    check (btrim(source_record_key) <> ''),
  add constraint fk_timetable_route_direction_stop
    foreign key (route_id, direction_key, stop_id)
    references public.route_stops (route_id, direction_key, stop_id)
    on delete cascade
    not valid;

create unique index uq_timetable_provider_source_record_validity
  on public.timetable_entries (source_provider, source_record_key, valid_from)
  where octet_length(source_provider) <= 128
    and octet_length(source_record_key) <= 512
    and octet_length(source_provider)
      + octet_length(source_record_key) <= 768;
create index idx_timetable_route_direction_stop
  on public.timetable_entries (route_id, direction_key, stop_id)
  where octet_length(direction_key) <= 512;
create index idx_timetable_source_snapshot
  on public.timetable_entries (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.weather_observations
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null;
create index idx_weather_observations_source_snapshot
  on public.weather_observations (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.weather_forecasts
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null;
create index idx_weather_forecasts_source_snapshot
  on public.weather_forecasts (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.bus_arrival_snapshots
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null;
create index idx_bus_arrival_snapshots_source_snapshot
  on public.bus_arrival_snapshots (source_snapshot_id)
  where source_snapshot_id is not null;

alter table public.mobility_route_snapshots
  drop constraint mobility_route_snapshots_request_hash_key,
  add column source_snapshot_id uuid references public.external_api_snapshots(id) on delete set null,
  add column import_run_id uuid references public.data_import_runs(id) on delete set null;

update public.mobility_route_snapshots
set source_operation = 'route'
where source_operation is null or btrim(source_operation) = '';

alter table public.mobility_route_snapshots
  alter column source_operation set default 'route',
  alter column source_operation set not null,
  add constraint ck_mobility_request_hash_nonblank
    check (btrim(request_hash) <> '') not valid,
  add constraint ck_mobility_source_key_lengths
    check (
      octet_length(source_provider) <= 128
      and octet_length(source_operation) <= 128
      and octet_length(request_hash) <= 512
    ) not valid;

create unique index uq_mobility_provider_request_observed
  on public.mobility_route_snapshots (
    source_provider, source_operation, request_hash, observed_at
  )
  where octet_length(source_provider) <= 128
    and octet_length(source_operation) <= 128
    and octet_length(request_hash) <= 512
    and octet_length(source_provider)
      + octet_length(source_operation)
      + octet_length(request_hash) <= 1024;
create index idx_mobility_provider_request_latest
  on public.mobility_route_snapshots (
    source_provider, request_hash, observed_at desc
  )
  where octet_length(source_provider) <= 128
    and octet_length(request_hash) <= 512
    and octet_length(source_provider)
      + octet_length(request_hash) <= 768;
create index idx_mobility_source_snapshot
  on public.mobility_route_snapshots (source_snapshot_id)
  where source_snapshot_id is not null;
create index idx_mobility_import_run
  on public.mobility_route_snapshots (import_run_id)
  where import_run_id is not null;

alter table public.data_import_checkpoints enable row level security;
alter table public.external_api_snapshots enable row level security;
alter table public.tour_place_sources enable row level security;
alter table public.place_detail_items enable row level security;
alter table public.external_reference_codes enable row level security;

do $$
begin
  if exists (select 1 from pg_catalog.pg_roles where rolname = 'anon') then
    execute 'revoke all on public.data_import_checkpoints from anon';
    execute 'revoke all on public.external_api_snapshots from anon';
    execute 'revoke all on public.tour_place_sources from anon';
    execute 'revoke all on public.place_detail_items from anon';
    execute 'revoke all on public.external_reference_codes from anon';
    execute 'alter default privileges in schema public revoke all on tables from anon';
    execute 'alter default privileges in schema public revoke all on sequences from anon';
  end if;

  if exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.data_import_checkpoints from authenticated';
    execute 'revoke all on public.external_api_snapshots from authenticated';
    execute 'revoke all on public.tour_place_sources from authenticated';
    execute 'revoke all on public.place_detail_items from authenticated';
    execute 'revoke all on public.external_reference_codes from authenticated';
    execute 'alter default privileges in schema public revoke all on tables from authenticated';
    execute 'alter default privileges in schema public revoke all on sequences from authenticated';
  end if;

  if exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role') then
    execute 'grant all on public.data_import_checkpoints to service_role';
    execute 'grant all on public.external_api_snapshots to service_role';
    execute 'grant all on public.tour_place_sources to service_role';
    execute 'grant all on public.place_detail_items to service_role';
    execute 'grant all on public.external_reference_codes to service_role';
    execute 'alter default privileges in schema public grant all on tables to service_role';
    execute 'alter default privileges in schema public grant all on sequences to service_role';
  end if;
end $$;
