# Issue #50 일정 항목 추가 API 개발 기록

## 범위와 기준

- 기준: `origin/develop` `4a82a6bcea99f961458a5d7af3d83ab1cc6240bf`
- 브랜치: `feat/50-schedule-item-create`
- endpoint: `POST /api/v1/trips/{tripId}/schedule-items`
- 포함: 강한 ETag/CAS, idempotency, 불변 일정 version 복제, item 추가, leg 재구성, Supabase migration, OpenAPI와 실제 PostgreSQL 검증
- 제외: 외부 경로 API·MCP 호출, 자동 일정 보정, 프론트엔드 구현

## Red → Green → Refactor

최초 RED에서는 Controller 통합 테스트로 올바른 요청이 `201`과 새 active `user_edit` version을 반환해야 한다고 먼저 고정했다. endpoint가 없어 기대 `201` 대신 실제 `404`로 실패했으며 Issue 댓글에 RED 증거를 기록했다.

Green에서는 강한 여행 ETag와 `expectedActiveScheduleVersionId`의 이중 CAS, UUID idempotency, closed JSON body, 제주 `+09:00` 시간 경계를 구현했다. 기존 active version의 모든 item을 새 UUID로 복제하고 추가 위치를 반영한 뒤, 모든 Day의 인접 pair에 새 leg를 만들어 `draft → active`, 기존 active의 `superseded`, 여행 pointer·revision 변경을 한 transaction에서 수행한다.

Refactor에서는 leg 결정 순서를 기존 의미 동일 leg 재사용, 미만료 mobility snapshot, 보수적 PostGIS 도보 fallback, `422 SCHEDULE_LEG_INCOMPLETE`로 고정했다. 원본 version과 item ID를 재사용하지 않고 실패 시 savepoint 밖에 draft나 부분 leg가 남지 않는지를 실제 PostgreSQL에서 검증했다.

## 확정 구현

- 인증된 owner의 여행·Day·장소·숙소·교통 이벤트만 사용하며 외부 소유 참조는 `404`로 은닉한다.
- `Idempotency-Key`와 강한 `If-Match: "trip-{uuid}-r{revision}"`를 필수로 하고 replay·key 재사용·처리 중 lease를 공통 idempotency 계약에 맞춘다.
- 요청은 duplicate/unknown field, explicit null, 비정상 시간 offset, 범위 밖 체류시간을 fail-closed로 거부한다.
- 새 version의 모든 item은 새 UUID를 받고 정확히 `N-1`개의 연속 leg를 가진다.
- request transaction에서 외부 API·MCP를 호출하지 않는다. snapshot의 정규화 요약만 사용하며 raw TMAP payload, geometry, JWT와 사용자 원문을 새로 저장하거나 로그에 남기지 않는다.
- `20260907000000_schedule_item_create_contract.sql`은 accommodation/transport-event 전용 FK를 같은 여행 범위로 추가한다. 운영 schema 기준은 계속 `supabase/migrations` 하나다.

## 검증

- Controller·OpenAPI 집중 통합 테스트: 성공
- 실제 PostgreSQL repository 통합 테스트: first/middle/last, 원본 불변성, CAS conflict, rollback, snapshot 우선순위 성공
- `./gradlew --no-daemon clean check`: 성공
- `./scripts/quality-gate.sh --setup-validation --scope common`: 성공, Python 전체 611 tests 중 환경 의존 2건 skip
- 일정 canonical validator와 관련 Python 24 tests: 성공
- `./scripts/docker-smoke-test.sh`: 성공
  - clean DB migration, API health, legacy upgrade, 동시성, 음수 제약, schema/PostGIS fixture 포함
  - 종료 후 `timing-jeju-smoke` 컨테이너 잔존 없음

## 남은 Gate

