-- 기존 public 스키마 계약을 유지하면서 import 실행과 일정·날씨 관계 무결성을 강화한다.

alter table public.data_import_runs
  add column parser_version text not null default 'legacy',
  add column schema_version text not null default 'legacy',
  add column sync_mode text not null default 'full',
  add column scope_key text not null default 'global',
  add column request_fingerprint text,
  add column idempotency_key text,
  add column parent_run_id uuid,
  add column checkpoint_before jsonb not null default '{}'::jsonb,
  add column checkpoint_after jsonb not null default '{}'::jsonb,
  add column retry_count integer not null default 0,
  add column fetched_count integer not null default 0,
  add column inserted_count integer not null default 0,
  add column updated_count integer not null default 0,
  add column skipped_count integer not null default 0,
  add column rejected_count integer not null default 0,
  add column deleted_count integer not null default 0,
  add column staled_count integer not null default 0;

alter table public.data_import_runs
  drop constraint data_import_runs_status_check;

alter table public.data_import_runs
  add constraint data_import_runs_status_check
    check (status in ('running', 'succeeded', 'failed', 'partial', 'cancelled')),
  add constraint chk_data_import_runs_nonblank_fields
    check (
      btrim(source_name) <> ''
      and (source_operation is null or btrim(source_operation) <> '')
      and btrim(data_version) <> ''
      and btrim(parser_version) <> ''
      and btrim(schema_version) <> ''
      and btrim(sync_mode) <> ''
      and btrim(scope_key) <> ''
      and (request_fingerprint is null or btrim(request_fingerprint) <> '')
      and (idempotency_key is null or btrim(idempotency_key) <> '')
      and (error_code is null or btrim(error_code) <> '')
      and (error_message is null or btrim(error_message) <> '')
    ),
  add constraint chk_data_import_runs_json_objects
    check (
      jsonb_typeof(metadata) = 'object'
      and jsonb_typeof(checkpoint_before) = 'object'
      and jsonb_typeof(checkpoint_after) = 'object'
    ),
  add constraint chk_data_import_runs_sync_mode
    check (sync_mode in ('full', 'incremental', 'lazy', 'snapshot')),
  add constraint chk_data_import_runs_nonnegative_counts
    check (
      row_count >= 0
      and retry_count >= 0
      and fetched_count >= 0
      and inserted_count >= 0
      and updated_count >= 0
      and skipped_count >= 0
      and rejected_count >= 0
      and deleted_count >= 0
      and staled_count >= 0
    ),
  add constraint chk_data_import_runs_error_pair
    check (
      (error_code is null and error_message is null)
      or (error_code is not null and error_message is not null)
    ),
  add constraint chk_data_import_runs_state_fields
    check (
      (
        status = 'running'
        and finished_at is null
        and error_code is null
        and error_message is null
      )
      or (
        status = 'succeeded'
        and finished_at is not null
        and error_code is null
        and error_message is null
      )
      or (
        status = 'failed'
        and finished_at is not null
        and error_code is not null
        and error_message is not null
      )
      or (
        status in ('partial', 'cancelled')
        and finished_at is not null
      )
    ),
  add constraint chk_data_import_runs_time_order
    check (finished_at is null or finished_at >= started_at),
  add constraint chk_data_import_runs_parent_not_self
    check (parent_run_id is null or parent_run_id <> id),
  add constraint fk_data_import_runs_parent
    foreign key (parent_run_id)
    references public.data_import_runs (id)
    on delete set null;

create unique index uq_data_import_runs_idempotency
  on public.data_import_runs (
    source_kind,
    source_name,
    source_operation,
    scope_key,
    idempotency_key
  ) nulls not distinct
  where idempotency_key is not null;

create unique index uq_data_import_runs_running_scope
  on public.data_import_runs (
    source_kind,
    source_name,
    coalesce(source_operation, ''),
    scope_key
  )
  where status = 'running';

create index idx_data_import_runs_parent
  on public.data_import_runs (parent_run_id);

alter table public.trip_schedule_versions
  add constraint chk_trip_schedule_versions_base_not_self
    check (base_schedule_version_id is null or base_schedule_version_id <> id);

