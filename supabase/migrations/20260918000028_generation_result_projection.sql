-- #53/#79/#95: AI의 검증된 소수 점수를 정수 반올림 없이 보존한다.
begin;

-- 여행 저장의 도보 선택을 AI walk 입력까지 보존한다. 기존 최대 3개·순위·primary 제약은 유지한다.
alter table public.trip_transport_modes
  drop constraint trip_transport_modes_transport_mode_check,
  add constraint trip_transport_modes_transport_mode_check
    check (transport_mode in ('public_transit', 'rental_car', 'taxi', 'walk'));

-- 이전 완료행의 시각을 임의로 backfill하지 않는다. 새 writer는 성공 전이와 함께 저장한다.
alter table public.itinerary_generation_runs
  add column facts_as_of timestamptz,
  add constraint generation_facts_as_of_check check (
    facts_as_of is null or
    (status='succeeded' and completed_at is not null and facts_as_of<=completed_at
      and isfinite(facts_as_of))
  );
comment on column public.itinerary_generation_runs.facts_as_of is
  '검증된 MCP planning_context.planned_at. 계획 평가 기준이며 개별 외부 fact 최신성 보증이 아니다.';

alter table public.itinerary_generation_candidates
  alter column score type numeric using score::numeric;

comment on column public.itinerary_generation_candidates.score is
  '검증된 AI 후보 점수. 정수로 반올림하지 않으며 기존 0~100 제약을 유지한다.';

