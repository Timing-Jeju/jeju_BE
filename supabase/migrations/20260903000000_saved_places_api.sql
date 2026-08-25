-- Issue #34: canonical owner CRUD, optimistic versioning and 24 hour POST idempotency.
create function public.saved_place_tags_valid(values_to_check text[])
returns boolean language sql immutable set search_path='' as $$
  select cardinality(values_to_check) <= 20
    and array_position(values_to_check, null) is null
    and not exists (
      select 1 from unnest(values_to_check) value
      where char_length(value) not between 1 and 50
        or value <> btrim(value)
        or value <> normalize(value, NFC)
        or value ~ '[[:cntrl:]]'
    )
    and cardinality(values_to_check) = (select count(distinct value) from unnest(values_to_check) value)
    and values_to_check = array(select value from unnest(values_to_check) value order by value collate "C")
$$;

-- Preserve repaired legacy values for at most 30 days and erase them with the owning account.
create table public.saved_places_backfill_audit (
  saved_place_id uuid primary key,
  user_id uuid references public.user_profiles(id) on delete cascade,
  session_id uuid references public.app_sessions(id) on delete cascade,
  original_memo text,
  original_tags text[] not null,
  original_priority integer not null,
  original_target_day integer,
  reasons text[] not null,
  captured_at timestamptz not null default now(),
  purge_after timestamptz not null default (now() + interval '30 days'),
  constraint ck_saved_places_backfill_owner
    check (num_nonnulls(user_id,session_id) between 1 and 2),
  constraint ck_saved_places_backfill_retention
    check (purge_after <= captured_at + interval '30 days')
);
alter table public.saved_places_backfill_audit enable row level security;
create index ix_saved_places_backfill_purge
  on public.saved_places_backfill_audit(purge_after);
create index ix_saved_places_backfill_user_fk
  on public.saved_places_backfill_audit(user_id);
create index ix_saved_places_backfill_session_fk
  on public.saved_places_backfill_audit(session_id);

insert into public.saved_places_backfill_audit(
  saved_place_id,user_id,session_id,original_memo,original_tags,original_priority,
  original_target_day,reasons)
select saved.id,saved.user_id,saved.session_id,saved.memo,saved.tags,saved.priority,saved.target_day,
  array_remove(array[
    case when saved.priority not between 0 and 5 then 'priority_out_of_range' end,
    case when saved.target_day is not null and saved.target_day not between 1 and 365
         then 'target_day_out_of_range' end,
    case when saved.memo is not null and (
      saved.memo <> btrim(saved.memo) or char_length(saved.memo) not between 1 and 2000
      or saved.memo <> normalize(saved.memo, NFC)
      or saved.memo ~ '[[:cntrl:]]') then 'memo_noncanonical' end,
    case when not public.saved_place_tags_valid(saved.tags) then 'tags_noncanonical' end,
    case when saved.user_id is not null and saved.session_id is not null
         then 'legacy_dual_owner' end
  ],null)
from public.saved_places saved
where saved.priority not between 0 and 5
   or (saved.target_day is not null and saved.target_day not between 1 and 365)
   or (saved.memo is not null and (
     saved.memo <> btrim(saved.memo) or char_length(saved.memo) not between 1 and 2000
     or saved.memo <> normalize(saved.memo, NFC)
     or saved.memo ~ '[[:cntrl:]]'))
   or not public.saved_place_tags_valid(saved.tags)
   or (saved.user_id is not null and saved.session_id is not null);

-- Existing rows predate the canonical bounds. The original is retained above; the live row is
-- deterministically repaired so a migration reset and an in-place upgrade both succeed.
update public.saved_places saved
set priority = greatest(0, least(5, saved.priority)),
    session_id = case when user_id is not null then null else session_id end,
    target_day = case when saved.target_day is null then null
                      else greatest(1, least(365, saved.target_day)) end,
    memo = case when saved.memo is null then null
                else nullif(left(normalize(
                  regexp_replace(btrim(saved.memo), '[[:cntrl:]]', '', 'g'), NFC), 2000), '') end,
    tags = array(
      select canonical_value
      from (
        select distinct normalize(btrim(value), NFC) as canonical_value
        from unnest(saved.tags) value
        where value is not null
          and char_length(normalize(btrim(value), NFC)) between 1 and 50
          and normalize(btrim(value), NFC) !~ '[[:cntrl:]]'
      ) canonical_tags
      order by canonical_value collate "C"
      limit 20
    );