create function public.assert_schedule_day_coverage(
  target_schedule_version_id uuid,
  target_trip_plan_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
  plan_start_date date;
  plan_end_date date;
  expected_day_count integer;
  actual_day_count integer;
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

  select p.start_date, p.end_date
    into plan_start_date, plan_end_date
  from public.trip_plans p
  where p.id = target_trip_plan_id;

  if not found then
    raise exception 'trip plan % does not exist', target_trip_plan_id;
  end if;

  expected_day_count := plan_end_date - plan_start_date + 1;

  select count(*)::integer
    into actual_day_count
  from public.trip_days d
  where d.trip_plan_id = target_trip_plan_id;

  if actual_day_count <> expected_day_count then
    raise exception 'trip % requires % days but has %',
      target_trip_plan_id, expected_day_count, actual_day_count;
  end if;

  if exists (
    select 1
    from generate_series(1, expected_day_count) expected(day_no)
    left join public.trip_days d
      on d.trip_plan_id = target_trip_plan_id
     and d.day_no = expected.day_no
     and d.trip_date = plan_start_date + (expected.day_no - 1)
    where d.id is null
  ) then
    raise exception 'trip % days must use contiguous numbers and dates from % through %',
      target_trip_plan_id, plan_start_date, plan_end_date;
  end if;

  if exists (
    select 1
    from public.trip_days d
    where d.trip_plan_id = target_trip_plan_id
      and not exists (
        select 1
        from public.trip_items i
        where i.trip_plan_id = target_trip_plan_id
          and i.schedule_version_id = target_schedule_version_id
          and i.trip_day_id = d.id
      )
  ) then
    raise exception 'schedule version % requires at least one item for every trip day',
      target_schedule_version_id;
  end if;
end;
$$;

create or replace function public.validate_schedule_version_sealing()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status in ('candidate', 'active')
     and (tg_op = 'INSERT' or old.status is distinct from new.status) then
    perform public.assert_schedule_version_sealable(new.id, new.trip_plan_id);
    perform public.assert_schedule_day_coverage(new.id, new.trip_plan_id);
  end if;

  return new;
end;
$$;

create or replace function public.require_draft_schedule_version()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  old_status text;
  new_status text;
begin
  if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
    return old;
  end if;

  if tg_op in ('UPDATE', 'DELETE') then
    select v.status
      into old_status
    from public.trip_schedule_versions v
    where v.id = old.schedule_version_id;

    if not found then
      if tg_op = 'DELETE' then
        return old;
      end if;
      raise exception 'schedule version % does not exist', old.schedule_version_id;
    end if;

    if old_status <> 'draft' then
      raise exception 'schedule content can only change while version % is draft',
        old.schedule_version_id;
    end if;
  end if;

  if tg_op in ('INSERT', 'UPDATE') then
    select v.status
      into new_status
    from public.trip_schedule_versions v
    where v.id = new.schedule_version_id;

    if not found then
      raise exception 'schedule version % does not exist', new.schedule_version_id;
    end if;

    if new_status <> 'draft' then
      raise exception 'schedule content can only change while version % is draft',
        new.schedule_version_id;
    end if;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;

  return new;
end;
$$;

alter table public.trip_weather_impacts
  drop constraint trip_weather_impacts_trip_day_id_fkey,
  add constraint fk_trip_weather_impacts_trip_day_plan
    foreign key (trip_day_id, trip_plan_id)
    references public.trip_days (id, trip_plan_id)
    on delete cascade;

create index idx_trip_weather_impacts_day_plan
  on public.trip_weather_impacts (trip_day_id, trip_plan_id);

alter table public.recommendation_candidates
  drop constraint recommendation_candidates_trip_day_id_fkey,
  add constraint fk_recommendation_candidates_trip_day_plan
    foreign key (trip_day_id, trip_plan_id)
    references public.trip_days (id, trip_plan_id)
    on delete cascade;

create index idx_recommendation_candidates_day_plan
  on public.recommendation_candidates (trip_day_id, trip_plan_id);

alter table public.weather_observations
  add constraint chk_weather_observations_precipitation_nonnegative
    check (precipitation_mm is null or precipitation_mm >= 0),
  add constraint chk_weather_observations_wind_nonnegative
    check (wind_speed_mps is null or wind_speed_mps >= 0);

alter table public.weather_forecasts
  add constraint chk_weather_forecasts_precipitation_nonnegative
    check (precipitation_amount_mm is null or precipitation_amount_mm >= 0),
  add constraint chk_weather_forecasts_wind_nonnegative
    check (wind_speed_mps is null or wind_speed_mps >= 0),
  add constraint chk_weather_forecasts_time_order
    check (forecasted_at <= valid_at),
  add constraint chk_weather_forecasts_temperature_order
    check (
      min_temperature_c is null
      or max_temperature_c is null
      or min_temperature_c <= max_temperature_c
    );
