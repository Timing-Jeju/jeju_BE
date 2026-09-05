# Issue #51 일정 항목 변경 API 개발 기록

## 범위와 기준

- 기준: `origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56`
- 브랜치: `feat/51-schedule-item-mutations`
- endpoint: 일정 항목 PATCH/DELETE, 전체 순서 PUT, Day 이동 POST
- 기준 계약: `docs/contracts/domains/schedules/contract.json`과 Issue #88

## Red → Green → Refactor

최초 RED에서는 유효한 `PATCH /api/v1/trips/{tripId}/schedule-items/{itemId}`가 새
`user_edit` version을 활성화하고 `200`을 반환해야 한다고 Controller 통합 테스트로 먼저
고정했다. endpoint가 없는 상태에서 기대 `200` 대신 실제 `404`로 실패했다.

```text
./gradlew test --tests 'com.timingjeju.api.domain.schedule.controller.ScheduleControllerIntegrationTest.PATCH_schedule_item은_새_user_edit_version을_활성화하고_200을_반환한다'
Status expected:<200> but was:<404>
```

Green에서는 PATCH/DELETE/reorder/move를 공통 idempotency·강한 ETag·expected active selector에
연결했다. 모든 변경은 여행 row를 잠근 뒤 기존 active의 item을 새 UUID로 복제하고 survivor
progress를 새 item에 연결한다. completed target, 외부 item/Day, 잘못된 permutation과 제주
현지 날짜·시간창 위반은 canonical Problem Details로 닫는다.

Refactor에서는 네 작업의 version 생성·leg 재구성·seal·pointer CAS를 한 transaction 경계로
통합했다. 의미가 같은 기존 leg는 provider 원본이나 geometry를 복제하지 않고 정규화된 이동
근거와 duration만 재사용하며, 새 from-item 종료시각에서 departure/arrival을 다시 계산한다.
snapshot 또는 보수적 PostGIS 도보 근거로도 다음 item 시간창을 만족하지 못하면
`SCHEDULE_LEG_INCOMPLETE`로 전체 rollback한다. 요청 시 외부 API·MCP·LLM 호출은 없다.

독립 Reviewer의 첫 검토에서는 두 동작을 추가 RED로 확인했다. 장소가 바뀐 PATCH가 이전
`fixture` leg를 복사했고, 실제 `[SECOND, FIRST]` same-Day reorder는 기존 항목 시간을 그대로
따라가 `SCHEDULE_LEG_INCOMPLETE`로 실패했다. 수정 후에는 원본과 편집 항목의 item type,
정규화 place identity, 시작·종료 시각 및 leg transport mode가 모두 같을 때만 route 속성을
재사용한다. Reorder는 Day의 기존 `plannedStartAt` 슬롯을 제출 순서에 재배정하고 각 항목의
stay로 종료 시각을 다시 계산하며, 겹침·시간창 위반은 원자적으로 거부한다. 두 RED와 기존
memo/stay 회귀를 포함한 실제 PostgreSQL 집중 테스트는 모두 Green으로 전환됐다.

Swagger operation 계약도 Controller 구현에서 `controller/docs/ScheduleMutationApiDocs`로
이동해 구현과 문서 경계를 분리했으며 생성 OpenAPI operation ID와 응답 schema는 유지했다.

## 검증

- Controller: 네 endpoint, duplicate/unknown JSON, semantic JSON hash, 1 MiB와 HTTP framing 검증 성공
- 실제 PostgreSQL: PATCH memo null/progress, DELETE first/middle/last, exact permutation,
  실제 same-Day swap과 시간 슬롯 재배정, cross-Day move, completed guard, 두 동시 device의
  단일 commit, 실패 rollback 성공
- schedule canonical validator 및 관련 Python 38 tests 성공
- OpenAPI frontend-readiness mode 28: `28 operations` 성공
- `./gradlew --no-daemon clean check`: 13분 56초, 성공
  - 일반 test 1,737건과 JaCoCo 성공
  - 환경 조건부 live test만 skip
- `./scripts/docker-smoke-test.sh`: 성공
  - API health, clean/legacy migration, 실제 2-session 동시성, 음수 제약,
    schema/PostGIS fixture 검증 성공
  - 종료 후 `timing-jeju-smoke` container/network/volume 잔존 없음

## 운영 경계

