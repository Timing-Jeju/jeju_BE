-- #53: 자유 입력문 대신 canonical 숙소 장소와 닫힌 스타일 코드를 저장한다.
begin;

alter table public.trip_days
  add column lodging_place_id uuid references public.tour_places(id);
create index idx_trip_days_lodging_place on public.trip_days(lodging_place_id)
  where lodging_place_id is not null;

alter table public.trip_plans
  add column planner_style_codes text[] not null default '{}',
  add constraint ck_trip_planner_style_codes check (
    coalesce(array_ndims(planner_style_codes), 1) = 1
    and cardinality(planner_style_codes) <= 7
    and array_position(planner_style_codes, null) is null
    and planner_style_codes <@ array[
      'restaurant','cafe','leisure','cultural_facility','relaxed','trendy','local']::text[]
  );

comment on column public.trip_days.lodging_place_id is '해당 Day 종료 숙소의 canonical place ID';
comment on column public.trip_plans.planner_style_codes is
  '구조화 스타일 코드. trendy/local은 UI 복원용이며 AI 사실 입력으로 사용하지 않는다.';

commit;