- 구현 커밋·원격 push 후 clean HEAD 기준 공식 `GITHUB_HEAD_REF=feat/50-schedule-item-create ./scripts/quality-gate.sh`를 실행한다.
- Developer self-review로 승인하지 않는다. 독립 Reviewer의 `APPROVED` 기록 전에는 PR을 생성하지 않는다.
- 공식 품질 Gate와 Reviewer Gate를 모두 통과한 뒤 저장소의 PR 생성 절차를 사용한다.

## OpenAPI 공식 Gate 보완

최초 clean HEAD 공식 Gate에서 기능·통합 테스트는 통과했지만 새 POST가 기존 23-operation allowlist 밖이었고, domain tag·parameter/header 설명과 예시·closed request schema·성공/Problem 예시가 빠져 OpenAPI frontend-readiness 단계에서 실패했다.

보완에서는 historical mode 16/20/21/23을 유지한 채 #50 POST만 더한 mode 24를 추가했다. `FrontendOpenApiCustomizer`에 일정 항목 추가 operation을 등록해 canonical schedule 계약에서 request/response schema를 투영하고, quoted strong ETag와 UUID idempotency header, runtime Problem 대표값, request/success/error 예시를 연결했다. 새로 생성한 단일 artifact에서 아래 검증이 성공했다.

```text
python3 scripts/validate_openapi_frontend_readiness.py services/spring-api/build/openapi/openapi.json --mode 24
# OpenAPI frontend-readiness 검사 성공: 24 operations
```

## 리스크와 후속

- title-only item type은 위치 근거가 없으면 인접 leg를 만들 수 없으므로 자동 추측하지 않고 `422`로 닫힌다.
- stored snapshot은 같은 정규화 place pair와 transport mode에 한해 사용한다. snapshot이 없고 좌표 기반 도보 구간도 다음 일정 시작 전에 도착하지 못하면 mutation 전체를 rollback한다.
- 프론트엔드는 mutation 성공 응답의 새 ETag를 다음 편집 요청에 보존하고, `409`에서는 active schedule과 여행 상세를 다시 조회해야 한다.

## 독립 리뷰 보완

첫 독립 리뷰는 MAJOR 4건과 MINOR 1건으로 `CHANGES_REQUESTED`였다. 보완은 각 경계를 테스트로 고정한 뒤 다음과 같이 닫았다.

- request body 크기를 idempotency request 생성 전에 검사해 정확히 1 MiB는 허용하고 1 byte 초과는 `400 INVALID_REQUEST`로 종료한다.
- 일곱 item type 모두 저장된 정규화 장소 ID를 유지한다. `meal`, `free_time`, `custom`의 선택적 `placeId`도 버리지 않으며 위치 없는 숙소·교통 이벤트는 draft 생성 전에 `422 SCHEDULE_ITEM_INVALID`로 거부한다.
- 장소는 `stale=false`, `source_deleted_at is null`, `tombstoned_at is null`, 유효한 `stale_at` 조건을 모두 만족해야 한다. 각 비활성 상태는 `404 PLACE_NOT_FOUND`이며 aggregate fingerprint가 변하지 않는다.
- 숙소·교통 이벤트 Problem 문구를 canonical fixture와 일치시켰다. 생성 OpenAPI는 status별 대표 code 하나가 아니라 endpoint error matrix의 전체 code/type 예시를 공개하고, 처리 중 멱등성 충돌에만 적용되는 `Retry-After`를 문서화한다. mode 24 validator도 이 전체 집합을 fail-closed로 대조한다.
- 프론트엔드 인계 표를 `develop` 23개와 #50 기능 브랜치 1개로 정정했다.

위치 근거는 `place_id` 참조로 보존하고 raw provider 응답·상세 geometry를 item facts에 복제하지 않는다.

