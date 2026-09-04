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

## 병합 후 PR #205 회귀 보완 Red → Green

병합된 `develop` `6cfa98f`를 기준으로 Reviewer finding을 별도 fix branch에서 다시 TDD로 고정했다. 최초 DB-free RED는 `ScheduleItemCreateMigrationContractTest` 4개 중 2개 실패였다. append-only `20260907000001` migration이 없었고 `JdbcScheduleMutationStore`가 자체 `FOR UPDATE`와 revision CAS를 보유했다. 이어 DB coverage test를 먼저 추가했을 때 legacy-invalid upgrade fixture가 없어 `NoSuchFileException`으로 실패했다.

Green에서는 기존 `20260907000000`을 수정하지 않고 후속 migration을 추가했다. migration은 기존 item 전체를 먼저 감사하고 첫 invalid item의 ID와 타입을 포함한 `23514`로 중단한다. 그 뒤 accommodation/arrival/departure와 나머지 타입의 필수·반대 참조를 동일한 exact predicate로 CHECK, row trigger, sealing validator에 적용한다. canonical schema introspection, insert/update/cross-type/sealing 음수 matrix, valid legacy 보존 contract, invalid legacy upgrade fixture/smoke를 추가했다. 실제 PostgreSQL migration 및 copied-invalid aggregate rollback 테스트도 작성했지만 이번 remediation 지시상 실행하지 않았다.

추가 security RED에서는 새 sealing helper의 기본 함수 실행권한이 공개 RPC 경계를 열 수 있음을 고정했다. Green에서 `PUBLIC`, `anon`, `authenticated`의 EXECUTE를 명시적으로 회수하고 `service_role`만 허용했으며 schema contract가 PUBLIC ACL과 세 역할 권한을 직접 검사한다.

여행 aggregate mutation은 #45의 canonical coordinator provenance(`d11b1f7`, `f25cfde`, 최종 `18408bd`)만 최소 이식했다. 일정 item store는 공용 coordinator의 owner lock, terminal/revision fence, root CAS를 사용하고 자체 lock/revision 증분을 제거했다. 일정 version 작성·sealing은 root CAS 전에, active pointer 교체는 CAS 뒤에 같은 transaction에서 수행하며 어느 단계든 실패하면 전체 rollback된다.

DB-free Green 증거는 다음과 같다.

```text
./gradlew --no-daemon test --tests com.timingjeju.api.domain.schedule.repository.ScheduleItemCreateMigrationContractTest --tests com.timingjeju.api.domain.schedule.repository.ScheduleItemCreateArchitectureSourceTest
# BUILD SUCCESSFUL

python3 -m unittest scripts.tests.test_push_notification_database scripts.tests.test_database_hardening
# Ran 52 tests ... OK
```

실제 PostgreSQL/Testcontainers와 Docker smoke, 전체 heavy gate는 작업 범위에서 금지되어 실행하지 않았으며 해당 gate 전까지 상태는 `BLOCKED`다.

### 실제 실행 chronology

실행 시각은 당시 별도로 기록하지 않았으므로 존재하지 않는 시각을 소급해 만들지 않고 commit 전후 순서로 남긴다.

1. `6cfa98f` 직후 `./gradlew --no-daemon test --tests com.timingjeju.api.domain.schedule.repository.ScheduleItemCreateMigrationContractTest`를 실행했다. 4 tests 중 `후속_migration은_item_type별_필수_참조를_감사하고_모든_쓰기_경계에서_강제한다`, `일정_item_store는_공용_trip_aggregate_coordinator_밖에서_lock_CAS_revision을_복제하지_않는다` 2개가 실패했다. 전자는 `20260907000001` 파일 부재, 후자는 coordinator 의존 부재가 핵심 RED였다.
2. `692fc88` 뒤 DB coverage test를 먼저 추가하고 `./gradlew --no-daemon test --tests 'com.timingjeju.api.domain.schedule.repository.ScheduleItemCreateMigrationContractTest.canonical_DB_contract는_schema_negative_legacy_upgrade의_각_경계를_검증한다'`를 실행했다. `legacy_schedule_item_reference_conflict_fixture.sql`의 `NoSuchFileException`으로 RED였다. `27894b3`에서 schema/negative/legacy/actual-PG test source를 채웠다.
3. coordinator source fence 첫 Green 시도는 Java formatter가 만든 `.execute(` 줄바꿈 때문에 테스트의 literal `coordinator.execute(`가 맞지 않아 1개 실패했다. 동작 실패가 아닌 취약한 source assertion으로 판정해 호출과 금지 SQL을 각각 검사하도록 고쳤고 `e1624af`에서 Green이 됐다.
4. sealing helper 권한 test를 먼저 추가하고 동일 migration contract 단일 테스트를 실행했다. `REVOKE EXECUTE ... assert_schedule_item_required_references` 문장이 없어 assertion RED였고 `6532690`에서 PUBLIC/anon/authenticated deny와 service_role allow 및 schema ACL introspection을 Green으로 만들었다.
5. terminal trip 공개 계약은 `ScheduleControllerIntegrationTest.POST_schedule_items는_종료된_trip을_schedule_canonical_409로_반환한다`와 `ScheduleOpenApiIntegrationTest.schedule_item_create는_필수_header_body와_응답을_OpenAPI에_공개한다`를 함께 실행했다. 2 tests 모두 실패했으며 실제 HTTP detail은 global trip 문구였고 OpenAPI 409 examples에는 terminal code가 없었다. fixture/contract/runtime/OpenAPI를 정렬한 뒤 두 테스트와 전체 schedule HTTP/OpenAPI focused suite가 Green이 됐다. Python 단일 테스트는 class 이름을 `ScheduleContractTest`로 잘못 지정해 `AttributeError`가 났으며 기능 RED 증거로 계산하지 않는다. 이후 전체 `scripts.tests.test_schedules_contract` 19개 중 fixture instance 중복 1개 실패를 고친 뒤 19개 Green을 확인했다.
6. 새 row trigger helper는 table trigger 실행 시 호출자에게 함수 EXECUTE가 필요하지 않고 외부 직접 호출도 필요 없다. 따라서 공개 표면을 남길 이유가 없다고 판단했다. migration source test에 deny 계약을 먼저 추가해 `validate_trip_item_required_references()` REVOKE 부재 RED를 확인한 뒤 PUBLIC/anon/authenticated/service_role 모두에서 EXECUTE를 회수하고 schema ACL contract로 고정했다. 이 판단과 마지막 Green 확인 시각은 `2026-09-04 14:42 KST`다.
