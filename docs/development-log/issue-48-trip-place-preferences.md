# Issue #48 여행 희망·회피 장소 API 개발 로그

## 목표

현재 사용자에게 저장된 canonical 장소만 trip의 희망·회피 조건으로 전체교체하고, 일정이 존재하면 조건 변경과 일정 무효화를 하나의 transaction으로 처리한다.

## RED

- application service가 없어서 단위 테스트 compile 실패를 확인했다.
- JDBC store bean이 없어 repository integration test가 실패하는 상태를 확인했다.
- endpoint 미구현으로 controller test의 `404`를 확인했다.
- migration 미존재와 OpenAPI 응답 schema 누락을 각각 contract test로 확인했다.

## GREEN

- strong `If-Match`, owner scope, terminal 상태와 canonical UUID를 fail-closed로 검증했다.
- 저장 장소 소유권과 active Tour place 상태를 잠금 조회하고, 같은 장소의 두 role을 허용하지 않았다.
- 전체교체, 빈 배열 clear, canonical no-op, active schedule invalidation을 구현했다.
- DB primary identity를 `(trip_plan_id, place_id)`로 강화하고 priority·Day 및 여행기간 축소 불변조건을 trigger로 강제했다.
- browser role의 table 권한을 제거하고 service role의 제한된 CRUD만 유지했다.
- canonical `preferences-transport` 계약을 생성 OpenAPI에 투영하고 mode 21 exact inventory를 추가했다.
- FE 저장소를 수정하지 않고 TypeScript client 생성 script와 handoff 문서를 제공했다.

## REFACTOR와 검증

- application model, store port, JDBC adapter, controller/ApiDocs/DTO를 분리했다.
- malformed JSON과 domain 제약을 `400/404/409/422/503` Problem Details로 고정했다.
- raw provider payload, TMAP 원문·geometry, 사용자 원문과 JWT를 새 저장 구조에 추가하지 않았다.
- 최종 exact-HEAD 품질 Gate 결과와 commit SHA는 Issue 댓글 및 로컬 quality-gate state에 기록한다.