- 공개 JSON schema는 `docs/contracts/domains/schedules/contract.json`을 그대로 OpenAPI에 투영한다.
- semantic JSON property 순서와 공백은 idempotency hash에 영향을 주지 않으며 DELETE query는
  canonical selector payload로 hash한다.
- `Transfer-Encoding`, 중복·비정상·실제 body와 불일치하는 `Content-Length`는 operation 전에
  `400 INVALID_REQUEST`로 거부한다.
- 기존 Supabase schema의 schedule version/item/leg/progress와 deferred constraint로 충분하므로
  이 Issue는 새 migration이나 환경변수를 추가하지 않는다.

## PR #206 리뷰 보정 (2026-09-04)

이 절은 최초 구현 기록을 소급해 바꾸지 않고, 리뷰 보정 브랜치
`fix/51-pr206-review-remediation`에서 새로 관측한 증거만 기록한다. PR #206의 exact HEAD
`af00c21fafbadb78a4c0728c6f16bd72e172ec5c`에서 시작했고, #51의 선행 조건인 #50
source-approved final `44769320539a66eb73087fa4c51a4ca72207dea5`를 merge commit
`8a0105f`로 full history와 함께 통합했다.

### 실제 RED

- `./gradlew --no-daemon test --tests '*ScheduleItemCreateArchitectureSourceTest'`
  - 4건 중 3건 실패: 편집 네 endpoint가 canonical coordinator를 쓰지 않았고,
    `changedItemIds`가 old namespace였으며, required-reference sealing 및 빈 Day Problem 계약이 없었다.
- `python3 -m unittest scripts.tests.test_openapi_frontend_readiness.OpenApiFrontendReadinessTest.test_mode24는_schedule_item_create를_exact_inventory로_검사한다 scripts.tests.test_openapi_frontend_readiness.OpenApiFrontendReadinessTest.test_mode28은_일정_편집_전체를_exact_inventory로_검사한다`
  - mode24가 편집 네 endpoint까지 요구해 historical 24-operation 계약이 실패했다.
- `./gradlew --no-daemon sliceTest --tests '*ScheduleOpenApiIntegrationTest'`
  - 새 OpenAPI 회귀 1건 실패: PATCH 409 examples에
    `TRIP_TERMINAL_STATE_CONFLICT`가 없었다.

### Green과 Refactor

- 일정 생성·편집 store의 별도 trip row lock/CAS를
  `TripAggregateMutationCoordinator` 계획으로 통합했다. completed/cancelled/failed 상태는
  coordinator가 mutation body 실행 전에 `409 TRIP_TERMINAL_STATE_CONFLICT`로 닫는다.
- 새 version을 seal/activate하기 전에 `assert_schedule_item_required_references`를 명시적으로
  실행한다. #50 migration의 CHECK, deferred trigger, helper ACL과 함께 legacy invalid reference를
  포함한 실패는 transaction 전체를 rollback한다.
- PATCH/move/reorder의 `changedItemIds`는 복제 과정의 old→new map을 사용해 새 active version
  namespace를 반환한다. DELETE는 제거된 old ID를 노출하지 않고 빈 배열을 반환한다.
- 모든 Day는 sealed version에서 적어도 한 항목을 유지한다. 단일 항목 DELETE와 다른 Day로의
  이동은 정확히 `422 SCHEDULE_DAY_EMPTY`로 거부한다. 이 금지 정책은 기존
  `assert_schedule_day_coverage`가 candidate/active sealing 때 모든 trip Day를 검사하는 DB
  product contract에 맞춘 것이다.
- frontend readiness를 historical mode24(기존 23 + create)와 mode28(24 + 네 edit)로 분리했고,
  missing/extra를 각 모드에서 독립적으로 검증한다.

### 보정 검증 범위

- architecture source: 4건 성공
- Controller bounded integration: `ScheduleControllerIntegrationTest` 성공
- OpenAPI bounded slice: 3건 성공
- Python schedule/OpenAPI: 55건 성공
- `testClasses` 성공으로 PostgreSQL 회귀 test source의 컴파일을 확인했다.

사용자 승인 범위에 따라 실제 DB, Testcontainers, Docker, live Supabase, 전체 heavy gate는
실행하지 않았다. `openApiDocs`는 내부적으로 broad `integrationTest`를 실행해 4분 이상
진행되었으므로 중단하고 위 bounded OpenAPI slice와 exact Python mode 테스트로 대체했다.
따라서 terminal 네 endpoint의 DB unchanged, legacy invalid-reference atomic rollback,
동시성 및 required-reference migration은 작성된 PostgreSQL integration test를 실제 DB에서
추가 확인해야 한다.

