# Issue #45 여행 수정·삭제 구현 기록

## State

- 기준: `origin/develop` `ae3926ac6428c1d93cd57372fbafb8dd31d34544`
- 기능 브랜치: `feat/45-trip-update-delete`
- 공개 API: `PATCH /api/v1/trips/{tripId}`, `DELETE /api/v1/trips/{tripId}`
- OpenAPI inventory: 22 operations, `validate_openapi_frontend_readiness.py --mode 22`
- 외부 provider 호출, TMAP geometry, 사용자 원문의 저장·로그 추가 없음

`docs/contracts/domains/trips/contract.json` v1은 #45 구현 이전 storage drift를 기록한 감사 기준이다. #45 runtime wire 계약과 migration의 구현 증거는 생성 OpenAPI, 이 문서, PostgreSQL 통합 테스트를 함께 권위 자료로 사용한다.

## PATCH 계약

`If-Match`는 필수 strong revision validator다.

```http
If-Match: "trip-44000000-0000-0000-0000-000000000044-r1"
```

- exact runtime 형식: `"trip-{lowercase canonical UUID}-r{positive revision}"`
- missing: `400 IF_MATCH_REQUIRED`
- malformed/weak/wildcard/list: `400 INVALID_IF_MATCH`
- 다른 trip ID 또는 stale revision: `409 TRIP_VERSION_CONFLICT`
- owner predicate를 잠금 쿼리에 포함하며 타 소유자는 `404 TRIP_NOT_FOUND`
- 성공 시 revision을 정확히 한 번 증가시키고 새 `ETag` 반환

Request는 closed presence body다. 허용 필드는 `title`, `startDate`, `endDate`, `timezone`, `userPace`, `transportModes`이며 한 개 이상 필요하다. 명시한 필드는 `null`일 수 없다. 요청이 받지 않는 time/cost/distance/risk 정보는 입력 계약에 없다.

| 실제 변경 | active schedule | 결과 |
|---|---|---|
| 제목 | 유지 | `scheduleEffect=maintained`, 재생성 불필요 |
| pace/교통수단 | 없음 | status `draft`, `scheduleEffect=invalidated`, 재생성 필요 |
| pace/교통수단 | 있음 | active version `superseded`, pointer/score 제거, status `draft`, 재생성 필요 |
| 날짜/timezone | 일정 버전 없음 | 자식 범위 검증 후 `trip_days` 정확히 재구성 |
| 날짜/timezone | 일정 버전 있음 | `409 TRIP_REGENERATION_REQUIRED`, 무변경 |
| 모든 필드 | terminal trip | `409 TRIP_TERMINAL_STATE_CONFLICT`, 무변경 |

## DELETE 계약

- 성공: `204`, body/content 없음
- 반복 삭제와 타 소유자 접근: `404 TRIP_NOT_FOUND`
- `live` 상태 또는 queued/running generation·compute·revision run: `409 TRIP_DELETE_CONFLICT`
- completed/cancelled/failed: `409 TRIP_TERMINAL_STATE_CONFLICT`
- trip aggregate 자식은 FK cascade로 제거
- 공유 `tour_places`, `data_import_runs`, `user_profiles`, `auth.users`는 보존

## Database

`20260905000000_trip_update_delete_contract.sql`은 다음을 적용한다.

- `trip_plans.revision bigint not null default 1`과 양수 check
- 날짜/timezone 변경 시 기존 일정 버전이 있으면 차단
- transport event와 accommodation이 새 날짜 범위를 벗어나는 변경 차단
- trip 삭제 시 aggregate 소유 데이터 cascade 및 공유 fact 보존
- compose test/live/docker와 smoke check에 같은 migration 순서 반영

Repository는 `SELECT ... FOR UPDATE`로 owner-scoped root를 잠근 뒤 revision을 검사한다. 같은 revision의 동시 writer 두 개 중 하나만 성공하고 다른 하나는 version conflict다. 검증, schedule 무효화, Day 재구성, revision 증가와 삭제는 각각 단일 트랜잭션이다.

## Evidence

- `TripEntityTagTest`: strong ETag 생성과 fail-closed parser
- `TripMutationServiceTest`: patch canonical validation과 store 위임
- `TripControllerRedIntegrationTest`: header/body/query/status/media 계약
- `JdbcTripMutationIntegrationTest`: 실제 PostgreSQL 원자성, 동시 writer, cascade/preservation, live/run/terminal 충돌
- `TripUpdateDeleteMigrationContractTest`: migration과 compose mount 계약
- `TripOpenApiIntegrationTest`: 22-operation 생성 artifact와 PATCH/DELETE schema/example/status
- Python frontend-readiness tests: historical 9/16/20과 current 22 inventory 고정

## Gaps and risks

- #45는 독립 Reviewer 승인과 `develop` 병합 전이다. 기능 브랜치 검증을 배포 상태로 간주하지 않는다.
- FE 코드는 이 작업에서 수정하지 않았다. FE는 상세 조회의 `ETag`를 side metadata로 보존하고 PATCH에 그대로 전달해야 한다.
- 기존 trips canonical v1 machine contract의 storage drift 문구는 당시 감사 기록이므로 소급 변경하지 않았다. 다음 contract version에서 runtime revision 형식과 22-operation inventory를 정식 승격한다.
