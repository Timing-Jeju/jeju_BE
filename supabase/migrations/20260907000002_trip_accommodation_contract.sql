-- Issue #68: trip accommodation CRUD, exact identity and replay-safe POST contract.
do $$
begin
  if exists (
    select 1
    from public.trip_accommodations accommodation
    where num_nonnulls(accommodation.place_id, accommodation.custom_name) <> 1
       or accommodation.check_in_time is null
       or accommodation.check_out_time is null
       or (
         accommodation.custom_name is not null
         and (
           accommodation.custom_name <> btrim(accommodation.custom_name)
           or accommodation.custom_name <> normalize(accommodation.custom_name, NFC)
           or char_length(accommodation.custom_name) not between 1 and 100
           or accommodation.custom_name ~ '[[:cntrl:]]'
         )
       )
  ) then
    raise exception 'legacy accommodation contract conflict';
  end if;
end;
$$;

do $$
declare
  existing_constraint record;
begin
  for existing_constraint in
    select constraint_row.conname
    from pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_accommodations'::regclass
      and constraint_row.contype = 'c'
      and pg_get_constraintdef(constraint_row.oid) ilike '%place_id%'
      and pg_get_constraintdef(constraint_row.oid) ilike '%custom_name%'
  loop
    execute format(
      'alter table public.trip_accommodations drop constraint %I',
      existing_constraint.conname
    );
  end loop;
end;
$$;

alter table public.trip_accommodations
  alter column check_in_time set not null,
  alter column check_out_time set not null,
  add constraint ck_trip_accommodations_exactly_one_identity
    check (num_nonnulls(place_id, custom_name) = 1),
  add constraint ck_trip_accommodations_custom_name_canonical
    check (
      custom_name is null or (
        custom_name = btrim(custom_name)
        and custom_name = normalize(custom_name, NFC)
        and char_length(custom_name) between 1 and 100
        and custom_name !~ '[[:cntrl:]]'
      )
    );

create table public.accommodation_idempotency (
  owner_sub uuid not null references auth.users(id) on delete cascade,
  trip_plan_id uuid not null references public.trip_plans(id) on delete cascade,
  idempotency_key varchar(128) not null,
  request_hash char(64) not null,
  accommodation_id uuid not null,
  response_status smallint,
  response_content_type text,
  response_location text,
  response_etag varchar(96),
  response_body bytea,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  primary key (owner_sub, trip_plan_id, idempotency_key),
  constraint ck_accommodation_idempotency_key
    check (idempotency_key ~ '^[!-~]{1,128}$'),
  constraint ck_accommodation_idempotency_hash
    check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_accommodation_idempotency_retention
    check (expires_at = created_at + interval '24 hours'),
  constraint ck_accommodation_idempotency_snapshot
    check (
      num_nonnulls(
        response_status,
        response_content_type,
        response_location,
        response_etag,
        response_body
      ) = 0
      or (
        num_nonnulls(
          response_status,
          response_content_type,
          response_location,
          response_etag,
          response_body
        ) = 5
        and response_status = 201
        and response_content_type = 'application/json'
        and response_location like '/api/v1/trips/%/accommodations/%'
        and response_etag ~ '^"trip-[1-9][0-9]*"$'
        and octet_length(response_body) <= 1048576
      )
    )
);

create index ix_accommodation_idempotency_expiry
  on public.accommodation_idempotency(expires_at);

create index ix_accommodation_idempotency_trip
  on public.accommodation_idempotency(trip_plan_id);

alter table public.accommodation_idempotency enable row level security;

create function public.protect_accommodation_idempotency_snapshot()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.response_body is not null and (
    new.owner_sub is distinct from old.owner_sub
    or new.trip_plan_id is distinct from old.trip_plan_id
    or new.idempotency_key is distinct from old.idempotency_key
    or new.request_hash is distinct from old.request_hash
    or new.accommodation_id is distinct from old.accommodation_id
    or new.created_at is distinct from old.created_at
    or new.expires_at is distinct from old.expires_at
    or new.response_status is distinct from old.response_status
    or new.response_content_type is distinct from old.response_content_type
    or new.response_location is distinct from old.response_location
    or new.response_etag is distinct from old.response_etag
    or new.response_body is distinct from old.response_body
  ) then
    raise exception 'completed accommodation idempotency snapshot is immutable';
  end if;
  return new;
end;
$$;

create trigger trg_accommodation_idempotency_snapshot_immutable
before update on public.accommodation_idempotency
for each row execute function public.protect_accommodation_idempotency_snapshot();

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.accommodation_idempotency from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.trip_accommodations from authenticated';
    execute 'revoke all on public.accommodation_idempotency from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on public.trip_accommodations from service_role';
    execute 'grant select,insert,update,delete on public.trip_accommodations to service_role';
    execute 'revoke all on public.accommodation_idempotency from service_role';
    execute 'grant select,insert,update,delete on public.accommodation_idempotency to service_role';
  end if;
end;
$$;

revoke all on function public.protect_accommodation_idempotency_snapshot() from public;
