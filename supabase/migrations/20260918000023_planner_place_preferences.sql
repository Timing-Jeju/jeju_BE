-- #53: 기존 전체교체/여행 revision 경계를 유지하며 AI 장소 초안을 확장한다.
begin;

alter table public.trip_place_preferences
  drop constraint trip_place_preferences_preference_type_check,
  add constraint trip_place_preferences_preference_type_check
    check (preference_type in ('must_visit', 'preferred', 'avoid')),
  add column requested_stay_minutes integer
    check (requested_stay_minutes is null or requested_stay_minutes between 1 and 1440);

comment on column public.trip_place_preferences.requested_stay_minutes is
  '사용자가 명시한 체류시간(분). NULL은 미지정이며 임의 기본값으로 보정하지 않는다.';

commit;