alter table public.saved_places
  drop constraint saved_places_check,
  add column version bigint not null default 1,
  add constraint ck_saved_places_exactly_one_owner
    check (num_nonnulls(user_id,session_id) = 1),
  add constraint ck_saved_places_priority_range check (priority between 0 and 5),
  add constraint ck_saved_places_target_day_range check (target_day is null or target_day between 1 and 365),
  add constraint ck_saved_places_memo check (
    memo is null or (
      memo = btrim(memo) and memo = normalize(memo, NFC)
      and char_length(memo) between 1 and 2000 and memo !~ '[[:cntrl:]]'
    )
  ),
  add constraint ck_saved_places_tags check (public.saved_place_tags_valid(tags));

create index ix_saved_places_owner_priority
  on public.saved_places(user_id, priority desc, created_at desc, place_id)
  where user_id is not null;
create index ix_saved_places_owner_target_day
  on public.saved_places(user_id, target_day asc nulls last, created_at desc, place_id)
  where user_id is not null;

create policy saved_places_owner_insert on public.saved_places for insert to authenticated
  with check (user_id = (select auth.uid()) and session_id is null);
create policy saved_places_owner_update on public.saved_places for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and session_id is null);
create policy saved_places_owner_delete on public.saved_places for delete to authenticated
  using (user_id = (select auth.uid()));

create table public.saved_place_idempotency (
  owner_sub uuid not null references auth.users(id) on delete cascade,
  idempotency_key varchar(128) not null,
  request_hash char(64) not null,
  place_id uuid not null,
  created boolean not null,
  response_etag varchar(64) not null,
  response_name text not null,
  response_category text not null,
  response_region_label text,
  response_thumbnail_url text,
  response_recommended_stay_minutes integer,
  response_memo text,
  response_tags text[] not null,
  response_priority integer not null,
  response_target_day integer,
  response_saved_at timestamptz not null,
  response_updated_at timestamptz not null,
  response_status smallint,
  response_content_type text,
  response_location text,
  response_body bytea,
  expires_at timestamptz not null,
  primary key(owner_sub, idempotency_key),
  constraint ck_saved_place_idempotency_key check (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
  constraint ck_saved_place_idempotency_hash check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_saved_place_idempotency_snapshot check (
    num_nonnulls(response_status,response_content_type,response_location,response_body) = 0
    or (
      num_nonnulls(response_status,response_content_type,response_location,response_body) = 4
      and (response_status in (200,201)) is true
      and (response_content_type = 'application/json') is true
      and (response_location like '/api/v1/me/saved-places/%') is true
      and (octet_length(response_body) <= 1048576) is true
    )
  )
);
create index ix_saved_place_idempotency_expiry on public.saved_place_idempotency(expires_at);
alter table public.saved_place_idempotency enable row level security;

create function public.protect_saved_place_idempotency_snapshot()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.response_body is not null and (
    new.response_status is distinct from old.response_status
    or new.response_content_type is distinct from old.response_content_type
    or new.response_location is distinct from old.response_location
    or new.response_etag is distinct from old.response_etag
    or new.response_body is distinct from old.response_body
  ) then
    raise exception 'completed saved-place idempotency snapshot is immutable';
  end if;
  return new;
end;
$$;
create trigger trg_saved_place_idempotency_snapshot_immutable
before update on public.saved_place_idempotency
for each row execute function public.protect_saved_place_idempotency_snapshot();

do $$
begin
  if exists (select 1 from pg_roles where rolname='anon') then
    execute 'revoke all on public.saved_place_idempotency from anon';
    execute 'revoke all on public.saved_places_backfill_audit from anon';
  end if;
  if exists (select 1 from pg_roles where rolname='authenticated') then
    execute 'revoke all privileges on table public.saved_places from authenticated';
    execute 'revoke all privileges on table public.saved_place_idempotency from authenticated';
    execute 'revoke all privileges on table public.saved_places_backfill_audit from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname='service_role') then
    execute 'revoke all privileges on table public.saved_places from service_role';
    execute 'grant select,insert,update,delete on public.saved_places to service_role';
    execute 'revoke all privileges on table public.saved_place_idempotency from service_role';
    execute 'grant select,insert,update,delete on public.saved_place_idempotency to service_role';
    execute 'revoke all privileges on table public.saved_places_backfill_audit from service_role';
    execute 'grant select,delete on public.saved_places_backfill_audit to service_role';
  end if;
end $$;

revoke all on function public.protect_saved_place_idempotency_snapshot() from public;
