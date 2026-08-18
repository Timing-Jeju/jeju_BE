create table public.place_stay_policy_versions (
  version text primary key,
  status text not null,
  payload_hash text not null,
  effective_at timestamptz not null,
  imported_at timestamptz not null,
  constraint ck_place_stay_policy_version_syntax
    check (version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
  constraint ck_place_stay_policy_version_status
    check (status in ('draft', 'active', 'retired')),
  constraint ck_place_stay_policy_payload_hash
    check (payload_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_place_stay_policy_effective_time
    check (effective_at <= imported_at)
);

create unique index uq_place_stay_policy_single_active
  on public.place_stay_policy_versions ((status))
  where status = 'active';

create table public.place_stay_policies (
  version text not null references public.place_stay_policy_versions(version) on delete restrict,
  scope text not null,
  category text,
  place_id uuid references public.tour_places(id) on delete restrict,
  minutes integer not null,
  source text not null default 'app_curation',
  updated_at timestamptz not null,
  constraint ck_place_stay_policy_scope
    check (scope in ('category_default', 'place_override')),
  constraint ck_place_stay_policy_exact_scope
    check (
      (scope = 'category_default' and category is not null and place_id is null)
      or (scope = 'place_override' and category is null and place_id is not null)
    ),
  constraint ck_place_stay_policy_category_syntax
    check (category is null or category ~ '^[A-Za-z0-9:_-]{1,64}$'),
  constraint ck_place_stay_policy_minutes
    check (minutes between 5 and 1440),
  constraint ck_place_stay_policy_source
    check (source = 'app_curation')
);

create unique index uq_place_stay_policy_category
  on public.place_stay_policies (version, category)
  where scope = 'category_default';

create unique index uq_place_stay_policy_place
  on public.place_stay_policies (version, place_id)
  where scope = 'place_override';

create index idx_place_stay_policy_place_lookup
  on public.place_stay_policies (place_id, version)
  where scope = 'place_override';

create index idx_place_stay_policy_category_lookup
  on public.place_stay_policies (category, version)
  where scope = 'category_default';

alter table public.place_stay_policy_versions enable row level security;
alter table public.place_stay_policies enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    revoke all on public.place_stay_policy_versions from anon;
    revoke all on public.place_stay_policies from anon;
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    revoke all on public.place_stay_policy_versions from authenticated;
    revoke all on public.place_stay_policies from authenticated;
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    grant select, insert, update on public.place_stay_policy_versions to service_role;
    grant select, insert on public.place_stay_policies to service_role;
  end if;
end
$$;
