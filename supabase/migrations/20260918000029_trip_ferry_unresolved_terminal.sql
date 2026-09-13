-- Issue #53: 기존 조건 화면의 선박 시각을 항구 추정 없이 보존한다.
-- CLI 생성 파일을 canonical suffix 뒤로 정렬한다. 기존 migration/행/ACL은 변경하지 않는다.
alter table public.trip_transport_events
  drop constraint ck_trip_transport_events_exactly_one_terminal,
  add constraint ck_trip_transport_events_terminal_resolution
    check (
      num_nonnulls(terminal_place_id, terminal_name) = 1
      or (
        transport_type = 'ferry'
        and terminal_place_id is null
        and terminal_name is null
      )
    );

comment on constraint ck_trip_transport_events_terminal_resolution
  on public.trip_transport_events is
  '항공은 확정 터미널 하나, 선박은 항구 미확정 null 허용. 선박 AI 생성은 별도 입력 검증에서 거부한다.';
