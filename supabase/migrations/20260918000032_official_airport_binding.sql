-- 공식 KAC 공항 대표좌표의 canonical UUID를 보존한다. 검증된 터미널 입구는 아니다.
create table public.approved_airport_place_bindings (
  place_id uuid primary key references public.tour_places(id),
  source_id text not null check (source_id = 'kac.airport'),
  external_id text not null check (external_id = 'CJU'),
  source_record_id text not null check (source_record_id = '제주'),
  dataset_id text not null check (dataset_id = '15002851'),
  source_date date not null,
  retrieved_at timestamptz not null,
  expires_at timestamptz not null,
  raw_sha256 text not null check (raw_sha256 ~ '^[0-9a-f]{64}$'),
  import_run_id uuid not null references public.data_import_runs(id),
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  unique(source_id, external_id),
  check (source_date <= (retrieved_at at time zone 'UTC')::date),
  check (expires_at > retrieved_at and expires_at <= retrieved_at + interval '30 days')
);

create index approved_airport_place_bindings_import_run_id_idx
  on public.approved_airport_place_bindings(import_run_id);

-- 잘못된 기존 장소, 실패 import, 오래된 원본을 설정값만으로 승인하지 않는다.
create view public.approved_airport_places as
select p.id, p.name, b.external_id, b.source_id, b.expires_at
from public.approved_airport_place_bindings b
join public.tour_places p on p.id=b.place_id and p.import_run_id=b.import_run_id
join public.data_import_runs r on r.id=b.import_run_id
join public.external_api_snapshots s on s.id=p.source_snapshot_id and s.import_run_id=r.id
where p.content_id is null and p.source_provider='한국공항공사'
  and p.source_service='15002851' and p.name='제주국제공항'
  and r.source_kind='admin_upload' and r.source_name='kac.airport'
  and r.source_operation='15002851' and r.status='succeeded'
  and r.source_provider='한국공항공사' and r.source_service='15002851'
  and s.source_provider=r.source_provider and s.source_service=r.source_service
  and s.source_operation=r.source_operation and s.parse_status='parsed'
  and s.payload_hash=b.raw_sha256
  and b.retrieved_at=s.fetched_at
  and b.expires_at<=s.fetched_at+interval '30 days'
  and (s.expires_at is null or s.expires_at>statement_timestamp())
  and r.metadata->>'raw_sha256'=b.raw_sha256
  and r.metadata->>'source_date'=b.source_date::text
  and st_y(p.location::geometry)=b.latitude and st_x(p.location::geometry)=b.longitude
  and not p.stale and p.tombstoned_at is null and p.source_deleted_at is null
  and (p.stale_at is null or p.stale_at>statement_timestamp())
  and b.retrieved_at<=statement_timestamp() and b.expires_at>statement_timestamp();

alter table public.approved_airport_place_bindings enable row level security;
revoke all on public.approved_airport_place_bindings from public,anon,authenticated;
revoke all on public.approved_airport_places from public,anon,authenticated;
grant select,insert,update on public.approved_airport_place_bindings to service_role;
grant select on public.approved_airport_places to service_role;