## 최신 source stack 재통합 (2026-09-06)

- exact base: `0d275a285f6cf66f5e414a3bac558338953f1508` (#50 source-approved current stack)
- 브랜치: `fix/51-schedule-item-mutations-current-stack`
- 보존 범위: #50 POST와 공용 `TripAggregateMutationCoordinator.executeMonotonic`, #46/#47/#48,
  #78 compose/runtime 계약

### Red → Green → Refactor 증거

- 최초 architecture Red는 네 편집 endpoint와 printable-ASCII 멱등성 공통 경계,
  monotonic coordinator, copied item namespace가 최신 base에 없음을 확인했다.
- 추가 Red는 future PostgreSQL 동시 경합 fixture가 coordinator의 `TripException`이 아니라
  `ScheduleException`을 잡고 있었고, 통합 migration의 현재 trigger/check 이름 대신 폐기된 이름을
  사용함을 확인했다. source architecture test를 먼저 추가한 뒤 fixture를 보정했다.
- OpenAPI Red는 cherry-pick 충돌 뒤 `SCHEDULE_MUTATION_OPERATIONS` half-merge와 edit 네 endpoint의
  runtime manifest/operation documents/header inventory 누락을 드러냈다. mode24는 create-only,
  mode28은 create+edit 네 개, later historical mode25/27/29/30/31은 출시 당시 목록,
  active mode33 selector는 최신 37-operation inventory로 분리했다.
- mocked HTTP에서 네 편집 endpoint의 stale ETag가 `TripException.versionConflict()`의 canonical
  `409 TRIP_VERSION_CONFLICT`를 반환하고 `Retry-After`를 보내지 않음을 검증했다. OpenAPI 409는
  처리 중 멱등성 충돌에서 사용할 수 있는 optional `Retry-After`를 문서화한다.

### 합성 migration

동일 `20260907000001` migration에 #50의 composite transport FK/index, sealing wrapper와 ACL을
유지하면서 #51의 `place_visit`/숙소/교통-event/Java `Character.isWhitespace` 기반 title 계약과
legacy audit를 합성했다. compose migration 순서 038→044→045와 #78 환경은 변경하지 않았다.

### DB-free 검증

- architecture source test: Green
- mocked `ScheduleControllerIntegrationTest`와 `ScheduleOpenApiIntegrationTest`: Green
- `openApiDocs`: Green
- generated artifact frontend readiness active `--mode 33`: `37 operations` Green
- schedule/OpenAPI/migration/current-stack Python: 70 tests Green
- Spotless: Green

승인 범위에 따라 실제 PostgreSQL/Testcontainers/Docker/root full quality gate/live Supabase는
실행하지 않았다. 후속 DB 검증 대상은 `JdbcScheduleMutationStoreIntegrationTest`의 실제 2-thread
동일 ETag 경합(성공 1, canonical conflict 1, active version 1), stale revision rollback,
legacy invalid-reference rollback, N-1 leg/seal/activation 원자성이다.

## Astra 리뷰 엄격 JSON 보정 (2026-09-06)

- Red: `ReorderScheduleCommand`의 top-level/nested null 요소가 `ScheduleException` 대신 NPE를
  던졌고, 실제 HTTP에서 PATCH `stayMinutes:1.9`, `memo:123`, `required:"true"` 및
  reorder/move의 잘못된 scalar·collection shape가 400 경계 밖으로 누출됐다.
- Green: DTO 역직렬화 전에 endpoint-local raw `JsonNode` shape를 검사한다. PATCH의 memo null과
  omitted 의미는 유지하면서 textual/integral/boolean을 exact 검사하고, reorder의 Day/object/list
  중첩 구조와 move scalar를 엄격히 검사한다. 전역 ObjectMapper는 변경하지 않았다.
- Refactor: command 생성자도 `List.copyOf` 전에 null collection/element를 검사해 service 직접 호출도
  canonical `400 INVALID_REQUEST`로 수렴한다. DELETE selector는 body가 없고 기존 lowercase canonical
  UUID query 검증을 그대로 사용한다.
- OpenAPI PATCH 예시는 place-visit reference 하나만 포함하는 실행 가능한 payload로 바꾸고,
  DELETE 성공 예시는 전용 `changedItemIds: []`를 사용한다. generated artifact test가 둘을 고정한다.