두 번째 독립 리뷰에서는 전체 code 목록은 닫혔지만 전역 registry의 다른 domain 문구와 `.example` type을 재사용해 일부 `type/title/detail`이 schedule fixture와 다르다는 MAJOR 1건이 남았다. 이를 해결하기 위해 schedule mutation 전용 Problem 정의와 advice를 분리했다. 따라서 다른 API의 오류 계약을 바꾸지 않으면서 `INVALID_REQUEST`, idempotency 3종, 장소·일정 버전·여행 버전 충돌을 schedule canonical fixture와 exact하게 반환한다. Problem writer는 reset 과정에서도 조건부 `Retry-After`만 안전하게 보존한다. HTTP 통합 테스트와 OpenAPI readiness는 각각 실제 응답과 fixture의 `type/title/status/detail/code/fieldErrors`를 직접 대조한다.

## 병합 후 DB required-reference 보정

PR #205 병합 뒤 독립 리뷰에서 `accommodation`, `arrival`, `departure` typed item의 필수 참조가 DB sealing 경계에서 강제되지 않는 문제가 확인됐다. 이 보정은 최신 `origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56` 기반 `fix/50-pr205-review-findings`에서 append-only migration으로 진행했다.

### First RED

- 테스트 파일: `scripts/tests/test_schedule_item_required_references.py`
- 명령: `python3 -m unittest scripts.tests.test_schedule_item_required_references`
- 정확한 시나리오:
  - `test_append_only_migration_uses_slot_038_before_seed_everywhere`
  - `test_legacy_rows_are_audited_before_required_reference_check`
  - `test_check_and_trigger_enforce_type_consistency_and_trip_ownership`
  - `test_sealing_assertion_rechecks_required_references`
  - `test_assertion_and_trigger_helpers_are_not_client_executable`
- 예상 실패: `20260907000001_schedule_item_required_references.sql`과 038 compose/smoke mount가 아직 없어 5개 테스트가 실패했다. compose subtest를 포함한 unittest 출력은 `FAILED (failures=8)`이었다.

### 최소 GREEN

- `20260907000001_schedule_item_required_references.sql`을 037 생성 계약 다음 append-only 슬롯에 추가했다.
- migration은 constraint 설치 전에 legacy typed item을 감사하며, 임의 보정 없이 item ID와 type이 포함된 `23514` 오류로 중단한다.
- CHECK는 `accommodation_id`와 `transport_event_id`의 유형별 필수·상호 배타성을 강제한다. trigger는 숙소와 교통 이벤트의 동일 여행 소유, arrival/departure와 교통 이벤트 type 일치를 강제하며 부모 교통 이벤트의 사후 type 변경도 차단한다.
- 기존 timeline/leg sealing assertion을 core로 보존하고 공개 `assert_schedule_version_sealable` 진입점에서 required-reference assertion을 함께 실행한다.
- 새 assertion/trigger helper와 공개 sealing helper는 `PUBLIC`, `anon`, `authenticated`의 EXECUTE를 회수하고 `service_role`에만 부여한다.
- 세 compose 파일과 legacy/concurrency smoke migration 열에 038 슬롯을 연결했다.

검증 명령:

```text
python3 -m unittest \
  scripts.tests.test_schedule_item_required_references \
  scripts.tests.test_push_notification_database
# Ran 11 tests ... OK
```

더 넓은 `test_database_hardening`, `test_schedule_consistency_hardening` 및 schedule canonical
계약까지 포함한 최종 Python focused 묶음 99건도 모두 통과했다. 실제 PostgreSQL,
Testcontainers, Docker smoke, full quality gate와 live Supabase 적용은 이 시점에는 실행하지
않았으며 아래 통합 검증에서 별도로 확인한다.

## 병합 후 terminal 여행 공개 계약 보정

일정 항목 추가 저장소가 `completed`, `cancelled`, `failed` 여행을 변경할 수 있었고 schedule
canonical 계약과 생성 OpenAPI도 `409 TRIP_TERMINAL_STATE_CONFLICT`를 공개하지 않던 리뷰
finding을 TDD로 보정했다.

### First RED

