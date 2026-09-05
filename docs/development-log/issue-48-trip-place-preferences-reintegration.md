# Issue #48 여행 희망·회피 장소 API 현재 스택 재통합

## 2026-09-06 기준과 TDD

- 기준: `origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56`에서 전용 브랜치를 만들고, 검증된 #47 `b68f65fb7b9d13aa2d7a654f61175565487e81b8`을 일반 merge했다.
- #48 고유 구현 소스: `fcec43116c14c98795d60e62a0412feda9520cf1`. 후속 작업 참고본 `26a4c03`은 conflict resolution 비교에만 사용했다.
- Red: `python3 -m unittest scripts.tests.test_issue48_reintegration_contract -v`에서 store/migration과 Docker init `043` 부재로 3건 실패했다.
- Reviewer Red: focused Gradle 테스트에서 전용 Problem Definitions 부재로 `compileTestJava`가 실패했고, unsigned UUID ordering·strict JSON·coordinator·ACL 테스트를 먼저 확장했다.
- Refactor Red: active mode31 generator 검증에서 `tripPlacePreferencesUpdate`가 network-free verifier 필수 operation 목록에 없음을 1건 실패로 확인했다.
- PostgreSQL Red: 첫 focused 실행에서 16개 중 15개가 통과했고, stale ETag 시나리오가 문법 오류 문자열을 사용해 기대한 version conflict 대신 `INVALID_IF_MATCH`를 반환했다. 동일 trip의 유효한 revision ETag로 fixture를 고쳤다.
- Reviewer 2차 Red: clock regression 요청에서 child/응답은 monotonic 시각인데 root `updated_at`은 과거 요청 시각으로 저장되는 불일치를 actual PostgreSQL로 재현했다. OpenAPI에서는 실제 503 분기와 달리 place-preferences 503 응답이 제거되는 3건 실패를 확인했다.
- Green: 장소 선호 service/controller/OpenAPI/architecture/migration focused 6 suite와 Python OpenAPI/contract 83 test가 통과했다.
- Reviewer 2차 Green: coordinator가 root lock 뒤 계산한 단일 monotonic committed timestamp를 root CAS와 place child/payload에 전달한다. 저장소·provisioning 장애의 `503 TRIP_DATA_UNAVAILABLE`을 controller, named OpenAPI example, runtime manifest에 일치시켰다.
- Coordinator 호환성: 기존 accommodation consumer의 unit/architecture 테스트를 함께 통과시켜 기존 `execute` 경계와 새 monotonic extension이 공존함을 확인했다.
- PostgreSQL Green: store/concurrency/migration/RLS/ACL focused 3 suite 17 test가 disposable PostgreSQL에서 통과했고 Testcontainers 잔여물은 0건이다.

## 구현 경계

- `20260908000000_trip_place_preference_contract.sql`은 #46/#47 뒤의 append-only migration이며 compose와 smoke의 init slot은 `043`이다.
- 장소 선호 store는 canonical `TripAggregateMutationCoordinator`를 소비한다. root row lock, terminal 판정, revision CAS와 active schedule 무효화 로직을 복제하지 않는다.
- clock regression에서도 coordinator가 root lock 아래 `max(requestedAt, currentUpdatedAt + 1µs)`를 정하고 root, child와 응답에 같은 commit timestamp를 사용한다. 뒤이은 canonical no-op은 revision, `updatedAt`, active schedule을 보존한다.
- request body는 parse 전 1 MiB로 제한하고 JSON `null`, duplicate member, coercion, trailing token, 잘못된 content type을 `400 INVALID_REQUEST`로 거부한다.
- 같은 priority의 UUID는 PostgreSQL `uuid`와 같은 unsigned 16-byte 순서로 canonicalize한다. 같은 canonical 요청은 revision, `updatedAt`, active schedule을 유지한다.
- migration은 테이블의 `PUBLIC`·`anon`·`authenticated` 직접 권한과 신규 trigger 함수의 기본 `PUBLIC EXECUTE`까지 회수한다. `service_role`에는 필요한 CRUD만 남긴다.
- OpenAPI historical mode24/25/27/29/30을 보존하고, place-preferences 한 건을 추가한 exact mode31을 active gate·manifest·generator·network-free verifier·문서에 연결한다.
- 실제 저장소·provisioning 실패의 `503 TRIP_DATA_UNAVAILABLE`은 문서, named example과 runtime-only manifest에 동일하게 노출한다.
- canonical contract는 migration 구현 사실을 기록하되 Reviewer 승인과 전체 품질 게이트 전에는 readiness를 `not-ready`로 유지한다.

## 검증 제한과 인계

- 외부 datasource, Supabase, MCP, Firebase 환경은 사용하지 않고 비밀정보를 출력하지 않는다.
- 요청 범위에 따라 루트 전체 quality gate와 Docker smoke는 실행하지 않는다.
- disposable PostgreSQL store/concurrency/RLS/migration QA와 generated OpenAPI mode31 검증을 완료했다. 전체 gate와 Docker smoke는 요청 범위에 따라 다음 승인 단계에 남긴다.
