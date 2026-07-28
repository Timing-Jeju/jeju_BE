create schema if not exists auth;

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin bypassrls;
  end if;
exception
  when insufficient_privilege then
    null;
end $$;

do $$
begin
  if not exists (
    select 1
    from information_schema.tables
    where table_schema = 'auth'
      and table_name = 'users'
  ) then
    create table auth.users (
      id uuid primary key default gen_random_uuid(),
      email text unique,
      raw_app_meta_data jsonb not null default '{}'::jsonb,
      raw_user_meta_data jsonb not null default '{}'::jsonb,
      created_at timestamptz not null default now(),
      updated_at timestamptz not null default now(),
      last_sign_in_at timestamptz
    );
  end if;

  if not exists (
    select 1
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'auth'
      and p.proname = 'uid'
  ) then
    execute 'create function auth.uid() returns uuid language sql stable as $func$ select nullif(current_setting(''request.jwt.claim.sub'', true), '''')::uuid $func$';
  end if;
end $$;

create table data_import_runs (
  id uuid primary key default gen_random_uuid(),
  source_kind text not null check (source_kind in (
    'fixture', 'tour_api', 'tago', 'jeju_bis', 'weather_api',
    'directions_api', 'admin_upload'
  )),
  source_name text not null,
  source_operation text,
  data_version text not null,
  status text not null check (status in ('running', 'succeeded', 'failed')),
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  row_count integer not null default 0 check (row_count >= 0),
  error_code text,
  error_message text,
  metadata jsonb not null default '{}'::jsonb
);

create index idx_data_import_runs_latest
  on data_import_runs (source_kind, source_name, data_version, started_at desc);

create table app_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid,
  public_token text not null unique,
  display_name text,
  locale text not null default 'ko-KR',
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  expires_at timestamptz
);

create table user_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text unique,
  nickname text,
  profile_image_url text,
  locale text not null default 'ko-KR',
  status text not null default 'active' check (status in ('active', 'blocked', 'deleted')),
  onboarding_completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_login_at timestamptz,
  deleted_at timestamptz
);

create table social_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references user_profiles(id) on delete cascade,
  provider text not null check (provider in ('kakao', 'naver', 'google')),
  provider_user_id text not null,
  provider_email text,
  provider_email_verified boolean,
  provider_nickname text,
  provider_profile_image_url text,
  scopes text[] not null default '{}',
  connected_at timestamptz not null default now(),
  last_login_at timestamptz,
  revoked_at timestamptz,
  raw_profile jsonb not null default '{}'::jsonb,
  unique (provider, provider_user_id),
  unique (user_id, provider)
);

alter table app_sessions
  add constraint fk_app_sessions_user
  foreign key (user_id) references user_profiles(id) on delete set null;

create index idx_app_sessions_user
  on app_sessions (user_id, last_seen_at desc);

create index idx_social_accounts_user
  on social_accounts (user_id, connected_at desc);

create table legal_documents (
  id uuid primary key default gen_random_uuid(),
  document_type text not null check (document_type in ('terms', 'privacy', 'location', 'marketing')),
  version text not null,
  title text not null,
  content_url text not null,
  required boolean not null default true,
  effective_at timestamptz not null,
  retired_at timestamptz,
  created_at timestamptz not null default now(),
  unique (document_type, version)
);

create table user_consents (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references user_profiles(id) on delete cascade,
  legal_document_id uuid not null references legal_documents(id),
  agreed boolean not null,
  agreed_at timestamptz not null default now(),
  withdrawn_at timestamptz,
  source text not null default 'web' check (source in ('web', 'mobile', 'admin')),
  created_at timestamptz not null default now(),
  unique (user_id, legal_document_id)
);

create index idx_user_consents_user
  on user_consents (user_id, agreed_at desc);

create index idx_user_consents_document
  on user_consents (legal_document_id);

create table tour_places (
  id uuid primary key default gen_random_uuid(),
  external_place_id text unique,
  content_id text,
  content_type_id text,
  name text not null,
  normalized_name text not null,
  category text not null,
  region_code text,
  region_label text,
  address text,
  address_detail text,
  location geography(Point, 4326) not null,
  image_url text,
  thumbnail_url text,
  overview text,
  recommended_stay_minutes integer check (recommended_stay_minutes is null or recommended_stay_minutes >= 0),
  source_provider text not null,
  source_service text,
  source_modified_at timestamptz,
  import_run_id uuid references data_import_runs(id),
  confidence numeric(4,3) not null default 1.000 check (confidence >= 0 and confidence <= 1),
  stale boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index uq_tour_places_content_id
  on tour_places (content_id)
  where content_id is not null;

create index idx_tour_places_import_run
  on tour_places (import_run_id);

create index idx_tour_places_location
  on tour_places using gist (location);

create index idx_tour_places_name
  on tour_places (normalized_name);

create index idx_tour_places_category_region
  on tour_places (category, region_code);

create table place_details (
  place_id uuid primary key references tour_places(id) on delete cascade,
  phone text,
  homepage_url text,
  operating_hours_text text,
  closed_days_text text,
  parking_text text,
  pet_policy_text text,
  admission_fee_text text,
  facilities_text text,
  reservation_info_text text,
  accessibility_text text,
  intro_attributes jsonb not null default '{}'::jsonb,
  source_provider text not null,
  source_service text,
  source_updated_at timestamptz,
  fetched_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table place_operating_hours (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references tour_places(id) on delete cascade,
  day_of_week smallint not null check (day_of_week between 0 and 6),
  is_closed boolean not null default false,
  open_time time,
  close_time time,
  last_entry_time time,
  note text,
  source_kind text not null default 'parsed' check (source_kind in ('source', 'parsed', 'manual')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (place_id, day_of_week),
  check (not is_closed or (open_time is null and close_time is null))
);

create index idx_place_operating_hours_place
  on place_operating_hours (place_id, day_of_week);

create table place_aliases (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references tour_places(id) on delete cascade,
  alias text not null,
  normalized_alias text not null,
  alias_type text not null check (alias_type in ('official', 'keyword', 'fallback_stop_keyword', 'user_query')),
  confidence numeric(4,3) not null default 1.000 check (confidence >= 0 and confidence <= 1),
  created_at timestamptz not null default now()
);

create unique index uq_place_aliases_place_alias_type
  on place_aliases (place_id, normalized_alias, alias_type);

create index idx_place_aliases_lookup
  on place_aliases (normalized_alias);

create table place_images (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references tour_places(id) on delete cascade,
  image_url text not null,
  thumbnail_url text,
  display_order integer not null default 0,
  source_provider text not null default '한국관광공사',
  created_at timestamptz not null default now()
);

create index idx_place_images_place_order
  on place_images (place_id, display_order);

create table saved_places (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references user_profiles(id) on delete cascade,
  session_id uuid references app_sessions(id) on delete cascade,
  place_id uuid not null references tour_places(id) on delete cascade,
  memo text,
  tags text[] not null default '{}',
  target_day integer check (target_day is null or target_day > 0),
  priority integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (user_id is not null or session_id is not null)
);

create index idx_saved_places_session
  on saved_places (session_id, created_at desc);

create index idx_saved_places_user
  on saved_places (user_id, created_at desc);

create index idx_saved_places_place
  on saved_places (place_id);

create unique index uq_saved_places_user_place
  on saved_places (user_id, place_id)
  where user_id is not null;

create unique index uq_saved_places_session_place
  on saved_places (session_id, place_id)
  where session_id is not null;

create index idx_saved_places_tags
  on saved_places using gin (tags);

create table bus_stops (
  id uuid primary key default gen_random_uuid(),
  external_stop_id text unique,
  node_id text not null,
  node_name text not null,
  node_no text,
  location geography(Point, 4326) not null,
  source_provider text not null default 'TAGO',
  source_service text,
  import_run_id uuid references data_import_runs(id),
  confidence numeric(4,3) not null default 1.000 check (confidence >= 0 and confidence <= 1),
  stale boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index uq_bus_stops_node_id
  on bus_stops (node_id);

create index idx_bus_stops_import_run
  on bus_stops (import_run_id);

create index idx_bus_stops_location
  on bus_stops using gist (location);

create index idx_bus_stops_node_name
  on bus_stops (node_name);

create table place_stop_links (
  place_id uuid not null references tour_places(id) on delete cascade,
  stop_id uuid not null references bus_stops(id) on delete cascade,
  distance_meters integer not null check (distance_meters >= 0),
  walk_minutes integer not null check (walk_minutes >= 0),
  link_method text not null check (link_method in ('spatial_radius', 'fixture', 'manual', 'api_nearby')),
  confidence numeric(4,3) not null default 1.000 check (confidence >= 0 and confidence <= 1),
  created_at timestamptz not null default now(),
  primary key (place_id, stop_id)
);

create index idx_place_stop_links_stop
  on place_stop_links (stop_id);

create index idx_place_stop_links_place_distance
  on place_stop_links (place_id, distance_meters);

create table weather_grid_points (
  id uuid primary key default gen_random_uuid(),
  grid_provider text not null default 'KMA',
  nx integer not null,
  ny integer not null,
  region_name text,
  representative_location geography(Point, 4326),
  nearest_place_id uuid references tour_places(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (grid_provider, nx, ny)
);

create index idx_weather_grid_points_location
  on weather_grid_points using gist (representative_location);

create index idx_weather_grid_points_nearest_place
  on weather_grid_points (nearest_place_id);

create table weather_observations (
  id uuid primary key default gen_random_uuid(),
  grid_point_id uuid not null references weather_grid_points(id) on delete cascade,
  observed_at timestamptz not null,
  base_date date not null,
  base_time time not null,
  temperature_c numeric(5,2),
  precipitation_mm numeric(6,2),
  precipitation_type text,
  humidity_percent integer check (humidity_percent is null or humidity_percent between 0 and 100),
  wind_speed_mps numeric(5,2),
  wind_direction_deg integer check (wind_direction_deg is null or wind_direction_deg between 0 and 360),
  source_provider text not null default 'KMA',
  source_operation text not null,
  import_run_id uuid references data_import_runs(id),
  raw_payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique (grid_point_id, observed_at, source_operation)
);

create index idx_weather_observations_grid_time
  on weather_observations (grid_point_id, observed_at desc);

create index idx_weather_observations_import_run
  on weather_observations (import_run_id);

create table weather_forecasts (
  id uuid primary key default gen_random_uuid(),
  grid_point_id uuid not null references weather_grid_points(id) on delete cascade,
  forecasted_at timestamptz not null,
  valid_at timestamptz not null,
  forecast_type text not null check (forecast_type in ('ultra_short', 'short')),
  sky_code text,
  precipitation_type text,
  precipitation_probability_percent integer check (
    precipitation_probability_percent is null or precipitation_probability_percent between 0 and 100
  ),
  precipitation_amount_mm numeric(6,2),
  temperature_c numeric(5,2),
  min_temperature_c numeric(5,2),
  max_temperature_c numeric(5,2),
  humidity_percent integer check (humidity_percent is null or humidity_percent between 0 and 100),
  wind_speed_mps numeric(5,2),
  source_provider text not null default 'KMA',
  source_operation text not null,
  import_run_id uuid references data_import_runs(id),
  raw_payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique (grid_point_id, forecasted_at, valid_at, forecast_type)
);

create index idx_weather_forecasts_grid_valid
  on weather_forecasts (grid_point_id, valid_at);

create index idx_weather_forecasts_valid
  on weather_forecasts (valid_at);

create index idx_weather_forecasts_import_run
  on weather_forecasts (import_run_id);

create table bus_routes (
  id uuid primary key default gen_random_uuid(),
  external_route_id text unique,
  route_no text not null,
  route_type text,
  direction_name text,
  source_provider text not null default 'TAGO',
  source_service text,
  import_run_id uuid references data_import_runs(id),
  confidence numeric(4,3) not null default 1.000 check (confidence >= 0 and confidence <= 1),
  stale boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index idx_bus_routes_route_no
  on bus_routes (route_no);

create index idx_bus_routes_import_run
  on bus_routes (import_run_id);

create table route_stops (
  route_id uuid not null references bus_routes(id) on delete cascade,
  stop_id uuid not null references bus_stops(id) on delete cascade,
  direction_key text not null default 'default',
  stop_sequence integer not null check (stop_sequence > 0),
  travel_minutes_from_prev integer check (travel_minutes_from_prev is null or travel_minutes_from_prev >= 0),
  import_run_id uuid references data_import_runs(id),
  primary key (route_id, direction_key, stop_sequence),
  unique (route_id, direction_key, stop_id)
);

create index idx_route_stops_stop
  on route_stops (stop_id, route_id, direction_key, stop_sequence);

create index idx_route_stops_import_run
  on route_stops (import_run_id);

create table timetable_entries (
  id uuid primary key default gen_random_uuid(),
  route_id uuid not null references bus_routes(id) on delete cascade,
  stop_id uuid not null references bus_stops(id) on delete cascade,
  direction_key text not null default 'default',
  service_day_type text not null check (service_day_type in ('weekday', 'saturday', 'sunday', 'holiday', 'daily')),
  departure_time time not null,
  trip_key text,
  valid_from date,
  valid_to date,
  source_provider text not null,
  import_run_id uuid references data_import_runs(id),
  confidence numeric(4,3) not null default 1.000 check (confidence >= 0 and confidence <= 1),
  stale boolean not null default false,
  created_at timestamptz not null default now(),
  check (valid_to is null or valid_from is null or valid_to >= valid_from)
);

create index idx_timetable_stop_day_time
  on timetable_entries (stop_id, service_day_type, departure_time);

create index idx_timetable_route_day_time
  on timetable_entries (route_id, service_day_type, departure_time);

create index idx_timetable_import_run
  on timetable_entries (import_run_id);

create index idx_timetable_trip
  on timetable_entries (trip_key)
  where trip_key is not null;

create table bus_arrival_snapshots (
  id uuid primary key default gen_random_uuid(),
  stop_id uuid not null references bus_stops(id) on delete cascade,
  route_id uuid references bus_routes(id) on delete set null,
  external_route_id text,
  route_no text not null,
  direction_name text,
  estimated_arrival_seconds integer not null check (estimated_arrival_seconds >= 0),
  remaining_stops integer check (remaining_stops is null or remaining_stops >= 0),
  vehicle_type text,
  observed_at timestamptz not null default now(),
  expires_at timestamptz not null,
  source_provider text not null default 'TAGO',
  source_operation text,
  import_run_id uuid references data_import_runs(id),
  raw_payload jsonb not null default '{}'::jsonb,
  check (expires_at >= observed_at)
);

create index idx_arrival_snapshots_stop_observed
  on bus_arrival_snapshots (stop_id, observed_at desc);

create index idx_arrival_snapshots_stop_expires
  on bus_arrival_snapshots (stop_id, expires_at);

create index idx_arrival_snapshots_route
  on bus_arrival_snapshots (route_id, observed_at desc);

create index idx_arrival_snapshots_import_run
  on bus_arrival_snapshots (import_run_id);

create table mobility_route_snapshots (
  id uuid primary key default gen_random_uuid(),
  request_hash text not null unique,
  origin_location geography(Point, 4326) not null,
  destination_location geography(Point, 4326) not null,
  transport_mode text not null check (transport_mode in ('walk', 'public_transit', 'rental_car', 'taxi')),
  departure_at timestamptz,
  distance_meters integer check (distance_meters is null or distance_meters >= 0),
  duration_minutes integer not null check (duration_minutes >= 0),
  estimated_fare integer check (estimated_fare is null or estimated_fare >= 0),
  source_provider text not null,
  source_operation text,
  route_summary jsonb not null default '{}'::jsonb,
  observed_at timestamptz not null default now(),
  expires_at timestamptz not null,
  raw_payload jsonb not null default '{}'::jsonb,
  check (expires_at >= observed_at)
);

create index idx_mobility_route_snapshots_origin
  on mobility_route_snapshots using gist (origin_location);

create index idx_mobility_route_snapshots_destination
  on mobility_route_snapshots using gist (destination_location);

create index idx_mobility_route_snapshots_expiry
  on mobility_route_snapshots (transport_mode, expires_at);

create table trip_plans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references user_profiles(id) on delete set null,
  session_id uuid references app_sessions(id) on delete set null,
  public_token text not null unique,
  title text,
  status text not null default 'draft' check (status in (
    'draft', 'generating', 'planned', 'live', 'completed', 'cancelled', 'failed'
  )),
  input_text text,
  start_date date not null,
  end_date date not null,
  user_pace text not null default 'normal' check (user_pace in ('slow', 'normal', 'fast')),
  total_score integer check (total_score is null or total_score between 0 and 100),
  source_mode text not null check (source_mode in ('fixture', 'live', 'mixed')),
  data_version text not null,
  stale boolean not null default false,
  active_schedule_version_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  expires_at timestamptz,
  check (end_date >= start_date),
  check (user_id is not null or session_id is not null)
);

create index idx_trip_plans_session_created
  on trip_plans (session_id, created_at desc);

create index idx_trip_plans_user_created
  on trip_plans (user_id, created_at desc);

create index idx_trip_plans_status
  on trip_plans (status, updated_at desc);

create table trip_preferences (
  trip_plan_id uuid primary key references trip_plans(id) on delete cascade,
  preferred_categories text[] not null default '{}',
  arrival_region_code text,
  departure_region_code text,
  preferred_region_codes text[] not null default '{}',
  start_place_id uuid references tour_places(id),
  end_place_id uuid references tour_places(id),
  raw_answers jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index idx_trip_preferences_start_place
  on trip_preferences (start_place_id);

create index idx_trip_preferences_end_place
  on trip_preferences (end_place_id);

create table trip_transport_modes (
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  transport_mode text not null check (transport_mode in ('public_transit', 'rental_car', 'taxi')),
  priority smallint not null check (priority between 1 and 3),
  is_primary boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (trip_plan_id, transport_mode),
  unique (trip_plan_id, priority)
);

create unique index uq_trip_transport_modes_primary
  on trip_transport_modes (trip_plan_id)
  where is_primary;

create table trip_place_preferences (
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  place_id uuid not null references tour_places(id) on delete cascade,
  preference_type text not null check (preference_type in ('must_visit', 'avoid')),
  target_day_no integer check (target_day_no is null or target_day_no > 0),
  priority integer not null default 0,
  source text not null default 'user_input' check (source in ('user_input', 'saved_place', 'ai_suggested')),
  created_at timestamptz not null default now(),
  primary key (trip_plan_id, place_id, preference_type)
);

create index idx_trip_place_preferences_place
  on trip_place_preferences (place_id, preference_type);

create table trip_transport_events (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  event_type text not null check (event_type in ('arrival', 'departure')),
  transport_type text not null check (transport_type in ('flight', 'ferry')),
  terminal_place_id uuid references tour_places(id),
  terminal_name text,
  scheduled_at timestamptz not null,
  transport_number text,
  source text not null default 'user_input' check (source in ('user_input', 'external_api', 'admin')),
  note text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (trip_plan_id, event_type),
  check (terminal_place_id is not null or terminal_name is not null)
);

create index idx_trip_transport_events_terminal
  on trip_transport_events (terminal_place_id);

create index idx_trip_transport_events_schedule
  on trip_transport_events (trip_plan_id, scheduled_at);

create table trip_accommodations (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  place_id uuid references tour_places(id),
  custom_name text,
  check_in_date date not null,
  check_out_date date not null,
  check_in_time time,
  check_out_time time,
  address_text text,
  sequence_no integer not null check (sequence_no > 0),
  source text not null default 'user_input' check (source in ('user_input', 'ai_suggested', 'admin')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (trip_plan_id, sequence_no),
  check (check_out_date > check_in_date),
  check (place_id is not null or custom_name is not null)
);

alter table trip_accommodations
  add constraint ex_trip_accommodations_no_date_overlap
  exclude using gist (
    trip_plan_id with =,
    daterange(check_in_date, check_out_date, '[)') with &&
  );

create index idx_trip_accommodations_place
  on trip_accommodations (place_id);

create index idx_trip_accommodations_dates
  on trip_accommodations (trip_plan_id, check_in_date, check_out_date);

create table trip_days (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  day_no integer not null check (day_no > 0),
  trip_date date not null,
  start_time time,
  end_time time,
  title text,
  safety_score integer check (safety_score is null or safety_score between 0 and 100),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (trip_plan_id, day_no),
  unique (trip_plan_id, trip_date),
  unique (id, trip_plan_id),
  check (end_time is null or start_time is null or end_time > start_time)
);

create index idx_trip_days_plan_day
  on trip_days (trip_plan_id, day_no);

create table trip_schedule_versions (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  version_no integer not null check (version_no > 0),
  base_schedule_version_id uuid,
  status text not null check (status in ('draft', 'candidate', 'active', 'superseded', 'rejected')),
  source_type text not null check (source_type in (
    'initial', 'user_edit', 'ai_generation', 'recovery', 'live_recalculation'
  )),
  summary text,
  resulting_score integer check (resulting_score is null or resulting_score between 0 and 100),
  created_by_user_id uuid references user_profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  applied_at timestamptz,
  foreign key (base_schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id),
  unique (trip_plan_id, version_no),
  unique (id, trip_plan_id)
);

create index idx_trip_schedule_versions_plan_created
  on trip_schedule_versions (trip_plan_id, created_at desc);

create index idx_trip_schedule_versions_base
  on trip_schedule_versions (base_schedule_version_id, trip_plan_id);

create index idx_trip_schedule_versions_created_by
  on trip_schedule_versions (created_by_user_id);

create unique index uq_trip_schedule_versions_active
  on trip_schedule_versions (trip_plan_id)
  where status = 'active';

alter table trip_plans
  add constraint fk_trip_plans_active_schedule_version
  foreign key (active_schedule_version_id, id)
  references trip_schedule_versions (id, trip_plan_id)
  deferrable initially deferred;

create index idx_trip_plans_active_schedule
  on trip_plans (active_schedule_version_id, id);

create table trip_items (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  trip_day_id uuid not null,
  schedule_version_id uuid not null,
  sequence_no integer not null check (sequence_no > 0),
  item_type text not null check (item_type in (
    'place_visit', 'meal', 'accommodation', 'arrival', 'departure', 'free_time', 'custom'
  )),
  place_id uuid references tour_places(id),
  title text,
  planned_start_at timestamptz,
  planned_end_at timestamptz,
  stay_minutes integer check (stay_minutes is null or stay_minutes >= 0),
  buffer_after_minutes integer not null default 0 check (buffer_after_minutes >= 0),
  required boolean not null default false,
  source text not null check (source in ('user_input', 'ai_generated', 'recovery', 'system')),
  memo text,
  facts jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (trip_day_id, trip_plan_id)
    references trip_days (id, trip_plan_id) on delete cascade,
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  unique (schedule_version_id, trip_day_id, sequence_no),
  unique (id, schedule_version_id),
  unique (id, schedule_version_id, trip_plan_id),
  unique (id, schedule_version_id, trip_plan_id, trip_day_id),
  check (place_id is not null or title is not null),
  check (planned_end_at is null or planned_start_at is null or planned_end_at >= planned_start_at)
);

create index idx_trip_items_plan_version
  on trip_items (trip_plan_id, schedule_version_id);

create index idx_trip_items_day_sequence
  on trip_items (trip_day_id, schedule_version_id, sequence_no);

create index idx_trip_items_version_plan
  on trip_items (schedule_version_id, trip_plan_id);

create index idx_trip_items_day_plan
  on trip_items (trip_day_id, trip_plan_id);

create index idx_trip_items_place
  on trip_items (place_id);

create table itinerary_generation_runs (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  trip_day_id uuid not null,
  base_schedule_version_id uuid,
  input_mode text not null default 'structured' check (input_mode in ('structured', 'conversation')),
  status text not null check (status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  structured_input jsonb not null default '{}'::jsonb,
  prompt_text text,
  contract_version text not null,
  algorithm_version text not null,
  model text,
  idempotency_key text not null,
  requested_by_user_id uuid references user_profiles(id) on delete set null,
  started_at timestamptz,
  completed_at timestamptz,
  error_code text,
  error_message text,
  created_at timestamptz not null default now(),
  foreign key (trip_day_id, trip_plan_id)
    references trip_days (id, trip_plan_id) on delete cascade,
  foreign key (base_schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id),
  unique (id, trip_plan_id),
  unique (trip_plan_id, idempotency_key)
);

create index idx_generation_runs_plan_created
  on itinerary_generation_runs (trip_plan_id, created_at desc);

create index idx_generation_runs_day_status
  on itinerary_generation_runs (trip_day_id, status, created_at desc);

create index idx_generation_runs_base_version
  on itinerary_generation_runs (base_schedule_version_id, trip_plan_id);

create index idx_generation_runs_day_plan
  on itinerary_generation_runs (trip_day_id, trip_plan_id);

create index idx_generation_runs_requested_by
  on itinerary_generation_runs (requested_by_user_id);

create table itinerary_generation_candidates (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  generation_run_id uuid not null,
  schedule_version_id uuid not null,
  rank_no integer not null check (rank_no > 0),
  score integer check (score is null or score between 0 and 100),
  explanation text,
  selected_at timestamptz,
  created_at timestamptz not null default now(),
  foreign key (generation_run_id, trip_plan_id)
    references itinerary_generation_runs (id, trip_plan_id) on delete cascade,
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  unique (generation_run_id, rank_no),
  unique (generation_run_id, schedule_version_id)
);

create index idx_generation_candidates_version
  on itinerary_generation_candidates (schedule_version_id, trip_plan_id);

create index idx_generation_candidates_run_plan
  on itinerary_generation_candidates (generation_run_id, trip_plan_id);

create index idx_generation_candidates_plan
  on itinerary_generation_candidates (trip_plan_id, created_at desc);

create table ai_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references user_profiles(id) on delete cascade,
  trip_plan_id uuid references trip_plans(id) on delete cascade,
  status text not null default 'active' check (status in ('active', 'closed', 'archived')),
  locale text not null default 'ko-KR',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index idx_ai_conversations_user_created
  on ai_conversations (user_id, created_at desc);

create index idx_ai_conversations_trip
  on ai_conversations (trip_plan_id, created_at desc);

create table ai_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references ai_conversations(id) on delete cascade,
  role text not null check (role in ('user', 'assistant', 'system', 'tool')),
  content text not null,
  structured_payload jsonb not null default '{}'::jsonb,
  generation_run_id uuid references itinerary_generation_runs(id) on delete set null,
  created_at timestamptz not null default now()
);

create index idx_ai_messages_conversation_created
  on ai_messages (conversation_id, created_at);

create index idx_ai_messages_generation_run
  on ai_messages (generation_run_id);

create table trip_legs (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  trip_day_id uuid not null,
  schedule_version_id uuid not null,
  sequence_no integer not null check (sequence_no > 0),
  from_item_id uuid not null,
  to_item_id uuid not null,
  transport_mode text not null check (transport_mode in ('walk', 'public_transit', 'rental_car', 'taxi')),
  origin_stop_id uuid references bus_stops(id),
  destination_stop_id uuid references bus_stops(id),
  route_id uuid references bus_routes(id),
  mobility_route_snapshot_id uuid references mobility_route_snapshots(id) on delete set null,
  planned_departure_at timestamptz,
  planned_arrival_at timestamptz,
  walk_minutes integer not null default 0 check (walk_minutes >= 0),
  wait_minutes integer not null default 0 check (wait_minutes >= 0),
  ride_minutes integer not null default 0 check (ride_minutes >= 0),
  transfer_minutes integer not null default 0 check (transfer_minutes >= 0),
  duration_minutes integer check (duration_minutes is null or duration_minutes >= 0),
  buffer_minutes integer not null default 0 check (buffer_minutes >= 0),
  distance_meters integer check (distance_meters is null or distance_meters >= 0),
  estimated_fare integer check (estimated_fare is null or estimated_fare >= 0),
  risk_score integer check (risk_score is null or risk_score between 0 and 100),
  facts jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (trip_day_id, trip_plan_id)
    references trip_days (id, trip_plan_id) on delete cascade,
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (from_item_id, schedule_version_id, trip_plan_id, trip_day_id)
    references trip_items (id, schedule_version_id, trip_plan_id, trip_day_id) on delete cascade,
  foreign key (to_item_id, schedule_version_id, trip_plan_id, trip_day_id)
    references trip_items (id, schedule_version_id, trip_plan_id, trip_day_id) on delete cascade,
  unique (schedule_version_id, trip_day_id, sequence_no),
  unique (schedule_version_id, from_item_id, to_item_id),
  unique (id, schedule_version_id, trip_plan_id),
  check (from_item_id <> to_item_id),
  check (planned_arrival_at is null or planned_departure_at is null or planned_arrival_at >= planned_departure_at)
);

create index idx_trip_legs_plan_version
  on trip_legs (trip_plan_id, schedule_version_id);

create index idx_trip_legs_day_sequence
  on trip_legs (trip_day_id, schedule_version_id, sequence_no);

create index idx_trip_legs_version_plan
  on trip_legs (schedule_version_id, trip_plan_id);

create index idx_trip_legs_day_plan
  on trip_legs (trip_day_id, trip_plan_id);

create index idx_trip_legs_from_version_plan_day
  on trip_legs (from_item_id, schedule_version_id, trip_plan_id, trip_day_id);

create index idx_trip_legs_to_version_plan_day
  on trip_legs (to_item_id, schedule_version_id, trip_plan_id, trip_day_id);

create index idx_trip_legs_from_item
  on trip_legs (from_item_id);

create index idx_trip_legs_to_item
  on trip_legs (to_item_id);

create index idx_trip_legs_origin_stop
  on trip_legs (origin_stop_id);

create index idx_trip_legs_destination_stop
  on trip_legs (destination_stop_id);

create index idx_trip_legs_route
  on trip_legs (route_id);

create index idx_trip_legs_mobility_snapshot
  on trip_legs (mobility_route_snapshot_id);

create table trip_item_progress (
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  schedule_version_id uuid not null,
  trip_item_id uuid not null,
  status text not null default 'planned' check (status in (
    'planned', 'active', 'arrived', 'completed', 'skipped', 'missed'
  )),
  actual_started_at timestamptz,
  actual_arrived_at timestamptz,
  actual_completed_at timestamptz,
  updated_at timestamptz not null default now(),
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (trip_item_id, schedule_version_id, trip_plan_id)
    references trip_items (id, schedule_version_id, trip_plan_id) on delete cascade,
  primary key (trip_plan_id, schedule_version_id, trip_item_id),
  check (actual_arrived_at is null or actual_started_at is null or actual_arrived_at >= actual_started_at),
  check (actual_completed_at is null or actual_arrived_at is null or actual_completed_at >= actual_arrived_at)
);

create index idx_trip_item_progress_version_status
  on trip_item_progress (schedule_version_id, trip_plan_id, status);

create index idx_trip_item_progress_item_version_plan
  on trip_item_progress (trip_item_id, schedule_version_id, trip_plan_id);

create table trip_execution_events (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  schedule_version_id uuid not null,
  trip_item_id uuid,
  trip_leg_id uuid,
  event_type text not null check (event_type in (
    'trip_started', 'departed', 'arrived', 'completed', 'skipped', 'missed',
    'recalculated', 'recovery_applied', 'trip_completed'
  )),
  client_event_id text not null,
  location geography(Point, 4326),
  occurred_at timestamptz not null,
  recorded_at timestamptz not null default now(),
  metadata jsonb not null default '{}'::jsonb,
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (trip_item_id, schedule_version_id, trip_plan_id)
    references trip_items (id, schedule_version_id, trip_plan_id),
  foreign key (trip_leg_id, schedule_version_id, trip_plan_id)
    references trip_legs (id, schedule_version_id, trip_plan_id),
  unique (trip_plan_id, client_event_id)
);

create index idx_trip_execution_events_plan_occurred
  on trip_execution_events (trip_plan_id, occurred_at desc);

create index idx_trip_execution_events_version_plan
  on trip_execution_events (schedule_version_id, trip_plan_id, occurred_at desc);

create index idx_trip_execution_events_item_version_plan
  on trip_execution_events (trip_item_id, schedule_version_id, trip_plan_id);

create index idx_trip_execution_events_leg_version_plan
  on trip_execution_events (trip_leg_id, schedule_version_id, trip_plan_id);

create index idx_trip_execution_events_location
  on trip_execution_events using gist (location);

create table compute_runs (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  trip_day_id uuid,
  schedule_version_id uuid not null,
  run_type text not null check (run_type in (
    'itinerary_validate', 'feasibility', 'spare_time', 'recovery', 'live_recalculate'
  )),
  status text not null check (status in ('queued', 'running', 'succeeded', 'failed', 'fallback')),
  input_hash text not null,
  contract_version text not null,
  algorithm_version text not null,
  facts_snapshot_at timestamptz not null,
  source_data_version text not null,
  result_summary jsonb not null default '{}'::jsonb,
  started_at timestamptz,
  completed_at timestamptz,
  error_code text,
  error_message text,
  created_at timestamptz not null default now(),
  foreign key (trip_day_id, trip_plan_id)
    references trip_days (id, trip_plan_id) on delete cascade,
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  unique (id, trip_plan_id, schedule_version_id),
  unique (schedule_version_id, run_type, input_hash, algorithm_version)
);

create index idx_compute_runs_plan_created
  on compute_runs (trip_plan_id, created_at desc);

create index idx_compute_runs_day
  on compute_runs (trip_day_id, trip_plan_id, run_type, created_at desc);

create index idx_compute_runs_version
  on compute_runs (schedule_version_id, trip_plan_id, run_type, created_at desc);

create table risk_events (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  schedule_version_id uuid not null,
  compute_run_id uuid not null,
  trip_item_id uuid,
  trip_leg_id uuid,
  event_type text not null check (event_type in (
    'missed_bus', 'tight_transfer', 'long_walk', 'low_frequency', 'stale_data',
    'impossible_segment', 'late_departure', 'closed_place', 'accommodation_conflict',
    'transport_event_miss', 'rain_risk', 'high_wind', 'heat_risk', 'weather_warning'
  )),
  severity text not null check (severity in ('info', 'green', 'yellow', 'red')),
  score_delta integer not null default 0,
  wait_risk_minutes integer,
  reason_code text not null,
  computed_facts jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (compute_run_id, trip_plan_id, schedule_version_id)
    references compute_runs (id, trip_plan_id, schedule_version_id) on delete cascade,
  foreign key (trip_item_id, schedule_version_id, trip_plan_id)
    references trip_items (id, schedule_version_id, trip_plan_id) on delete cascade,
  foreign key (trip_leg_id, schedule_version_id, trip_plan_id)
    references trip_legs (id, schedule_version_id, trip_plan_id) on delete cascade,
  unique (id, trip_plan_id, schedule_version_id),
  check (trip_item_id is not null or trip_leg_id is not null)
);

create index idx_risk_events_plan
  on risk_events (trip_plan_id, severity);

create index idx_risk_events_version
  on risk_events (schedule_version_id, trip_plan_id, severity);

create index idx_risk_events_compute
  on risk_events (compute_run_id, trip_plan_id, schedule_version_id);

create index idx_risk_events_item
  on risk_events (trip_item_id, schedule_version_id, trip_plan_id);

create index idx_risk_events_leg
  on risk_events (trip_leg_id, schedule_version_id, trip_plan_id);

create table trip_weather_impacts (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  trip_day_id uuid references trip_days(id) on delete cascade,
  schedule_version_id uuid not null,
  compute_run_id uuid not null,
  trip_item_id uuid,
  trip_leg_id uuid,
  weather_forecast_id uuid references weather_forecasts(id) on delete set null,
  weather_observation_id uuid references weather_observations(id) on delete set null,
  impact_type text not null check (impact_type in ('rain', 'wind', 'heat', 'cold', 'visibility', 'umbrella_needed')),
  severity text not null check (severity in ('info', 'green', 'yellow', 'red')),
  score_delta integer not null default 0,
  recommendation_text text,
  computed_facts jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (compute_run_id, trip_plan_id, schedule_version_id)
    references compute_runs (id, trip_plan_id, schedule_version_id) on delete cascade,
  foreign key (trip_item_id, schedule_version_id, trip_plan_id)
    references trip_items (id, schedule_version_id, trip_plan_id) on delete cascade,
  foreign key (trip_leg_id, schedule_version_id, trip_plan_id)
    references trip_legs (id, schedule_version_id, trip_plan_id) on delete cascade,
  check (trip_item_id is not null or trip_leg_id is not null)
);

create index idx_trip_weather_impacts_plan
  on trip_weather_impacts (trip_plan_id, severity);

create index idx_trip_weather_impacts_day
  on trip_weather_impacts (trip_day_id, severity);

create index idx_trip_weather_impacts_version
  on trip_weather_impacts (schedule_version_id, trip_plan_id, severity);

create index idx_trip_weather_impacts_compute
  on trip_weather_impacts (compute_run_id, trip_plan_id, schedule_version_id);

create index idx_trip_weather_impacts_item
  on trip_weather_impacts (trip_item_id, schedule_version_id, trip_plan_id);

create index idx_trip_weather_impacts_leg
  on trip_weather_impacts (trip_leg_id, schedule_version_id, trip_plan_id);

create index idx_trip_weather_impacts_forecast
  on trip_weather_impacts (weather_forecast_id);

create index idx_trip_weather_impacts_observation
  on trip_weather_impacts (weather_observation_id);

create table recommendation_candidates (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  trip_day_id uuid references trip_days(id) on delete cascade,
  schedule_version_id uuid not null,
  compute_run_id uuid not null,
  base_item_id uuid,
  candidate_place_id uuid not null references tour_places(id),
  recommendation_type text not null check (recommendation_type in ('spare_time', 'nearby', 'replacement')),
  available_gap_minutes integer check (available_gap_minutes is null or available_gap_minutes >= 0),
  required_total_minutes integer check (required_total_minutes is null or required_total_minutes >= 0),
  travel_minutes integer check (travel_minutes is null or travel_minutes >= 0),
  stay_minutes integer check (stay_minutes is null or stay_minutes >= 0),
  safety_buffer_minutes integer not null default 0 check (safety_buffer_minutes >= 0),
  score integer check (score is null or score between 0 and 100),
  reason_code text,
  facts jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (compute_run_id, trip_plan_id, schedule_version_id)
    references compute_runs (id, trip_plan_id, schedule_version_id) on delete cascade,
  foreign key (base_item_id, schedule_version_id, trip_plan_id)
    references trip_items (id, schedule_version_id, trip_plan_id)
);

create index idx_recommendation_candidates_plan
  on recommendation_candidates (trip_plan_id, recommendation_type, score desc);

create index idx_recommendation_candidates_day
  on recommendation_candidates (trip_day_id, score desc);

create index idx_recommendation_candidates_version
  on recommendation_candidates (schedule_version_id, trip_plan_id, score desc);

create index idx_recommendation_candidates_compute
  on recommendation_candidates (compute_run_id, trip_plan_id, schedule_version_id);

create index idx_recommendation_candidates_base_item
  on recommendation_candidates (base_item_id, schedule_version_id, trip_plan_id);

create index idx_recommendation_candidates_place
  on recommendation_candidates (candidate_place_id);

create table recovery_options (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  compute_run_id uuid not null,
  trigger_risk_event_id uuid,
  base_schedule_version_id uuid not null,
  proposed_schedule_version_id uuid not null,
  option_type text not null check (option_type in (
    'take_next_bus', 'move_to_another_day', 'replace_place', 'shorten_stay',
    'skip_optional', 'change_transport'
  )),
  status text not null default 'proposed' check (status in ('proposed', 'applied', 'dismissed', 'expired')),
  title text not null,
  explanation text,
  impact_minutes integer not null default 0,
  resulting_score integer check (resulting_score is null or resulting_score between 0 and 100),
  change_summary jsonb not null default '{}'::jsonb,
  expires_at timestamptz,
  selected_at timestamptz,
  applied_at timestamptz,
  created_at timestamptz not null default now(),
  foreign key (base_schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (proposed_schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (compute_run_id, trip_plan_id, base_schedule_version_id)
    references compute_runs (id, trip_plan_id, schedule_version_id) on delete cascade,
  foreign key (trigger_risk_event_id, trip_plan_id, base_schedule_version_id)
    references risk_events (id, trip_plan_id, schedule_version_id),
  unique (id, base_schedule_version_id, proposed_schedule_version_id),
  check (base_schedule_version_id <> proposed_schedule_version_id)
);

create index idx_recovery_options_plan
  on recovery_options (trip_plan_id, status, created_at desc);

create index idx_recovery_options_compute
  on recovery_options (compute_run_id, trip_plan_id, base_schedule_version_id);

create index idx_recovery_options_risk
  on recovery_options (trigger_risk_event_id, trip_plan_id, base_schedule_version_id);

create index idx_recovery_options_base_version
  on recovery_options (base_schedule_version_id, trip_plan_id);

create index idx_recovery_options_proposed_version
  on recovery_options (proposed_schedule_version_id, trip_plan_id);

create table recovery_option_changes (
  id uuid primary key default gen_random_uuid(),
  recovery_option_id uuid not null,
  base_schedule_version_id uuid not null,
  proposed_schedule_version_id uuid not null,
  change_order integer not null check (change_order > 0),
  action text not null check (action in (
    'add', 'remove', 'move_day', 'update_time', 'shorten_stay', 'change_transport'
  )),
  source_item_id uuid,
  proposed_item_id uuid,
  before_value jsonb not null default '{}'::jsonb,
  after_value jsonb not null default '{}'::jsonb,
  reason_code text not null,
  created_at timestamptz not null default now(),
  foreign key (recovery_option_id, base_schedule_version_id, proposed_schedule_version_id)
    references recovery_options (id, base_schedule_version_id, proposed_schedule_version_id) on delete cascade,
  foreign key (source_item_id, base_schedule_version_id)
    references trip_items (id, schedule_version_id),
  foreign key (proposed_item_id, proposed_schedule_version_id)
    references trip_items (id, schedule_version_id),
  unique (recovery_option_id, change_order)
);

create index idx_recovery_option_changes_source_item
  on recovery_option_changes (source_item_id, base_schedule_version_id);

create index idx_recovery_option_changes_proposed_item
  on recovery_option_changes (proposed_item_id, proposed_schedule_version_id);

create index idx_recovery_option_changes_option_versions
  on recovery_option_changes (recovery_option_id, base_schedule_version_id, proposed_schedule_version_id);

create table live_state_snapshots (
  id uuid primary key default gen_random_uuid(),
  trip_plan_id uuid not null references trip_plans(id) on delete cascade,
  schedule_version_id uuid not null,
  active_item_id uuid,
  active_leg_id uuid,
  compute_run_id uuid,
  status text not null check (status in ('green', 'yellow', 'red')),
  current_location geography(Point, 4326),
  current_place_id uuid references tour_places(id),
  next_action text,
  observed_at timestamptz not null default now(),
  facts jsonb not null default '{}'::jsonb,
  foreign key (schedule_version_id, trip_plan_id)
    references trip_schedule_versions (id, trip_plan_id) on delete cascade,
  foreign key (active_item_id, schedule_version_id, trip_plan_id)
    references trip_items (id, schedule_version_id, trip_plan_id),
  foreign key (active_leg_id, schedule_version_id, trip_plan_id)
    references trip_legs (id, schedule_version_id, trip_plan_id),
  foreign key (compute_run_id, trip_plan_id, schedule_version_id)
    references compute_runs (id, trip_plan_id, schedule_version_id)
);

create index idx_live_state_snapshots_plan_observed
  on live_state_snapshots (trip_plan_id, observed_at desc);

create index idx_live_state_snapshots_version
  on live_state_snapshots (schedule_version_id, trip_plan_id, observed_at desc);

create index idx_live_state_snapshots_item
  on live_state_snapshots (active_item_id, schedule_version_id, trip_plan_id);

create index idx_live_state_snapshots_leg
  on live_state_snapshots (active_leg_id, schedule_version_id, trip_plan_id);

create index idx_live_state_snapshots_compute
  on live_state_snapshots (compute_run_id, trip_plan_id, schedule_version_id);

create index idx_live_state_snapshots_place
  on live_state_snapshots (current_place_id);

create table mcp_compute_call_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references user_profiles(id) on delete set null,
  trip_plan_id uuid references trip_plans(id) on delete cascade,
  compute_run_id uuid references compute_runs(id) on delete cascade,
  generation_run_id uuid references itinerary_generation_runs(id) on delete cascade,
  request_id text not null,
  tool_name text not null check (tool_name in (
    'parse_trip_intent', 'generate_day_itinerary', 'revise_day_itinerary',
    'validate_itinerary', 'calculate_feasibility', 'recommend_spare_time',
    'generate_recovery_options', 'recalculate_live_state', 'explain_result'
  )),
  status text not null check (status in ('running', 'succeeded', 'failed', 'fallback')),
  contract_version text not null,
  provider text,
  model text,
  request_payload_redacted jsonb not null default '{}'::jsonb,
  response_payload_redacted jsonb not null default '{}'::jsonb,
  latency_ms integer check (latency_ms is null or latency_ms >= 0),
  error_code text,
  error_message text,
  created_at timestamptz not null default now(),
  check (compute_run_id is not null or generation_run_id is not null)
);

create unique index uq_mcp_compute_call_logs_request
  on mcp_compute_call_logs (request_id, tool_name);

create index idx_mcp_compute_call_logs_user_created
  on mcp_compute_call_logs (user_id, created_at desc);

create index idx_mcp_compute_call_logs_trip_tool
  on mcp_compute_call_logs (trip_plan_id, tool_name, created_at desc);

create index idx_mcp_compute_call_logs_compute
  on mcp_compute_call_logs (compute_run_id);

create index idx_mcp_compute_call_logs_generation
  on mcp_compute_call_logs (generation_run_id);

create function public.validate_trip_calendar_child()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  plan_start_date date;
  plan_end_date date;
  event_local_date date;
begin
  select p.start_date, p.end_date
    into plan_start_date, plan_end_date
  from public.trip_plans p
  where p.id = new.trip_plan_id;

  if not found then
    raise exception 'trip plan % does not exist', new.trip_plan_id;
  end if;

  if tg_table_name = 'trip_days' then
    if new.trip_date <> plan_start_date + (new.day_no - 1)
       or new.trip_date > plan_end_date then
      raise exception 'trip day % date % is inconsistent with trip range %..%',
        new.day_no, new.trip_date, plan_start_date, plan_end_date;
    end if;
  elsif tg_table_name = 'trip_transport_events' then
    event_local_date := timezone('Asia/Seoul', new.scheduled_at)::date;
    if event_local_date < plan_start_date or event_local_date > plan_end_date then
      raise exception 'transport event date % is outside trip range %..%',
        event_local_date, plan_start_date, plan_end_date;
    end if;
  elsif tg_table_name = 'trip_accommodations' then
    if new.check_in_date < plan_start_date
       or new.check_out_date > plan_end_date
       or new.check_out_date <= new.check_in_date then
      raise exception 'accommodation range %..% is outside trip range %..%',
        new.check_in_date, new.check_out_date, plan_start_date, plan_end_date;
    end if;
  end if;

  return new;
end;
$$;

create trigger trg_trip_days_calendar
before insert or update on trip_days
for each row execute function public.validate_trip_calendar_child();

create trigger trg_trip_transport_events_calendar
before insert or update on trip_transport_events
for each row execute function public.validate_trip_calendar_child();

create trigger trg_trip_accommodations_calendar
before insert or update on trip_accommodations
for each row execute function public.validate_trip_calendar_child();

create function public.protect_trip_date_range()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.start_date is not distinct from old.start_date
     and new.end_date is not distinct from old.end_date then
    return new;
  end if;

  if exists (select 1 from public.trip_days d where d.trip_plan_id = old.id)
     or exists (select 1 from public.trip_transport_events e where e.trip_plan_id = old.id)
     or exists (select 1 from public.trip_accommodations a where a.trip_plan_id = old.id) then
    raise exception 'trip date range cannot change after calendar children are created';
  end if;

  return new;
end;
$$;

create trigger trg_trip_plans_protect_date_range
before update on trip_plans
for each row execute function public.protect_trip_date_range();

create function public.validate_schedule_timeline()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  target_date date;
  target_start_time time;
  target_end_time time;
  starts_at timestamptz;
  ends_at timestamptz;
  local_start_date date;
  local_end_date date;
  local_start_time time;
  local_end_time time;
begin
  select d.trip_date, d.start_time, d.end_time
    into target_date, target_start_time, target_end_time
  from public.trip_days d
  where d.id = new.trip_day_id
    and d.trip_plan_id = new.trip_plan_id;

  if not found then
    raise exception 'trip day % does not belong to trip %', new.trip_day_id, new.trip_plan_id;
  end if;

  if tg_table_name = 'trip_items' then
    starts_at := new.planned_start_at;
    ends_at := new.planned_end_at;
  else
    starts_at := new.planned_departure_at;
    ends_at := new.planned_arrival_at;
  end if;

  if starts_at is not null then
    local_start_date := timezone('Asia/Seoul', starts_at)::date;
    local_start_time := timezone('Asia/Seoul', starts_at)::time;
    if local_start_date <> target_date
       or (target_start_time is not null and local_start_time < target_start_time)
       or (target_end_time is not null and local_start_time > target_end_time) then
      raise exception 'schedule start % is outside trip day %', starts_at, target_date;
    end if;
  end if;

  if ends_at is not null then
    local_end_date := timezone('Asia/Seoul', ends_at)::date;
    local_end_time := timezone('Asia/Seoul', ends_at)::time;
    if local_end_date <> target_date
       or (target_start_time is not null and local_end_time < target_start_time)
       or (target_end_time is not null and local_end_time > target_end_time) then
      raise exception 'schedule end % is outside trip day %', ends_at, target_date;
    end if;
  end if;

  return new;
end;
$$;

create trigger trg_trip_items_timeline
before insert or update on trip_items
for each row execute function public.validate_schedule_timeline();

create trigger trg_trip_legs_timeline
before insert or update on trip_legs
for each row execute function public.validate_schedule_timeline();

create function public.validate_trip_item_progress_mutation()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.trip_plan_id <> old.trip_plan_id
     or new.schedule_version_id <> old.schedule_version_id
     or new.trip_item_id <> old.trip_item_id then
    raise exception 'trip item progress identity is immutable';
  end if;

  if new.status <> old.status and not (
    (old.status = 'planned' and new.status in ('active', 'arrived', 'skipped', 'missed'))
    or (old.status = 'active' and new.status in ('arrived', 'completed', 'skipped', 'missed'))
    or (old.status = 'arrived' and new.status in ('completed', 'skipped', 'missed'))
  ) then
    raise exception 'invalid trip item progress transition: % -> %', old.status, new.status;
  end if;

  return new;
end;
$$;

create trigger trg_trip_item_progress_transition
before update on trip_item_progress
for each row execute function public.validate_trip_item_progress_mutation();

create function public.prevent_execution_event_mutation()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
    return old;
  end if;

  raise exception 'trip execution events are append-only';
end;
$$;

create trigger trg_trip_execution_events_append_only
before update or delete on trip_execution_events
for each row execute function public.prevent_execution_event_mutation();

create function public.assert_schedule_version_sealable(
  target_schedule_version_id uuid,
  target_trip_plan_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
  item_count integer;
  expected_leg_count integer;
  actual_leg_count integer;
begin
  if not exists (
    select 1
    from public.trip_schedule_versions v
    where v.id = target_schedule_version_id
      and v.trip_plan_id = target_trip_plan_id
  ) then
    raise exception 'schedule version % does not belong to trip %',
      target_schedule_version_id, target_trip_plan_id;
  end if;

  select count(*)
    into item_count
  from public.trip_items i
  where i.schedule_version_id = target_schedule_version_id
    and i.trip_plan_id = target_trip_plan_id;

  if item_count = 0 then
    raise exception 'schedule version % cannot be sealed without items', target_schedule_version_id;
  end if;

  if exists (
    select 1
    from public.trip_items i
    where i.schedule_version_id = target_schedule_version_id
      and i.trip_plan_id = target_trip_plan_id
      and (
        i.planned_start_at is null
        or i.planned_end_at is null
        or i.planned_end_at <= i.planned_start_at
        or i.stay_minutes is null
        or i.stay_minutes <= 0
        or i.stay_minutes <> floor(extract(epoch from (i.planned_end_at - i.planned_start_at)) / 60)::integer
      )
  ) then
    raise exception 'sealed schedule items require consistent start, end, and stay minutes';
  end if;

  if exists (
    select 1
    from public.trip_items i
    where i.schedule_version_id = target_schedule_version_id
      and i.trip_plan_id = target_trip_plan_id
      and i.place_id is null
      and not coalesce(
        jsonb_typeof(i.facts -> 'location') = 'object'
        and jsonb_typeof(i.facts #> '{location,lat}') = 'number'
        and jsonb_typeof(i.facts #> '{location,lng}') = 'number',
        false
      )
  ) then
    raise exception 'sealed schedule items require a place or explicit location facts';
  end if;

  if exists (
    select 1
    from (
      select
        i.sequence_no,
        row_number() over (
          partition by i.trip_day_id
          order by i.sequence_no
        ) as expected_sequence_no
      from public.trip_items i
      where i.schedule_version_id = target_schedule_version_id
        and i.trip_plan_id = target_trip_plan_id
    ) ordered_items
    where ordered_items.sequence_no <> ordered_items.expected_sequence_no
  ) then
    raise exception 'sealed schedule item sequence numbers must be contiguous per day';
  end if;

  if exists (
    select 1
    from public.trip_items left_item
    join public.trip_items right_item
      on right_item.schedule_version_id = left_item.schedule_version_id
     and right_item.trip_plan_id = left_item.trip_plan_id
     and right_item.trip_day_id = left_item.trip_day_id
     and right_item.id > left_item.id
    where left_item.schedule_version_id = target_schedule_version_id
      and left_item.trip_plan_id = target_trip_plan_id
      and tstzrange(left_item.planned_start_at, left_item.planned_end_at, '[)')
          && tstzrange(right_item.planned_start_at, right_item.planned_end_at, '[)')
  ) then
    raise exception 'sealed schedule items cannot overlap within a day';
  end if;

  select coalesce(sum(greatest(day_item_count - 1, 0)), 0)::integer
    into expected_leg_count
  from (
    select count(*)::integer as day_item_count
    from public.trip_items i
    where i.schedule_version_id = target_schedule_version_id
      and i.trip_plan_id = target_trip_plan_id
    group by i.trip_day_id
  ) day_counts;

  select count(*)
    into actual_leg_count
  from public.trip_legs l
  where l.schedule_version_id = target_schedule_version_id
    and l.trip_plan_id = target_trip_plan_id;

  if actual_leg_count <> expected_leg_count then
    raise exception 'sealed schedule version % requires % consecutive legs but has %',
      target_schedule_version_id, expected_leg_count, actual_leg_count;
  end if;

  if exists (
    with ordered_items as (
      select
        i.trip_day_id,
        i.id as from_item_id,
        i.sequence_no,
        lead(i.id) over (
          partition by i.trip_day_id
          order by i.sequence_no
        ) as to_item_id
      from public.trip_items i
      where i.schedule_version_id = target_schedule_version_id
        and i.trip_plan_id = target_trip_plan_id
    )
    select 1
    from ordered_items o
    where o.to_item_id is not null
      and not exists (
        select 1
        from public.trip_legs l
        where l.schedule_version_id = target_schedule_version_id
          and l.trip_plan_id = target_trip_plan_id
          and l.trip_day_id = o.trip_day_id
          and l.sequence_no = o.sequence_no
          and l.from_item_id = o.from_item_id
          and l.to_item_id = o.to_item_id
      )
  ) then
    raise exception 'sealed schedule legs must connect every consecutive item';
  end if;

  if exists (
    select 1
    from public.trip_legs l
    join public.trip_items from_item
      on from_item.id = l.from_item_id
     and from_item.schedule_version_id = l.schedule_version_id
    join public.trip_items to_item
      on to_item.id = l.to_item_id
     and to_item.schedule_version_id = l.schedule_version_id
    where l.schedule_version_id = target_schedule_version_id
      and l.trip_plan_id = target_trip_plan_id
      and (
        l.planned_departure_at is null
        or l.planned_arrival_at is null
        or l.duration_minutes is null
        or l.planned_arrival_at <= l.planned_departure_at
        or l.duration_minutes <= 0
        or l.duration_minutes <> floor(
          extract(epoch from (l.planned_arrival_at - l.planned_departure_at)) / 60
        )::integer
        or l.duration_minutes <>
          l.walk_minutes + l.wait_minutes + l.ride_minutes + l.transfer_minutes
        or l.planned_departure_at < from_item.planned_end_at
        or l.planned_arrival_at > to_item.planned_start_at
      )
  ) then
    raise exception 'sealed schedule legs require consistent component durations within adjacent item windows';
  end if;
end;
$$;

create function public.validate_schedule_version_sealing()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status in ('candidate', 'active')
     and (tg_op = 'INSERT' or old.status is distinct from new.status) then
    perform public.assert_schedule_version_sealable(new.id, new.trip_plan_id);
  end if;

  return new;
end;
$$;

create trigger trg_trip_schedule_versions_sealing
before insert or update on trip_schedule_versions
for each row execute function public.validate_schedule_version_sealing();

create function public.validate_schedule_version_mutation()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    if pg_trigger_depth() > 1 then
      return old;
    end if;

    if old.status not in ('draft', 'rejected') then
      raise exception 'schedule version % with status % is immutable', old.id, old.status;
    end if;

    return old;
  end if;

  if new.id <> old.id or new.trip_plan_id <> old.trip_plan_id then
    raise exception 'schedule version identity and trip ownership are immutable';
  end if;

  if old.status <> 'draft' and (
    new.version_no is distinct from old.version_no
    or new.base_schedule_version_id is distinct from old.base_schedule_version_id
    or new.source_type is distinct from old.source_type
    or new.summary is distinct from old.summary
    or new.resulting_score is distinct from old.resulting_score
    or new.created_by_user_id is distinct from old.created_by_user_id
    or new.created_at is distinct from old.created_at
  ) then
    raise exception 'non-draft schedule version % content is immutable', old.id;
  end if;

  if new.status <> old.status and not (
    (old.status = 'draft' and new.status in ('candidate', 'active', 'rejected'))
    or (old.status = 'candidate' and new.status in ('active', 'rejected'))
    or (old.status = 'active' and new.status = 'superseded')
  ) then
    raise exception 'invalid schedule version status transition: % -> %', old.status, new.status;
  end if;

  return new;
end;
$$;

create trigger trg_trip_schedule_versions_mutation
before update or delete on trip_schedule_versions
for each row execute function public.validate_schedule_version_mutation();

create function public.require_draft_schedule_version()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  target_schedule_version_id uuid;
  target_status text;
begin
  if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
    return old;
  end if;

  if tg_op = 'DELETE' then
    target_schedule_version_id := old.schedule_version_id;
  else
    target_schedule_version_id := new.schedule_version_id;
  end if;

  select v.status
    into target_status
  from public.trip_schedule_versions v
  where v.id = target_schedule_version_id;

  if not found then
    if tg_op = 'DELETE' then
      return old;
    end if;
    raise exception 'schedule version % does not exist', target_schedule_version_id;
  end if;

  if target_status <> 'draft' then
    raise exception 'schedule content can only change while version % is draft', target_schedule_version_id;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;

  return new;
end;
$$;

create trigger trg_trip_items_require_draft_version
before insert or update or delete on trip_items
for each row execute function public.require_draft_schedule_version();

create trigger trg_trip_legs_require_draft_version
before insert or update or delete on trip_legs
for each row execute function public.require_draft_schedule_version();

create function public.enforce_active_schedule_version_consistency()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  target_trip_plan_id uuid;
  current_active_version_id uuid;
  current_trip_status text;
  active_version_count integer;
  pointer_is_active boolean;
begin
  if tg_table_name = 'trip_plans' then
    target_trip_plan_id := new.id;
  elsif tg_op = 'DELETE' then
    target_trip_plan_id := old.trip_plan_id;
  else
    if tg_op = 'UPDATE' and new.trip_plan_id <> old.trip_plan_id then
      raise exception 'trip_schedule_versions.trip_plan_id is immutable';
    end if;
    target_trip_plan_id := new.trip_plan_id;
  end if;

  select p.active_schedule_version_id, p.status
    into current_active_version_id, current_trip_status
  from public.trip_plans p
  where p.id = target_trip_plan_id;

  if not found then
    return null;
  end if;

  select count(*), coalesce(bool_or(v.id = current_active_version_id), false)
    into active_version_count, pointer_is_active
  from public.trip_schedule_versions v
  where v.trip_plan_id = target_trip_plan_id
    and v.status = 'active';

  if current_active_version_id is null and active_version_count <> 0 then
    raise exception 'trip % has an active version but no active_schedule_version_id', target_trip_plan_id;
  end if;

  if current_active_version_id is not null
     and (active_version_count <> 1 or not pointer_is_active) then
    raise exception 'trip % active schedule pointer and version status are inconsistent', target_trip_plan_id;
  end if;

  if current_trip_status in ('planned', 'live', 'completed')
     and current_active_version_id is null then
    raise exception 'trip % with status % requires an active schedule version',
      target_trip_plan_id, current_trip_status;
  end if;

  return null;
end;
$$;

create constraint trigger trg_trip_plans_active_schedule_consistency
after insert or update on trip_plans
deferrable initially deferred
for each row execute function public.enforce_active_schedule_version_consistency();

create constraint trigger trg_trip_schedule_versions_active_consistency
after insert or update or delete on trip_schedule_versions
deferrable initially deferred
for each row execute function public.enforce_active_schedule_version_consistency();

do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'data_import_runs', 'app_sessions', 'user_profiles', 'social_accounts',
    'legal_documents', 'user_consents', 'tour_places', 'place_details',
    'place_operating_hours', 'place_aliases', 'place_images', 'saved_places',
    'bus_stops', 'place_stop_links', 'weather_grid_points', 'weather_observations',
    'weather_forecasts', 'bus_routes', 'route_stops', 'timetable_entries',
    'bus_arrival_snapshots', 'mobility_route_snapshots', 'trip_plans',
    'trip_preferences', 'trip_transport_modes', 'trip_place_preferences',
    'trip_transport_events', 'trip_accommodations', 'trip_days',
    'trip_schedule_versions', 'trip_items', 'itinerary_generation_runs',
    'itinerary_generation_candidates', 'ai_conversations', 'ai_messages',
    'trip_legs', 'trip_item_progress', 'trip_execution_events',
    'compute_runs', 'risk_events', 'trip_weather_impacts',
    'recommendation_candidates', 'recovery_options', 'recovery_option_changes',
    'live_state_snapshots', 'mcp_compute_call_logs'
  ]
  loop
    execute format('alter table public.%I enable row level security', table_name);
  end loop;
end $$;

create function public.owns_trip_plan(target_trip_plan_id uuid)
returns boolean
language sql
stable
security invoker
set search_path = ''
as $$
  select exists (
    select 1
    from public.trip_plans p
    where p.id = target_trip_plan_id
      and p.user_id = (select auth.uid())
  );
$$;

create policy user_profiles_owner_select
  on user_profiles for select to authenticated
  using (id = (select auth.uid()));

create policy user_consents_owner_select
  on user_consents for select to authenticated
  using (user_id = (select auth.uid()));

create policy saved_places_owner_select
  on saved_places for select to authenticated
  using (user_id = (select auth.uid()));

create policy trip_plans_owner_select
  on trip_plans for select to authenticated
  using (user_id = (select auth.uid()));

create policy trip_preferences_owner_select
  on trip_preferences for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_transport_modes_owner_select
  on trip_transport_modes for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_place_preferences_owner_select
  on trip_place_preferences for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_transport_events_owner_select
  on trip_transport_events for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_accommodations_owner_select
  on trip_accommodations for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_days_owner_select
  on trip_days for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_schedule_versions_owner_select
  on trip_schedule_versions for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_items_owner_select
  on trip_items for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy itinerary_generation_runs_owner_select
  on itinerary_generation_runs for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy itinerary_generation_candidates_owner_select
  on itinerary_generation_candidates for select to authenticated
  using (
    exists (
      select 1
      from itinerary_generation_runs r
      where r.id = itinerary_generation_candidates.generation_run_id
        and (select public.owns_trip_plan(r.trip_plan_id))
    )
  );

create policy ai_conversations_owner_select
  on ai_conversations for select to authenticated
  using (user_id = (select auth.uid()));

create policy ai_messages_owner_select
  on ai_messages for select to authenticated
  using (
    exists (
      select 1
      from ai_conversations c
      where c.id = ai_messages.conversation_id
        and c.user_id = (select auth.uid())
    )
  );

create policy trip_legs_owner_select
  on trip_legs for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_item_progress_owner_select
  on trip_item_progress for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_execution_events_owner_select
  on trip_execution_events for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy compute_runs_owner_select
  on compute_runs for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy risk_events_owner_select
  on risk_events for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy trip_weather_impacts_owner_select
  on trip_weather_impacts for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy recommendation_candidates_owner_select
  on recommendation_candidates for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy recovery_options_owner_select
  on recovery_options for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

create policy recovery_option_changes_owner_select
  on recovery_option_changes for select to authenticated
  using (
    exists (
      select 1
      from recovery_options ro
      where ro.id = recovery_option_changes.recovery_option_id
        and (select public.owns_trip_plan(ro.trip_plan_id))
    )
  );

create policy live_state_snapshots_owner_select
  on live_state_snapshots for select to authenticated
  using ((select public.owns_trip_plan(trip_plan_id)));

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on all tables in schema public from anon';
    execute 'revoke all on all sequences in schema public from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on all tables in schema public from authenticated';
    execute 'revoke all on all sequences in schema public from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'grant all on all tables in schema public to service_role';
    execute 'grant all on all sequences in schema public to service_role';
    execute 'grant execute on function public.owns_trip_plan(uuid) to service_role';
  end if;
end $$;
