-- #53/#79/#95: 생성 전용 복구·후보 만료 기반. 기존 migration은 변경하지 않는다.
begin;

lock table public.itinerary_generation_runs, public.itinerary_generation_candidates
  in access exclusive mode;

do $$
begin
  if exists (select 1 from public.itinerary_generation_runs where status = 'running') then
    raise exception using errcode = '23514', message = 'generation workers must be drained before migration';
  end if;
end;
$$;

alter table public.itinerary_generation_runs
  add column attempt_count integer not null default 0 check (attempt_count between 0 and 3),
  add column fencing_token bigint not null default 0 check (fencing_token >= 0),
  add column lease_owner text check (lease_owner is null or length(lease_owner) between 1 and 100),
  add column lease_expires_at timestamptz,
  add column heartbeat_at timestamptz,
  add column next_attempt_at timestamptz,
  add column outcome text check (outcome in ('success', 'insufficient_feasible_routes')),
  add column retained_until timestamptz,
  add constraint generation_lease_state_check check (
    (status = 'running' and lease_owner is not null and lease_expires_at is not null
      and heartbeat_at is not null and attempt_count > 0 and fencing_token > 0)
    or (status <> 'running' and lease_owner is null and lease_expires_at is null and heartbeat_at is null)
  ),
  add constraint generation_outcome_state_check check (outcome is null or status = 'succeeded'),
  add constraint generation_retention_check check (
    retained_until is null or (completed_at is not null and retained_until = completed_at + interval '7 days')
  );

create unique index generation_one_active_day
  on public.itinerary_generation_runs (trip_plan_id, trip_day_id)
  where status in ('queued', 'running');
create index generation_claim_queue
  on public.itinerary_generation_runs (coalesce(lease_expires_at, next_attempt_at, created_at), id)
  where status in ('queued', 'running');
create index generation_terminal_retention
  on public.itinerary_generation_runs (retained_until, id)
  where retained_until is not null;

alter table public.itinerary_generation_candidates
  add column strategy text check (strategy in ('balanced', 'relaxed', 'experience_max')),
  add column feasibility text check (feasibility in ('feasible', 'feasible_with_caution')),
  add column expires_at timestamptz,
  add constraint generation_candidate_expiry_check check (
    expires_at is null or expires_at = created_at + interval '24 hours'
  );
create unique index generation_candidate_strategy
  on public.itinerary_generation_candidates (generation_run_id, strategy)
  where strategy is not null;

-- 생성 내부 lease와 후보 상태는 공개 Data API가 아닌 소유권 검증 API로만 제공한다.
alter table public.itinerary_generation_runs enable row level security;
alter table public.itinerary_generation_candidates enable row level security;
revoke all on public.itinerary_generation_runs, public.itinerary_generation_candidates from anon, authenticated;

commit;