-- Run의 7일 조회 보존과 분리한다. 선택된 일정 버전이 살아 있는 동안 Day 이력을 유지한다.
create function timing_jeju_planner_private.generation_result_ids(j jsonb)
returns boolean language plpgsql immutable security invoker set search_path=''
as $$
begin
  if j is null or jsonb_typeof(j)<>'array' then return false; end if;
  return not exists (select 1 from jsonb_array_elements(j) v
    where jsonb_typeof(v)<>'string' or btrim(v #>> '{}')='')
    and (select count(*)=count(distinct value) from jsonb_array_elements(j));
end;
$$;

-- 내부 저장 projection 검사이며 MCP 공개 Schema를 재정의하지 않는다.
create function timing_jeju_planner_private.generation_day_result_valid(h jsonb,e jsonb)
returns boolean language plpgsql immutable security invoker set search_path=''
as $$
declare p jsonb; f jsonb; t jsonb; c jsonb; field text; id text;
  indices jsonb; dependents jsonb; indegrees integer[]; ready integer[];
  head integer:=1; child integer; edges integer:=0; fact_count integer;
  numeric_fields text[]:=array['totalMinutes','visitMinutes','transferMinutes','restMinutes',
    'mealMinutes','bufferMinutes','walkingMinutes','walkingDistanceMeters','taxiPickupBufferMinutes',
    'busWaitMinutes','busDistanceMeters','taxiDistanceMeters','totalDistanceMeters'];
begin
  if h is null or e is null or octet_length(h::text)+octet_length(e::text)>1048576
    or not timing_jeju_planner_private.generation_input_object(h,array[
      'dayId','tripDate','windowStartAt','windowEndAt','dayStartAt','dayEndAt',
      'selectedPlaces','totals','evidenceFactIds'])
    or not timing_jeju_planner_private.generation_input_object(e,array['facts','sourceIds'])
    or not timing_jeju_planner_private.generation_input_uuid(h->'dayId')
    or jsonb_typeof(e->'facts')<>'object'
    or not timing_jeju_planner_private.generation_result_ids(e->'sourceIds')
    or not timing_jeju_planner_private.generation_result_ids(h->'evidenceFactIds')
    or jsonb_array_length(h->'evidenceFactIds') not between 1 and 256
    or jsonb_typeof(h->'selectedPlaces')<>'array'
    or jsonb_array_length(h->'selectedPlaces') not between 1 and 40 then return false; end if;
  if jsonb_typeof(h->'tripDate')<>'string' or (h->>'tripDate') !~ '^\d{4}-\d{2}-\d{2}$' then return false; end if;
  perform (h->>'tripDate')::date;
  select count(*) into fact_count from jsonb_object_keys(e->'facts');
  if fact_count>4096 then return false; end if;
  foreach field in array array['windowStartAt','windowEndAt','dayStartAt','dayEndAt'] loop
    if jsonb_typeof(h->field)<>'string' or (h->>field) !~
      '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?\+09:00$'
      or left(h->>field,10)<>h->>'tripDate' then return false; end if;
    perform (h->>field)::timestamptz;
  end loop;
  if (h->>'windowStartAt')::timestamptz>(h->>'dayStartAt')::timestamptz
    or (h->>'dayStartAt')::timestamptz>=(h->>'dayEndAt')::timestamptz
    or (h->>'dayEndAt')::timestamptz>(h->>'windowEndAt')::timestamptz then return false; end if;
  for id,f in select key,value from jsonb_each(e->'facts') loop
    if btrim(id)='' or not timing_jeju_planner_private.generation_input_object(f,array['sourceIds','inputFactIds'])
      or not timing_jeju_planner_private.generation_result_ids(f->'sourceIds')
      or not timing_jeju_planner_private.generation_result_ids(f->'inputFactIds')
      or not ((e->'sourceIds') @> (f->'sourceIds'))
      or exists (select 1 from jsonb_array_elements_text(f->'inputFactIds') v
        where not jsonb_exists(e->'facts',v)) then return false; end if;
    edges:=edges+jsonb_array_length(f->'inputFactIds');
    if edges>16384 then return false; end if;
  end loop;
  -- Kahn 대기열: 긴 단일 체인에서도 매 단계 전체 fact를 다시 스캔하지 않는다.
  select jsonb_object_agg(n.key,n.idx),array_agg(jsonb_array_length(n.value->'inputFactIds') order by n.idx)
    into indices,indegrees from (
      select key,value,row_number() over (order by key)::integer idx from jsonb_each(e->'facts')
    ) n;
  select coalesce(jsonb_object_agg(a.parent_idx,a.children),'{}'::jsonb) into dependents from (
    select indices->>parent.value parent_idx,jsonb_agg((indices->>n.key)::integer) children
    from jsonb_each(e->'facts') n
    cross join lateral jsonb_array_elements_text(n.value->'inputFactIds') parent(value)
    group by indices->>parent.value
  ) a;
  select coalesce(array_agg(s.idx),'{}'::integer[]) into ready
    from generate_subscripts(indegrees,1) s(idx) where indegrees[s.idx]=0;
  while head<=cardinality(ready) loop
    for child in select value::integer from jsonb_array_elements_text(
        coalesce(dependents->(ready[head]::text),'[]'::jsonb)) loop
      indegrees[child]:=indegrees[child]-1;
      if indegrees[child]=0 then ready:=array_append(ready,child); end if;
    end loop;
    head:=head+1;
  end loop;
  if cardinality(ready)<>fact_count then return false; end if;
  if exists (select 1 from jsonb_array_elements_text(h->'evidenceFactIds') v
    where not jsonb_exists(e->'facts',v)) then return false; end if;
  for p in select value from jsonb_array_elements(h->'selectedPlaces') loop
    if not timing_jeju_planner_private.generation_input_object(p,array[
        'canonicalPlaceId','placeFactId','role','evidenceFactIds'])
      or not timing_jeju_planner_private.generation_input_uuid(p->'canonicalPlaceId')
      or jsonb_typeof(p->'placeFactId')<>'string' or btrim(p->>'placeFactId')=''
      or jsonb_typeof(p->'role')<>'string' or p->>'role' not in ('visit','meal','rest')
      or not timing_jeju_planner_private.generation_result_ids(p->'evidenceFactIds')
      or jsonb_array_length(p->'evidenceFactIds') not between 1 and 64
      or not ((h->'evidenceFactIds') @> (p->'evidenceFactIds')) then return false; end if;
  end loop;
  if exists (select 1 from jsonb_array_elements(h->'selectedPlaces') selected(value)
    group by selected.value->>'role',selected.value->>'placeFactId' having count(*)>1) then return false; end if;
  t:=h->'totals';
  if not timing_jeju_planner_private.generation_input_object(t,numeric_fields ||
    array['estimatedCost','busCost','taxiCost','evidenceFactIds'])
    or not timing_jeju_planner_private.generation_result_ids(t->'evidenceFactIds')
    or not ((h->'evidenceFactIds') @> (t->'evidenceFactIds')) then return false; end if;
  foreach field in array numeric_fields loop
    if jsonb_typeof(t->field)<>'number' or (t->>field) !~ '^[0-9]+$'
      or (t->>field)::numeric>2147483647 then return false; end if;
  end loop;
  foreach field in array array['estimatedCost','busCost','taxiCost'] loop
    c:=t->field;
    if not timing_jeju_planner_private.generation_input_object(c,array['minKrw','maxKrw','isEstimated'])
      or jsonb_typeof(c->'isEstimated')<>'boolean'
      or jsonb_typeof(c->'minKrw')<>'number' or (c->>'minKrw') !~ '^[0-9]+$'
      or jsonb_typeof(c->'maxKrw')<>'number' or (c->>'maxKrw') !~ '^[0-9]+$'
      or (c->>'minKrw')::numeric>(c->>'maxKrw')::numeric
      or (c->>'maxKrw')::numeric>2147483647 then return false; end if;
  end loop;
  return (t->>'totalMinutes')::bigint=(t->>'visitMinutes')::bigint+(t->>'transferMinutes')::bigint+
      (t->>'restMinutes')::bigint+(t->>'mealMinutes')::bigint+(t->>'bufferMinutes')::bigint
    and (t->>'totalDistanceMeters')::bigint=(t->>'walkingDistanceMeters')::bigint+
      (t->>'busDistanceMeters')::bigint+(t->>'taxiDistanceMeters')::bigint
    and (t->'estimatedCost'->>'minKrw')::bigint=(t->'busCost'->>'minKrw')::bigint+(t->'taxiCost'->>'minKrw')::bigint
    and (t->'estimatedCost'->>'maxKrw')::bigint=(t->'busCost'->>'maxKrw')::bigint+(t->'taxiCost'->>'maxKrw')::bigint;
exception when data_exception then return false;
end;
$$;

create table timing_jeju_planner_private.generation_day_results (
  schedule_version_id uuid not null,
  trip_plan_id uuid not null,
  trip_day_id uuid not null,
  schema_version smallint not null default 1 check (schema_version=1),
  history jsonb not null,
  evidence jsonb not null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (schedule_version_id,trip_day_id),
  foreign key (schedule_version_id,trip_plan_id)
    references public.trip_schedule_versions(id,trip_plan_id) on delete cascade,
  foreign key (trip_day_id,trip_plan_id)
    references public.trip_days(id,trip_plan_id) on delete cascade,
  check (history->>'dayId'=trip_day_id::text),
  check (timing_jeju_planner_private.generation_day_result_valid(history,evidence)),
  check (timing_jeju_planner_private.generation_input_object(history,array[
    'dayId','tripDate','windowStartAt','windowEndAt','dayStartAt','dayEndAt',
    'selectedPlaces','totals','evidenceFactIds'])),
  check (timing_jeju_planner_private.generation_input_object(evidence,array['facts','sourceIds']))
);
create index generation_day_results_trip_day_idx
  on timing_jeju_planner_private.generation_day_results(trip_day_id,trip_plan_id);
alter table timing_jeju_planner_private.generation_day_results enable row level security;
revoke all on timing_jeju_planner_private.generation_day_results from public,anon,authenticated,service_role;
grant select,insert on timing_jeju_planner_private.generation_day_results to service_role;

create function timing_jeju_planner_private.guard_generation_day_result()
returns trigger language plpgsql security invoker set search_path=''
as $$
declare selected jsonb;
begin
  if tg_op='UPDATE' then
    raise exception using errcode='23514',message='generation day result is immutable';
  end if;
  if not timing_jeju_planner_private.generation_day_result_valid(new.history,new.evidence)
    or new.history->>'dayId' is distinct from new.trip_day_id::text then
    raise exception using errcode='23514',message='generation day result invalid';
  end if;
  -- 완료 writer와 같은 Trip -> version 순서. 봉인 후에는 INSERT도 불허한다.
  perform 1 from public.trip_plans t where t.id=new.trip_plan_id for share;
  if not found then raise exception using errcode='23514',message='generation day result lineage mismatch'; end if;
  perform 1 from public.trip_schedule_versions v
    where v.id=new.schedule_version_id and v.trip_plan_id=new.trip_plan_id
      and v.status='draft' and v.coverage_through_day_no is not null for share;
  if not found then raise exception using errcode='23514',message='generation day result lineage mismatch'; end if;
  perform 1 from public.trip_days d where d.id=new.trip_day_id and d.trip_plan_id=new.trip_plan_id
    and d.trip_date=(new.history->>'tripDate')::date;
  if not found then raise exception using errcode='23514',message='generation day result lineage mismatch'; end if;
  perform 1 from public.trip_items i where i.trip_plan_id=new.trip_plan_id
    and i.schedule_version_id=new.schedule_version_id and i.trip_day_id=new.trip_day_id
    and i.boundary_role='day_start' and i.planned_start_at=(new.history->>'dayStartAt')::timestamptz;
  if not found then raise exception using errcode='23514',message='generation day result lineage mismatch'; end if;
  perform 1 from public.trip_items i where i.trip_plan_id=new.trip_plan_id
    and i.schedule_version_id=new.schedule_version_id and i.trip_day_id=new.trip_day_id
    and i.boundary_role='day_end' and i.planned_end_at=(new.history->>'dayEndAt')::timestamptz;
  if not found then raise exception using errcode='23514',message='generation day result lineage mismatch'; end if;
  for selected in select value from jsonb_array_elements(new.history->'selectedPlaces') loop
    perform 1 from public.trip_items i join public.tour_places p on p.id=i.place_id
      where i.trip_plan_id=new.trip_plan_id and i.schedule_version_id=new.schedule_version_id
        and i.trip_day_id=new.trip_day_id and i.boundary_role is null
        and i.place_id=(selected->>'canonicalPlaceId')::uuid
        and 'tourapi.place:' || p.content_id=selected->>'placeFactId'
        and i.item_type=case selected->>'role' when 'visit' then 'place_visit'
          when 'meal' then 'meal' else 'free_time' end;
    if not found then raise exception using errcode='23514',message='generation day result lineage mismatch'; end if;
  end loop;
  -- 이력에 존재하는 활동만 검사하면 실제 방문의 누락을 탐지하지 못한다.
  if exists (select 1 from public.trip_items i
    where i.trip_plan_id=new.trip_plan_id and i.schedule_version_id=new.schedule_version_id
      and i.trip_day_id=new.trip_day_id and i.boundary_role is null
      and i.item_type in ('place_visit','meal','free_time')
      and not exists (select 1 from jsonb_array_elements(new.history->'selectedPlaces') hp(value)
        where hp.value->>'canonicalPlaceId'=i.place_id::text
          and hp.value->>'role'=case i.item_type when 'place_visit' then 'visit'
            when 'meal' then 'meal' else 'rest' end)) then
    raise exception using errcode='23514',message='generation day result lineage mismatch';
  end if;
  return new;
end;
$$;
create trigger trg_generation_day_result_guard before insert or update
  on timing_jeju_planner_private.generation_day_results for each row
  execute function timing_jeju_planner_private.guard_generation_day_result();
revoke all on function timing_jeju_planner_private.generation_result_ids(jsonb),
  timing_jeju_planner_private.generation_day_result_valid(jsonb,jsonb),
  timing_jeju_planner_private.guard_generation_day_result() from public,anon,authenticated;
grant execute on function timing_jeju_planner_private.generation_result_ids(jsonb),
  timing_jeju_planner_private.generation_day_result_valid(jsonb,jsonb),
  timing_jeju_planner_private.guard_generation_day_result() to service_role;

comment on table timing_jeju_planner_private.generation_day_results is
  '버전별 최소 선택 Day 이력과 fact/source ID 계보. 외부 fact value, 원본, geometry, 사용자 원문을 저장하지 않는다.';

commit;
