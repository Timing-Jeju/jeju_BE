# Issue #51 일정 항목 변경 API 개발 기록

## 범위와 기준

- 최초 기준: `origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56`
- #50 반영 기준: merge commit `03595cd66a81d3ce65dc304c8a4073ea03b598b7`
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

### 공식 리뷰 보완 RED → GREEN

#50 병합 후 공식 리뷰 지적을 재현하는 회귀 테스트를 추가했다. 실제 PostgreSQL 집중 실행에서
다음 RED를 확인했다.

```text
./gradlew test --tests 'com.timingjeju.api.domain.schedule.repository.JdbcScheduleMutationStoreIntegrationTest'
DELETE: ScheduleMutationResult가 빈 changedItemIds를 거부해 IllegalArgumentException
empty Day DELETE/MOVE: expected SCHEDULE_DAY_EMPTY but was SCHEDULE_LEG_INCOMPLETE
concurrent PATCH: 공통 coordinator의 TripException(TRIP_VERSION_CONFLICT)을 테스트가 분류하지 못함
```

GREEN에서는 PATCH/DELETE/reorder/move도 #50의 `TripAggregateMutationCoordinator`로 통합해
소유권 잠금, ETag 검증, terminal 상태 거부, revision 증가를 하나의 경계로 사용한다. PATCH,
reorder, move의 `changedItemIds`는 새 active version에 실제 존재하는 복사 ID만 반환하고,
삭제 항목은 새 namespace에 대응 ID가 없으므로 DELETE는 빈 배열을 반환한다. 마지막 항목을
삭제하거나 다른 Day로 옮겨 원본 Day가 비게 되는 동작은 `422 SCHEDULE_DAY_EMPTY`로 명시하고
전체 transaction을 rollback한다. 수정 후 store와 controller 집중 테스트가 모두 성공했다.

OpenAPI readiness도 #50의 24-operation inventory에는 schedule create만 포함하고, #51의
28-operation inventory에 PATCH/DELETE/reorder/move 네 건을 추가하도록 분리했다. mode 24가
#51 endpoint를 allowlist 밖으로 거부하는 테스트와 mode 28 exact inventory 테스트를 각각
추가했으며 관련 Python 43 tests가 성공했다.

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