- 테스트: `test_schedule_mutations_reject_terminal_trip_with_canonical_conflict`
- 명령: `python3 -m unittest scripts.tests.test_schedules_contract.SchedulesContractTest.test_schedule_mutations_reject_terminal_trip_with_canonical_conflict`
- 기대: 다섯 schedule mutation의 `409` matrix와 canonical Problem fixture에 `TRIP_TERMINAL_STATE_CONFLICT`가 존재한다.
- 실제 실패: `conditions["TRIP_TERMINAL_STATE_CONFLICT"]` 조회에서 `KeyError: 'TRIP_TERMINAL_STATE_CONFLICT'`, `FAILED (errors=1)`.

### 최소 GREEN

- 일정 저장소와 여행 저장소가 함께 사용하는 `TripAggregateMutationCoordinator`를 추가했다.
  이 coordinator가 owner row lock, strong ETag 검증, terminal 상태 차단과 schedule pointer
  revision CAS를 소유하며 일정 adapter의 중복 lock/CAS SQL을 제거했다.
- 다섯 mutation error matrix, schedule error condition과 Problem fixture를 같은 `409` code/type/title/detail로 닫았다.
- `ScheduleProblemDefinitions`의 mutation 전용 정의를 추가해 전역 다른 domain 문구에 의존하지 않고 실제 schedule 응답을 fixture와 일치시켰다.
- POST 일정 항목 추가 OpenAPI 전체 Problem 집합과 runtime manifest에 terminal conflict를 투영했다.
- Controller와 OpenAPI 통합 테스트에서 실제 `type/title/status/detail/code`와 409 example 집합을 확인했다.

검증:

```text
python3 -m unittest scripts.tests.test_schedules_contract scripts.tests.test_spring_openapi
# Ran 34 tests ... OK

python3 scripts/validate_schedules_contract.py
# [OK] Issue #88 불변 일정 조회·편집 계약 검증 통과

./gradlew --no-daemon test --tests 'com.timingjeju.api.domain.schedule.controller.ScheduleControllerIntegrationTest' --rerun-tasks
# BUILD SUCCESSFUL

./gradlew --no-daemon test --tests 'com.timingjeju.api.documentation.ScheduleOpenApiIntegrationTest' --rerun-tasks
# BUILD SUCCESSFUL

python3 scripts/validate_openapi_frontend_readiness.py services/spring-api/build/openapi/openapi.json --mode 24
# OpenAPI frontend-readiness 검사 성공: 24 operations
```

저장소 구현 RED도 별도로 실행했다.

```text
./gradlew test --tests 'com.timingjeju.api.domain.schedule.repository.JdbcScheduleMutationStoreIntegrationTest.terminal_trip은_일정_항목_추가를_409로_원자거부한다'
# completed/cancelled/failed 3 cases: Expecting code to raise a throwable, 3 failed
```

coordinator 적용과 stale ETag 예외 계약 정렬 후 schedule repository, controller/OpenAPI,
trip repository 및 architecture 집중 스위트는 51초에 성공했다. 전체 `openApiDocs`, clean check,
Docker와 공식 root gate는 보완 commit 생성 후 exact SHA에서 다시 실행한다.

## Docker seed 통합 회귀 보정

첫 공식 root gate의 Docker 초기화에서 038 제약 적용 후 기존 `099_seed_fixtures.sql`이
typed item의 필수 FK를 생략해 PostgreSQL이 `arrival schedule item requires only
transport_event_id`로 중단됐다. 애플리케이션은 기동했지만 DB 컨테이너가 종료되어 최종
health check는 `UnknownHostException: postgres`로 실패했다.

로컬 seed의 `trip_items` 열 목록에 `accommodation_id`, `transport_event_id`를 추가하고,
세 schedule version의 arrival/departure/accommodation 항목이 같은 여행의 canonical 숙소·교통
이벤트를 참조하도록 수정했다. 일반 방문·식사 항목은 두 참조를 모두 `null`로 명시했다.
회귀 테스트 `test_local_seed_populates_required_schedule_item_references`가 seed 계약을 고정하며,
격리 Compose에서 PostgreSQL을 새로 초기화해 `healthy` 상태를 확인했다. 이 보정 commit에서
전체 공식 root gate를 다시 실행한다.
